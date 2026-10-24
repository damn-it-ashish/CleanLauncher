package com.cleanlauncher;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.*;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Minimal ad-free home screen: clock + sorted app grid. No network, no animations. */
public class MainActivity extends Activity {
    private static final String PREFS = "p", KEY = "hidden";

    static class App { String label, pkg; ComponentName cn; boolean hidden; }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<App> apps = new ArrayList<App>();
    private LruCache<String, Bitmap> cache;
    private GridView grid;
    private BaseAdapter adapter;
    private SharedPreferences prefs;
    private PackageManager pm;
    private boolean showHidden, swallowUp, receiverOn;
    private int iconPx;
    private float dp;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reload(); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        pm = getPackageManager();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        dp = getResources().getDisplayMetrics().density;
        iconPx = (int) (64 * dp);
        cache = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
            @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
        };
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (48 * dp), (int) (24 * dp), (int) (48 * dp), (int) (16 * dp));

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm a");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(36);
        root.addView(clock);

        grid = new GridView(this);
        grid.setNumColumns(6);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing((int) (8 * dp));
        grid.setSelector(new ColorDrawable(0x40FFFFFF));
        grid.setDrawSelectorOnTop(false);
        grid.setVerticalScrollBarEnabled(false);
        grid.setSmoothScrollbarEnabled(false);
        grid.setCacheColorHint(Color.TRANSPARENT);
        grid.setPadding(0, (int) (16 * dp), 0, 0);
        grid.setClipToPadding(false);
        adapter = new AppAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) { launch(apps.get(pos)); }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) { toggleHidden(apps.get(pos)); return true; }
        });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView hint = new TextView(this);
        hint.setText("Hold OK: hide / unhide      Menu: show hidden apps");
        hint.setTextColor(0x66FFFFFF);
        hint.setTextSize(12);
        root.addView(hint);

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

    @Override protected void onNewIntent(Intent i) {   // Home pressed while open
        super.onNewIntent(i);
        grid.setSelection(0);
    }

    @Override public void onBackPressed() { /* stay on home */ }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) {
            showHidden = !showHidden;
            Toast.makeText(this, showHidden ? "Showing hidden apps" : "Hiding hidden apps", Toast.LENGTH_SHORT).show();
            reload();
            return true;
        }
        if (k == KeyEvent.KEYCODE_MENU) return true;
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 1) {
                int pos = grid.getSelectedItemPosition();
                if (pos >= 0 && pos < apps.size()) toggleHidden(apps.get(pos));
                swallowUp = true;
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_UP && swallowUp) { swallowUp = false; return true; }
        }
        return super.dispatchKeyEvent(e);
    }

    private void launch(App a) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(a.cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleHidden(App a) {
        Set<String> h = new HashSet<String>(prefs.getStringSet(KEY, new HashSet<String>()));
        if (!h.remove(a.pkg)) h.add(a.pkg);
        prefs.edit().putStringSet(KEY, h).apply();
        reload();
    }

    private void reload() {
        io.execute(new Runnable() { public void run() {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
            Set<String> hidden = prefs.getStringSet(KEY, new HashSet<String>());
            final ArrayList<App> out = new ArrayList<App>();
            for (ResolveInfo ri : list) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                boolean h = hidden.contains(pkg);
                if (h && !showHidden) continue;
                App a = new App();
                a.pkg = pkg; a.hidden = h;
                a.cn = new ComponentName(pkg, ri.activityInfo.name);
                a.label = String.valueOf(ri.loadLabel(pm));
                out.add(a);
            }
            Collections.sort(out, new Comparator<App>() {
                public int compare(App x, App y) { return x.label.compareToIgnoreCase(y.label); }
            });
            ui.post(new Runnable() { public void run() {
                apps.clear(); apps.addAll(out); adapter.notifyDataSetChanged();
            }});
        }});
    }

    private Bitmap toBitmap(Drawable d) {
        Bitmap bm = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        d.setBounds(0, 0, iconPx, iconPx);
        d.draw(c);
        return bm;
    }

    private class AppAdapter extends BaseAdapter {
        public int getCount() { return apps.size(); }
        public Object getItem(int i) { return apps.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int pos, View v, ViewGroup parent) {
            LinearLayout row; ImageView img; TextView tv;
            if (v == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setGravity(Gravity.CENTER_HORIZONTAL);
                int p = (int) (10 * dp);
                row.setPadding(p, p, p, p);
                img = new ImageView(MainActivity.this);
                row.addView(img, new LinearLayout.LayoutParams(iconPx, iconPx));
                tv = new TextView(MainActivity.this);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(14);
                tv.setSingleLine(true);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tv.setGravity(Gravity.CENTER);
                tv.setPadding(0, (int) (6 * dp), 0, 0);
                row.addView(tv, new LinearLayout.LayoutParams(-1, -2));
                row.setTag(new View[]{img, tv});
            } else row = (LinearLayout) v;

            View[] h = (View[]) row.getTag();
            img = (ImageView) h[0]; tv = (TextView) h[1];
            final App a = apps.get(pos);
            tv.setText(a.label);
            row.setAlpha(a.hidden ? 0.4f : 1f);
            img.setTag(a.pkg);
            Bitmap bm = cache.get(a.pkg);
            if (bm != null) img.setImageBitmap(bm);
            else {
                img.setImageDrawable(null);
                final ImageView target = img;
                io.execute(new Runnable() { public void run() {
                    try {
                        final Bitmap b = toBitmap(pm.getActivityIcon(a.cn));
                        cache.put(a.pkg, b);
                        ui.post(new Runnable() { public void run() {
                            if (a.pkg.equals(target.getTag())) target.setImageBitmap(b);
                        }});
                    } catch (Exception ignored) {}
                }});
            }
            return row;
        }
    }
}
