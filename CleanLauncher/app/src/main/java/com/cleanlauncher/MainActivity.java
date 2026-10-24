package com.cleanlauncher;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** Quiet home screen: photo/dark/light background, clock, glass tiles. Dock or List layout. */
public class MainActivity extends Activity {
    static class App { String label, pkg; ComponentName cn; }
    static final int[][] WALL = {{0xFF1F1C2C, 0xFF928DAB}, {0xFF0F2027, 0xFF2C5364},
            {0xFF0B1F14, 0xFF3C6E47}, {0xFF200F0F, 0xFFB5523B}};
    static final String[] BGN = {"Dusk", "Ocean", "Forest", "Ember", "My photo", "Dark", "Light"};
    static final String[] SHOWN = {"Icons and names", "Icons only", "Names only"};

    final ExecutorService io = Executors.newSingleThreadExecutor();
    final Handler ui = new Handler(Looper.getMainLooper());
    final ArrayList<App> all = new ArrayList<App>(), edit = new ArrayList<App>();
    final HashSet<String> sel = new HashSet<String>();
    LruCache<String, Bitmap> cache;
    SharedPreferences sp;
    PackageManager pm;
    Typeface light;
    float d;
    int sw, sh, layout, show, bg, lastBg = -1, mode, focusIdx, fg;
    boolean c24, overlayOpen, swallowUp, receiverOn;
    Bitmap photoBmp;
    FrameLayout root, home;
    LinearLayout panel;
    ViewGroup tiles;
    ListView list;
    TextView title, hint;
    BaseAdapter adapter;

