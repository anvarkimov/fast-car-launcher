package com.fastcar.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Жуда енгил лаунчер: фон расми йўқ, анимация йўқ, виджет йўқ.
 * Юқорида соат, ўртада бош қатор (сevimli иловалар), пастда барча иловалар.
 * Иловага узоқ босилса: бош қаторга қўшиш/олиб ташлаш, маълумот, ўчириш.
 */
public class MainActivity extends Activity {

    static class App {
        String label;
        String pkg;
        Intent intent;
        Bitmap icon;
    }

    private static final String PREFS = "fast_launcher";
    private static final String KEY_FAVS = "favs";

    private static final String[] DEFAULT_FAVS = {
            "com.google.android.apps.maps", "ru.yandex.yandexnavi", "ru.yandex.yandexmaps",
            "com.waze", "com.google.android.youtube", "com.android.vending",
            "com.android.settings"
    };

    private final List<App> apps = new ArrayList<>();
    private final Map<String, Bitmap> iconCache = new HashMap<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private PackageManager pm;
    private LinearLayout favRow;
    private GridView grid;
    private AppAdapter adapter;
    private boolean dirty = true;
    private float density;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            dirty = true;
            loadApps();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pm = getPackageManager();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        buildUi();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);

        loadApps();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // "Уй" тугмаси босилганда рўйхат бошига қайтади
        if (grid != null) grid.setSelection(0);
    }

    @Override
    public void onBackPressed() {
        // бош экранда "орқага" ҳеч нима қилмайди
    }

    // ---------------------------------------------------------------- UI

    private int dp(int v) {
        return Math.round(v * density);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B0B12"));
        root.setPadding(dp(24), dp(12), dp(24), dp(8));

        // Юқори қатор: соат
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(40);
        top.addView(clock);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("d MMMM, EEEE");
        date.setFormat12Hour("d MMMM, EEEE");
        date.setTextColor(Color.parseColor("#8A8FA3"));
        date.setTextSize(18);
        date.setPadding(dp(20), 0, 0, 0);
        top.addView(date);

        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Бош қатор (севимлилар)
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        favRow = new LinearLayout(this);
        favRow.setOrientation(LinearLayout.HORIZONTAL);
        favRow.setPadding(0, dp(8), 0, dp(8));
        hsv.addView(favRow);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(hsv, hp);

        // Барча иловалар
        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(120));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(dp(6));
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.parseColor("#22FFFFFF")));
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setVerticalScrollBarEnabled(false);
        grid.setFastScrollEnabled(false);
        grid.setSmoothScrollbarEnabled(false);
        adapter = new AppAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> launch(apps.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            showMenu(apps.get(pos));
            return true;
        });
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(grid, gp);

        setContentView(root);
    }

    private View makeTile(final App app, int iconDp, int widthDp, int textSp) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(dp(6), dp(8), dp(6), dp(8));
        t.setTag(new Holder(t, iconDp, textSp));
        ((Holder) t.getTag()).bind(app);
        if (widthDp > 0) {
            t.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return t;
    }

    private class Holder {
        final ImageView icon;
        final TextView label;

        Holder(LinearLayout parent, int iconDp, int textSp) {
            icon = new ImageView(MainActivity.this);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            parent.addView(icon, new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));
            label = new TextView(MainActivity.this);
            label.setTextColor(Color.WHITE);
            label.setTextSize(textSp);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(4), 0, 0);
            parent.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void bind(App a) {
            icon.setImageBitmap(a.icon);
            label.setText(a.label);
        }
    }

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int i) { return apps.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            App a = apps.get(pos);
            if (convert == null) {
                convert = makeTile(a, 56, 0, 13);
                convert.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                ((Holder) convert.getTag()).bind(a);
            }
            return convert;
        }
    }

    // ---------------------------------------------------------------- маълумот

    private void loadApps() {
        io.execute(() -> {
            Intent q = new Intent(Intent.ACTION_MAIN);
            q.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
            final List<App> out = new ArrayList<>();
            for (ResolveInfo ri : list) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                App a = new App();
                a.pkg = pkg;
                a.label = String.valueOf(ri.loadLabel(pm));
                a.intent = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(pkg, ri.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                Bitmap bmp;
                synchronized (iconCache) { bmp = iconCache.get(pkg); }
                if (bmp == null) {
                    try {
                        bmp = toBitmap(ri.loadIcon(pm));
                        synchronized (iconCache) { iconCache.put(pkg, bmp); }
                    } catch (Throwable ignored) { }
                }
                a.icon = bmp;
                out.add(a);
            }
            Collections.sort(out, new Comparator<App>() {
                @Override
                public int compare(App x, App y) {
                    return x.label.toLowerCase(Locale.ROOT).compareTo(y.label.toLowerCase(Locale.ROOT));
                }
            });
            ui.post(() -> {
                apps.clear();
                apps.addAll(out);
                adapter.notifyDataSetChanged();
                ensureDefaultFavs();
                rebuildFavs();
                dirty = false;
            });
        });
    }

    private Bitmap toBitmap(Drawable d) {
        int s = dp(64);
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, s, s);
        d.draw(c);
        return b;
    }

    // ---------------------------------------------------------------- севимлилар

    private List<String> getFavs() {
        String s = prefs.getString(KEY_FAVS, null);
        List<String> r = new ArrayList<>();
        if (s != null && s.length() > 0) {
            for (String p : s.split(",")) if (p.length() > 0) r.add(p);
        }
        return r;
    }

    private void saveFavs(List<String> l) {
        prefs.edit().putString(KEY_FAVS, TextUtils.join(",", l)).apply();
    }

    private void ensureDefaultFavs() {
        if (prefs.contains(KEY_FAVS)) return;
        List<String> r = new ArrayList<>();
        for (String p : DEFAULT_FAVS) {
            if (findApp(p) != null) r.add(p);
        }
        saveFavs(r);
    }

    private App findApp(String pkg) {
        for (App a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    private void rebuildFavs() {
        favRow.removeAllViews();
        for (String p : getFavs()) {
            final App a = findApp(p);
            if (a == null) continue;
            View t = makeTile(a, 72, 110, 14);
            t.setOnClickListener(v -> launch(a));
            t.setOnLongClickListener(v -> { showMenu(a); return true; });
            t.setFocusable(true);
            favRow.addView(t);
        }
    }

    private void toggleFav(App a) {
        List<String> l = getFavs();
        if (l.contains(a.pkg)) l.remove(a.pkg); else l.add(a.pkg);
        saveFavs(l);
        rebuildFavs();
    }

    // ---------------------------------------------------------------- ҳаракатлар

    private void launch(App a) {
        try {
            startActivity(a.intent);
        } catch (Exception e) {
            dirty = true;
            loadApps();
        }
    }

    private void showMenu(final App a) {
        boolean fav = getFavs().contains(a.pkg);
        String[] items = {
                fav ? "Бош қатордан олиб ташлаш" : "Бош қаторга қўшиш",
                "Илова ҳақида",
                "Ўчириш"
        };
        new AlertDialog.Builder(this)
                .setTitle(a.label)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        toggleFav(a);
                    } else if (which == 1) {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + a.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } else {
                        startActivity(new Intent(Intent.ACTION_DELETE,
                                Uri.parse("package:" + a.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    }
                })
                .show();
    }
}
