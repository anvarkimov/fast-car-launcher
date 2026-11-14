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
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.Toast;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
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
 * Енгил лаунчер, Mi Box'даги "Уй экрани" услубида: шиша (glass) кўринишдаги
 * юмалоқ карточкалар, катта соат, об-ҳаво, бош қатор ва иловалар.
 * Ҳақиқий blur ишлатилмайди (магнитолада қотирмаслик учун) — фақат
 * шаффоф юмалоқ карточкалар ва юпқа чизиқ.
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
    private static final String KEY_GLASS = "glass";
    private static final long WEATHER_EVERY_MS = 30L * 60L * 1000L;

    private static final String[] DEFAULT_FAVS = {
            "com.google.android.apps.maps", "ru.yandex.yandexnavi", "ru.yandex.yandexmaps",
            "com.waze", "com.google.android.youtube", "com.android.vending",
            "com.android.settings"
    };

    private final List<App> apps = new ArrayList<>();
    private final Map<String, Bitmap> iconCache = new HashMap<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private PackageManager pm;
    private LinearLayout favRow;
    private GridView grid;
    private AppAdapter adapter;
    private TextView weather;
    private float density;
    private int glassVer = 0;
    private Drawable.ConstantState glassCS;
    private long lastWeather = 0;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            loadApps();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pm = getPackageManager();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        rebuildGlass();
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
    protected void onStart() {
        super.onStart();
        if (SystemClock.elapsedRealtime() - lastWeather > WEATHER_EVERY_MS || lastWeather == 0) {
            loadWeather();
        }
        if (System.currentTimeMillis() - prefs.getLong("upd_ts", 0) > 6L * 3600L * 1000L) {
            checkUpdate(false);
        }
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        io.shutdownNow();
        net.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (grid != null) grid.setSelection(0);
    }

    @Override
    public void onBackPressed() {
        // бош экранда "орқага" ҳеч нима қилмайди
    }

    // ---------------------------------------------------------------- шиша услуби

    private int dp(int v) {
        return Math.round(v * density);
    }

    private int glass() {
        return prefs.getInt(KEY_GLASS, 50);
    }

    private GradientDrawable roundRect(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(18));
        g.setStroke(dp(1), stroke);
        return g;
    }

    private void rebuildGlass() {
        float s = glass() / 100f;
        int fill = Color.argb((int) (255 * (0.05 + 0.20 * s)), 255, 255, 255);
        int fillP = Color.argb((int) (255 * (0.14 + 0.25 * s)), 255, 255, 255);
        int stroke = Color.argb((int) (255 * (0.08 + 0.22 * s)), 255, 255, 255);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, roundRect(fillP, stroke));
        sl.addState(new int[]{android.R.attr.state_focused}, roundRect(fillP, stroke));
        sl.addState(new int[]{}, roundRect(fill, stroke));
        glassCS = sl.getConstantState();
        glassVer++;
    }

    private void applyGlass() {
        rebuildGlass();
        adapter.notifyDataSetChanged();
        rebuildFavs();
    }

    // ---------------------------------------------------------------- UI

    private TextView label(String text, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0C1124, 0xFF1A1140, 0xFF0A1530});
        root.setBackground(bg);
        root.setPadding(dp(28), dp(12), dp(28), dp(8));

        // Юқори қатор: соат + сана | об-ҳаво + созламалар
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(46);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        left.addView(clock);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("d MMMM, EEEE");
        date.setFormat12Hour("d MMMM, EEEE");
        date.setTextColor(0xFF9AA3C0);
        date.setTextSize(16);
        left.addView(date);
        top.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        weather = label("", 20, Color.WHITE);
        weather.setGravity(Gravity.CENTER);
        weather.setPadding(dp(18), dp(10), dp(18), dp(10));
        weather.setBackground(roundRect(0x22FFFFFF, 0x33FFFFFF));
        weather.setVisibility(View.GONE);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wp.rightMargin = dp(12);
        top.addView(weather, wp);

        TextView gear = label("⚙", 26, Color.WHITE);
        gear.setGravity(Gravity.CENTER);
        gear.setBackground(roundRect(0x22FFFFFF, 0x33FFFFFF));
        gear.setClickable(true);
        gear.setOnClickListener(v -> showSettings());
        top.addView(gear, new LinearLayout.LayoutParams(dp(52), dp(52)));

        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Бош қатор
        TextView t1 = label("Севимлилар", 13, 0xFF9AA3C0);
        t1.setPadding(dp(6), dp(10), 0, 0);
        root.addView(t1);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        favRow = new LinearLayout(this);
        favRow.setOrientation(LinearLayout.HORIZONTAL);
        favRow.setPadding(0, dp(2), 0, dp(4));
        hsv.addView(favRow);
        root.addView(hsv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Барча иловалар
        TextView t2 = label("Иловалар", 13, 0xFF9AA3C0);
        t2.setPadding(dp(6), dp(4), 0, dp(2));
        root.addView(t2);

        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(120));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setVerticalScrollBarEnabled(false);
        grid.setSmoothScrollbarEnabled(false);
        adapter = new AppAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> launch(apps.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            showMenu(apps.get(pos));
            return true;
        });
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private class Holder {
        final LinearLayout inner;
        final ImageView icon;
        final TextView label;
        int ver = -1;

        Holder(LinearLayout inner, int iconDp, int textSp) {
            this.inner = inner;
            icon = new ImageView(MainActivity.this);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            inner.addView(icon, new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));
            label = new TextView(MainActivity.this);
            label.setTextColor(Color.WHITE);
            label.setTextSize(textSp);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(5), 0, 0);
            inner.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void bind(App a) {
            if (ver != glassVer) {
                inner.setBackground(glassCS.newDrawable());
                ver = glassVer;
            }
            icon.setImageBitmap(a.icon);
            label.setText(a.label);
        }
    }

    private FrameLayout makeTile(App app, int iconDp, int textSp) {
        FrameLayout outer = new FrameLayout(this);
        outer.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER_HORIZONTAL);
        inner.setPadding(dp(6), dp(10), dp(6), dp(8));
        inner.setDuplicateParentStateEnabled(true);
        outer.addView(inner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Holder h = new Holder(inner, iconDp, textSp);
        outer.setTag(h);
        h.bind(app);
        return outer;
    }

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int i) { return apps.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            App a = apps.get(pos);
            if (convert == null) {
                convert = makeTile(a, 52, 13);
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

    private void loadWeather() {
        lastWeather = SystemClock.elapsedRealtime();
        net.execute(() -> {
            try {
                URL u = new URL("https://api.open-meteo.com/v1/forecast?latitude=41.55&longitude=60.63&current=temperature_2m");
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                double t = new JSONObject(sb.toString()).getJSONObject("current").getDouble("temperature_2m");
                final String txt = Math.round(t) + "°  Урганч";
                ui.post(() -> {
                    weather.setText(txt);
                    weather.setVisibility(View.VISIBLE);
                });
            } catch (Exception e) {
                lastWeather = 0; // кейинги safar қайта уриниш
            }
        });
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
            FrameLayout t = makeTile(a, 68, 14);
            t.setLayoutParams(new LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT));
            t.setClickable(true);
            t.setOnClickListener(v -> launch(a));
            t.setOnLongClickListener(v -> { showMenu(a); return true; });
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
            loadApps();
        }
    }

    private void showMenu(final App a) {
        boolean fav = getFavs().contains(a.pkg);
        String[] items = {
                fav ? "Севимлилардан олиб ташлаш" : "Севимлиларга қўшиш",
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

    // ---------------------------------------------------------------- ўз-ўзидан янгилаш

    private void checkUpdate(final boolean manual) {
        prefs.edit().putLong("upd_ts", System.currentTimeMillis()).apply();
        net.execute(() -> {
            try {
                final Updater.Info info = Updater.fetchLatest();
                if (info != null && info.code > Updater.currentCode(this)) {
                    ui.post(() -> askUpdate(info));
                } else if (manual) {
                    ui.post(() -> Toast.makeText(this, "Охирги версия ўрнатилган", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                if (manual) {
                    ui.post(() -> Toast.makeText(this, "Текшириб бўлмади (интернет?)", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    private void askUpdate(final Updater.Info info) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Янги версия бор")
                .setMessage("Лаунчер " + info.tag + " чиқди. Янгилайсизми?")
                .setPositiveButton("Янгилаш", (d, w) -> startUpdate(info))
                .setNegativeButton("Кейин", null)
                .show();
    }

    private void startUpdate(final Updater.Info info) {
        if (!pm.canRequestPackageInstalls()) {
            Toast.makeText(this, "Рухсат беринг, кейин қайта “Янгилаш”ни босинг", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        Toast.makeText(this, "Юкланяпти…", Toast.LENGTH_LONG).show();
        net.execute(() -> {
            try {
                Updater.downloadAndInstall(getApplicationContext(), info.url);
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Юклаб бўлмади: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(8));
        final TextView lbl = label("Шиша кучи: " + glass() + "%", 16, Color.WHITE);
        SeekBar sb = new SeekBar(this);
        sb.setMax(10);
        sb.setProgress(glass() / 10);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                prefs.edit().putInt(KEY_GLASS, p * 10).apply();
                lbl.setText("Шиша кучи: " + (p * 10) + "%");
                applyGlass();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        box.addView(lbl);
        box.addView(sb);
        final TextView ver = label("Версия: " + Updater.currentCode(this), 14, 0xFF9AA3C0);
        ver.setPadding(0, dp(18), 0, dp(6));
        box.addView(ver);
        Button upd = new Button(this);
        upd.setText("Янгиланишни текшириш");
        upd.setOnClickListener(v -> checkUpdate(true));
        box.addView(upd);
        new AlertDialog.Builder(this)
                .setTitle("Созламалар")
                .setView(box)
                .setPositiveButton("Тайёр", null)
                .show();
    }
}