    final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reload(); }
    };

    int dp(int v) { return (int) (v * d + 0.5f); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        pm = getPackageManager();
        sp = getSharedPreferences("p", MODE_PRIVATE);
        d = getResources().getDisplayMetrics().density;
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        sw = Math.max(w, h); sh = Math.min(w, h);
        light = Typeface.create("sans-serif-light", Typeface.NORMAL);
        layout = sp.getInt("layout", 0); show = sp.getInt("show", 0);
        bg = sp.getInt("bg", 0); c24 = sp.getBoolean("c24", false);
        cache = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
            @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
        };

        root = new FrameLayout(this);
        home = new FrameLayout(this);
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(16), dp(12), dp(12));
        GradientDrawable pb = new GradientDrawable();
        pb.setColor(0xF2141418); pb.setCornerRadius(dp(20)); pb.setStroke(dp(1), 0x33FFFFFF);
        panel.setBackgroundDrawable(pb);
        title = text("", 24, Color.WHITE);
        title.setPadding(dp(20), 0, dp(20), dp(8));
        panel.addView(title);
        list = new ListView(this);
        list.setDivider(null);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setSelector(new ColorDrawable(0x33FFFFFF));
        adapter = new PanelAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) { onRow(pos); }
        });
        panel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        hint = text("", 13, 0x88FFFFFF);
        hint.setPadding(dp(20), dp(8), dp(20), 0);
        panel.addView(hint);
        panel.setVisibility(View.GONE);
        root.addView(panel, new FrameLayout.LayoutParams(dp(480), dp(420), Gravity.CENTER));
        setContentView(root);
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
        receiverOn = true;
        reload();
    }

    @Override protected void onStop() {
        if (receiverOn) { unregisterReceiver(pkgReceiver); receiverOn = false; }
        super.onStop();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (overlayOpen) closeOverlay();
        else if (tiles != null && tiles.getChildCount() > 0) tiles.getChildAt(0).requestFocus();
    }

    @Override public void onBackPressed() {
        if (overlayOpen) { if (mode == 1) leaveApps(); else closeOverlay(); }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (k == KeyEvent.KEYCODE_MENU) {
            if (!down) { if (overlayOpen) onBackPressed(); else openOverlay(); }
            return true;
        }
        if (overlayOpen) {
            if (mode == 1 && down && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                int p = list.getSelectedItemPosition(), q = p + (k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                if (p >= 1 && q >= 1 && q <= edit.size()) {
                    Collections.swap(edit, p - 1, q - 1);
                    adapter.notifyDataSetChanged();
                    list.setSelection(q);
                }
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
            if (down && e.getRepeatCount() == 1) { openOverlay(); swallowUp = true; return true; }
            if (!down && swallowUp) { swallowUp = false; return true; }
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------- data ----------
    void reload() {
        io.execute(new Runnable() { public void run() {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            final ArrayList<App> out = new ArrayList<App>();
            HashSet<String> seen = new HashSet<String>();
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                App a = new App();
                a.pkg = pkg;
                a.cn = new ComponentName(pkg, ri.activityInfo.name);
                a.label = String.valueOf(ri.loadLabel(pm));
                out.add(a);
            }
            Collections.sort(out, new Comparator<App>() {
                public int compare(App x, App y) { return x.label.compareToIgnoreCase(y.label); }
            });
            ui.post(new Runnable() { public void run() {
                all.clear(); all.addAll(out); render();
            }});
        }});
    }

    ArrayList<App> homeApps() {
        if (!sp.contains("order")) return new ArrayList<App>(all);
        HashMap<String, App> m = new HashMap<String, App>();
        for (App a : all) m.put(a.pkg, a);
        ArrayList<App> r = new ArrayList<App>();
        String s = sp.getString("order", "");
        if (s.length() > 0) for (String p : s.split(",")) { App a = m.get(p); if (a != null) r.add(a); }
        return r;
    }

    void savePrefs() {
        sp.edit().putInt("layout", layout).putInt("show", show).putInt("bg", bg).putBoolean("c24", c24).apply();
    }

    // ---------- rendering ----------
    TextView text(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setTypeface(light);
        t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    GradientDrawable rr(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill); g.setCornerRadius(dp(18)); g.setStroke(dp(1), stroke);
        return g;
    }

    Drawable glass() {
        int base = bg == 6 ? 0x000000 : 0xFFFFFF;
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, rr(0x5A000000 | base, 0xAA000000 | base));
        s.addState(new int[]{}, rr(0x1F000000 | base, 0x2E000000 | base));
        return s;
    }

    boolean photoExists() { return new File(Environment.getExternalStorageDirectory(), "CleanLauncher.jpg").exists(); }

    Bitmap loadPhoto() {
        try {
            File f = new File(Environment.getExternalStorageDirectory(), "CleanLauncher.jpg");
            if (!f.exists()) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            o.inSampleSize = Math.max(1, Math.min(o.outWidth / sw, o.outHeight / sh));
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap src = BitmapFactory.decodeFile(f.getPath(), o);
            if (src == null) return null;
            Bitmap out = Bitmap.createBitmap(sw, sh, Bitmap.Config.RGB_565);
            float sc = Math.max((float) sw / src.getWidth(), (float) sh / src.getHeight());
            Matrix m = new Matrix();
            m.postScale(sc, sc);
            m.postTranslate((sw - src.getWidth() * sc) / 2, (sh - src.getHeight() * sc) / 2);
            new Canvas(out).drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG));
            src.recycle();
            return out;
        } catch (Throwable t) { return null; }
    }

    Drawable bgDrawable() {
        if (bg != 4) photoBmp = null;
        if (bg < 4) return new GradientDrawable(GradientDrawable.Orientation.TL_BR, WALL[bg]);
        if (bg == 4) {
            if (photoBmp == null) photoBmp = loadPhoto();
            if (photoBmp != null)
                return new LayerDrawable(new Drawable[]{new BitmapDrawable(getResources(), photoBmp), new ColorDrawable(0x66000000)});
            return new ColorDrawable(0xFF0B0B0D);
        }
        return new ColorDrawable(bg == 5 ? 0xFF0B0B0D : 0xFFEDEDEF);
    }

    void render() {
        fg = bg == 6 ? 0xFF111111 : Color.WHITE;
        if (lastBg != bg) { root.setBackgroundDrawable(bgDrawable()); lastBg = bg; }
        home.removeAllViews();
        final boolean lst = layout == 1;

        LinearLayout clk = new LinearLayout(this);
        clk.setOrientation(LinearLayout.VERTICAL);
        String f = c24 ? "HH:mm" : "h:mm";
        TextClock t = new TextClock(this);
        t.setFormat12Hour(f); t.setFormat24Hour(f);
        t.setTextColor(fg); t.setTextSize(lst ? 96 : 72); t.setTypeface(light); t.setIncludeFontPadding(false);
        TextClock dt = new TextClock(this);
        dt.setFormat12Hour("EEEE, d MMMM"); dt.setFormat24Hour("EEEE, d MMMM");
        dt.setTextColor(fg); dt.setTextSize(20); dt.setTypeface(light); dt.setAlpha(0.7f);
        clk.addView(t); clk.addView(dt);

        ArrayList<App> items = homeApps();
        App s = new App(); s.label = "Settings"; items.add(s);

        if (!lst) {
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setClipChildren(false); hs.setClipToPadding(false);
            hs.setPadding(dp(48), dp(24), dp(48), dp(24));
            LinearLayout row = new LinearLayout(this);
            row.setClipChildren(false);
            for (App a : items) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.setMargins(dp(8), 0, dp(8), 0);
                row.addView(tile(a, false), lp);
            }
            hs.addView(row);
            FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            hp.bottomMargin = dp(24);
            home.addView(hs, hp);
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
            cp.leftMargin = dp(64); cp.topMargin = dp(48);
            home.addView(clk, cp);
            tiles = row;
        } else {
            ScrollView sv = new ScrollView(this);
            sv.setVerticalScrollBarEnabled(false);
            sv.setClipChildren(false); sv.setClipToPadding(false);
            sv.setPadding(dp(48), dp(40), dp(48), dp(40));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setClipChildren(false);
            for (App a : items) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = dp(8);
                col.addView(tile(a, true), lp);
            }
            sv.addView(col);
            home.addView(sv, new FrameLayout.LayoutParams(dp(520), -1, Gravity.LEFT));
            clk.setGravity(Gravity.RIGHT);
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            cp.rightMargin = dp(80);
            home.addView(clk, cp);
            tiles = col;
        }
        if (!overlayOpen && tiles.getChildCount() > 0)
            tiles.getChildAt(Math.min(focusIdx, tiles.getChildCount() - 1)).requestFocus();
    }

    View tile(final App a, final boolean lst) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(lst ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        t.setGravity(lst ? Gravity.CENTER_VERTICAL : Gravity.CENTER);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        t.setBackgroundDrawable(glass());
        t.setFocusable(true);
        ImageView iv = null;
        if (show != 2) {
            iv = new ImageView(this);
            int sz = dp(lst ? 40 : 64);
            t.addView(iv, new LinearLayout.LayoutParams(sz, sz));
            icon(a, iv);
        }
        if (show != 1) {
            TextView tv = text(a.label, lst ? 20 : 14, fg);
            tv.setGravity(lst ? Gravity.LEFT : Gravity.CENTER);
            if (iv != null) { if (lst) tv.setPadding(dp(16), 0, 0, 0); else tv.setPadding(0, dp(8), 0, 0); }
            t.addView(tv, lst ? new LinearLayout.LayoutParams(0, -2, 1f)
                    : new LinearLayout.LayoutParams(iv != null ? dp(96) : -2, -2));
        }
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean f) {
                float sc = f ? (lst ? 1.05f : 1.12f) : 1f;
                v.animate().scaleX(sc).scaleY(sc).setDuration(120).start();
                if (f) focusIdx = ((ViewGroup) v.getParent()).indexOfChild(v);
            }
        });
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (a.cn == null) openOverlay(); else launch(a); }
        });
        return t;
    }

    void launch(App a) {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (Exception ex) {
            Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    void icon(final App a, final ImageView iv) {
        if (a.cn == null) { iv.setImageResource(android.R.drawable.ic_menu_preferences); return; }
        iv.setTag(a.pkg);
        Bitmap b = cache.get(a.pkg);
        if (b != null) { iv.setImageBitmap(b); return; }
        io.execute(new Runnable() { public void run() {
            try {
                int px = dp(64);
                Drawable dr = pm.getActivityIcon(a.cn);
                final Bitmap bm = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
                dr.setBounds(0, 0, px, px);
                dr.draw(new Canvas(bm));
                cache.put(a.pkg, bm);
                ui.post(new Runnable() { public void run() {
                    if (a.pkg.equals(iv.getTag())) iv.setImageBitmap(bm);
                }});
            } catch (Exception ignored) {}
        }});
    }

    // ---------- settings overlay ----------
    void openOverlay() {
        overlayOpen = true; mode = 0;
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        panel.setVisibility(View.VISIBLE);
        title.setText("Settings");
        hint.setText("OK: change      Back: close");
        adapter.notifyDataSetChanged();
        list.requestFocus();
        list.setSelection(0);
    }

    void closeOverlay() {
        overlayOpen = false;
        panel.setVisibility(View.GONE);
        home.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        render();
    }

    void enterApps() {
        edit.clear(); sel.clear();
        for (App a : homeApps()) { edit.add(a); sel.add(a.pkg); }
        for (App a : all) if (!sel.contains(a.pkg)) edit.add(a);
        mode = 1;
        title.setText("Choose and order apps");
        hint.setText("OK: show / hide      Left / Right: move");
        adapter.notifyDataSetChanged();
        list.setSelection(0);
    }

    void leaveApps() {
        StringBuilder sb = new StringBuilder();
        for (App a : edit) if (sel.contains(a.pkg)) { if (sb.length() > 0) sb.append(','); sb.append(a.pkg); }
        sp.edit().putString("order", sb.toString()).apply();
        mode = 0;
        title.setText("Settings");
        hint.setText("OK: change      Back: close");
        render();
        adapter.notifyDataSetChanged();
        list.setSelection(4);
    }

    void onRow(int pos) {
        if (mode == 1) {
            if (pos == 0) { leaveApps(); return; }
            String p = edit.get(pos - 1).pkg;
            if (!sel.remove(p)) sel.add(p);
            adapter.notifyDataSetChanged();
            return;
        }
        switch (pos) {
            case 0: layout = 1 - layout; break;
            case 1: show = (show + 1) % 3; break;
            case 2: bg = (bg + 1) % 7; if (bg == 4 && !photoExists()) bg = 5; break;
            case 3: c24 = !c24; break;
            case 4: enterApps(); return;
            case 5: closeOverlay(); return;
        }
        savePrefs();
        render();
        adapter.notifyDataSetChanged();
    }

    class PanelAdapter extends BaseAdapter {
        public int getCount() { return mode == 0 ? 6 : 1 + edit.size(); }
        public Object getItem(int i) { return null; }
        public long getItemId(int i) { return i; }
        public View getView(int pos, View v, ViewGroup parent) {
            String l, r = null;
            float alpha = 1f;
            if (mode == 0) {
                switch (pos) {
                    case 0: l = "Layout"; r = layout == 0 ? "Dock" : "List"; break;
                    case 1: l = "Show"; r = SHOWN[show]; break;
                    case 2: l = "Background"; r = BGN[bg]; break;
                    case 3: l = "Clock"; r = c24 ? "24-hour" : "12-hour"; break;
                    case 4: l = "Choose and order apps"; r = ">"; break;
                    default: l = "Done";
                }
            } else if (pos == 0) l = "<  Done";
            else {
                App a = edit.get(pos - 1);
                boolean on = sel.contains(a.pkg);
                l = (on ? "\u25CF  " : "\u25CB  ") + a.label;
                alpha = on ? 1f : 0.5f;
            }
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(20), dp(12), dp(20), dp(12));
            row.addView(text(l, 20, Color.WHITE), new LinearLayout.LayoutParams(0, -2, 1f));
            if (r != null) row.addView(text(r, 18, 0xAAFFFFFF));
            row.setAlpha(alpha);
            return row;
        }
    }
}
