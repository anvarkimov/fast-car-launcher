package com.fastcar.home;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

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
 * Лёгкий лаунчер для автомагнитолы, стиль «тёмное стекло».
 * Часы и погода, спидометр (GPS), плеер (Яндекс Музыка), радио,
 * избранное и все приложения. Без blur и анимаций.
 */
public class MainActivity extends Activity {

    static class App {
        String label;
        String pkg;
        Intent intent;
        Bitmap icon;
    }

    private static final String PREFS = "fast_launcher";
    private static final String KEY_FAVS = "favs5";
    private static final String KEY_GLASS = "glass2";
    private static final String KEY_MAPKEY = "mapkey";
    private static final String DEFAULT_MAPKEY = "58839e27-b97f-4e36-a620-a5f3747fcb18";
    private static final String KEY_LIVEMAP = "livemap";
    private static final String PLUS_PKG = "__plus__";
    private static final long WEATHER_EVERY_MS = 30L * 60L * 1000L;
    private static final int REQ_LOC = 11;
    private static final String MUSIC_PKG = "ru.yandex.music";
    private static final int MUTED = 0xFF9AA3C0;

    private static final String[] DEFAULT_FAVS = {
            "ru.yandex.yandexnavi", "ru.yandex.music", "com.google.android.apps.maps",
            "com.google.android.youtube", "com.android.vending", "com.android.settings"
    };

    private final List<App> apps = new ArrayList<>();
    private final Map<String, Bitmap> iconCache = new HashMap<>();
    private final List<View> cards = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private PackageManager pm;
    private GridView grid;
    private AppAdapter adapter;
    private LinearLayout content, appsPanel;
    private AppAdapter appsAdapter;
    private GridView appsGrid;
    private boolean started, showingApps;
    private final List<App> favItems = new ArrayList<>();
    private App plusApp;
    private TextView weather;
    private GaugeView gauge;
    private float density;
    private int glassVer = 0;
    private Drawable.ConstantState glassCS;
    private long lastWeather = 0;

    // музыка
    private TextView tvCaption, tvTitle, tvArtist, coverNote;
    private ImageView coverImg;
    private BarView bar;
    private IconView btnPlay;
    private MediaSessionManager msm;
    private ComponentName listener;
    private MediaController mc;
    private boolean sessionsRegistered = false;
    private Bitmap lastArt;

    // радио
    private TextView tvRadioTitle, tvRadioSub;
    private MediaController radioCtl;
    private String radioPkg;

    // GPS
    private LocationManager lm;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            loadApps();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> list) {
                    pickController(list);
                }
            };

    private final MediaController.Callback mcCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { updateMusicUi(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { updateMusicUi(); }
        @Override public void onSessionDestroyed() { refreshMedia(); }
    };

    private final MediaController.Callback radioCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { updateRadioUi(); }
        @Override public void onPlaybackStateChanged(PlaybackState st) { updateRadioUi(); }
        @Override public void onSessionDestroyed() { refreshMedia(); }
    };

    private LiveMap liveMap;

    private final LocationListener locListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location l) {
            if (liveMap != null) liveMap.moveTo(l.getLatitude(), l.getLongitude(), l.hasBearing() ? l.getBearing() : 0f);
            if (l.hasSpeed()) {
                int kmh = Math.round(l.getSpeed() * 3.6f);
                gauge.setSpeed(kmh < 2 ? 0 : kmh);
            }
        }
        @Override public void onStatusChanged(String p, int s, Bundle e) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { gauge.setSpeed(-1); }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            ui.postDelayed(this, 1000);
        }
    };

    // ---------------------------------------------------------------- жизненный цикл

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pm = getPackageManager();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        listener = new ComponentName(this, MediaListener.class);
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
        started = true;
        if (lastWeather == 0 || SystemClock.elapsedRealtime() - lastWeather > WEATHER_EVERY_MS) {
            loadWeather();
        }
        if (System.currentTimeMillis() - prefs.getLong("upd_ts", 0) > 6L * 3600L * 1000L) {
            checkUpdate(false);
        }
        startLocation();
        refreshMedia();
        if (liveMap != null) liveMap.onStart();
        ui.post(tick);
    }

    @Override
    protected void onStop() {
        started = false;
        if (liveMap != null) liveMap.onStop();
        showApps(false);
        ui.removeCallbacks(tick);
        try { lm.removeUpdates(locListener); } catch (Exception ignored) { }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        try { if (sessionsRegistered) msm.removeOnActiveSessionsChangedListener(sessionsListener); } catch (Exception ignored) { }
        if (mc != null) { try { mc.unregisterCallback(mcCallback); } catch (Exception ignored) { } }
        if (radioCtl != null) { try { radioCtl.unregisterCallback(radioCallback); } catch (Exception ignored) { } }
        io.shutdownNow();
        net.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // «Домой» на самом лаунчере: дашборд ⇄ все приложения.
        // Если лаунчер вернулся из другого приложения — всегда дашборд.
        showApps(started && !showingApps);
        if (grid != null) grid.setSelection(0);
    }

    @Override
    public void onBackPressed() {
        if (showingApps) showApps(false);
    }

    // ---------------------------------------------------------------- стекло

    private int dp(int v) {
        return Math.round(v * density);
    }

    private int glass() {
        return prefs.getInt(KEY_GLASS, 30);
    }

    private GradientDrawable roundRect(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(22));
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
        for (View v : cards) v.setBackground(glassCS.newDrawable());
        adapter.notifyDataSetChanged();
        appsAdapter.notifyDataSetChanged();
        rebuildFavs();
    }

    // ---------------------------------------------------------------- интерфейс

    private TextView label(String text, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    private LinearLayout card(int orientation) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(orientation);
        l.setBackground(glassCS.newDrawable());
        cards.add(l);
        return l;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF070A18, 0xFF110C2C, 0xFF06101F}));
        root.setPadding(dp(20), dp(8), dp(20), dp(8));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(content, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ===== левая колонка: часы + спидометр =====
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        content.addView(left, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.31f));

        LinearLayout clockCard = card(LinearLayout.VERTICAL);
        clockCard.setPadding(dp(16), dp(10), dp(16), dp(8));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("d MMMM, EEEE");
        date.setFormat12Hour("d MMMM, EEEE");
        date.setTextColor(MUTED);
        date.setTextSize(16);
        date.setSingleLine(true);
        date.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(date, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        weather = label("", 14, Color.WHITE);
        weather.setPadding(dp(10), dp(4), dp(10), dp(4));
        weather.setBackground(roundRect(0x22FFFFFF, 0x33FFFFFF));
        weather.setVisibility(View.GONE);
        top.addView(weather, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));
        TextView gear = label("⚙", 20, Color.WHITE);
        gear.setGravity(Gravity.CENTER);
        gear.setClickable(true);
        gear.setOnClickListener(v -> showSettings());
        LinearLayout.LayoutParams gp = lp(dp(36), dp(36), 0);
        gp.leftMargin = dp(8);
        top.addView(gear, gp);
        clockCard.addView(top, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(80);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        clockCard.addView(clock, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));
        LinearLayout.LayoutParams cp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0);
        cp.bottomMargin = dp(10);
        left.addView(clockCard, cp);

        LinearLayout speedCard = card(LinearLayout.VERTICAL);
        speedCard.setPadding(dp(10), dp(10), dp(10), dp(10));
        speedCard.setClickable(true);
        speedCard.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    return true;
                case MotionEvent.ACTION_UP:
                    v.setPressed(false);
                    openMaps();   // и касание, и свайп
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    return true;
                default:
                    return true;
            }
        });
        gauge = new GaugeView(this);
        speedCard.addView(gauge, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        left.addView(speedCard, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ===== средняя колонка: навигация =====
        buildMapCard(content);

        // ===== правая колонка: плеер/радио (свайп) + избранное =====
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rp = lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.34f);
        rp.leftMargin = dp(10);
        content.addView(right, rp);

        SwipePager pager = new SwipePager(this);
        right.addView(pager, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.85f));
        buildMusicCard(pager);
        buildRadioCard(pager);

        TextView t1 = label("Избранное", 12, MUTED);
        t1.setPadding(dp(4), dp(8), 0, dp(2));
        right.addView(t1);
        plusApp = new App();
        plusApp.pkg = PLUS_PKG;
        plusApp.label = "Добавить";
        plusApp.icon = plusBitmap();
        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setVerticalSpacing(dp(6));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setVerticalScrollBarEnabled(false);
        grid.setSmoothScrollbarEnabled(false);
        adapter = new AppAdapter(favItems);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            App a = favItems.get(pos);
            if (PLUS_PKG.equals(a.pkg)) showPicker(); else launch(a);
        });
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            App a = favItems.get(pos);
            if (!PLUS_PKG.equals(a.pkg)) showMenu(a);
            return true;
        });
        right.addView(grid, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.5f));

        appsPanel = new LinearLayout(this);
        appsPanel.setOrientation(LinearLayout.VERTICAL);
        appsPanel.setVisibility(View.GONE);
        TextView ah = label("Все приложения", 14, MUTED);
        ah.setPadding(dp(8), dp(4), 0, dp(4));
        appsPanel.addView(ah);
        GridView ag = new GridView(this);
        ag.setNumColumns(GridView.AUTO_FIT);
        ag.setColumnWidth(dp(112));
        ag.setVerticalSpacing(dp(6));
        ag.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        ag.setSelector(new ColorDrawable(Color.TRANSPARENT));
        ag.setOverScrollMode(View.OVER_SCROLL_NEVER);
        ag.setVerticalScrollBarEnabled(false);
        ag.setSmoothScrollbarEnabled(false);
        appsAdapter = new AppAdapter(apps);
        ag.setAdapter(appsAdapter);
        ag.setOnItemClickListener((pp, v, pos, id) -> launch(apps.get(pos)));
        ag.setOnItemLongClickListener((pp, v, pos, id) -> { showMenu(apps.get(pos)); return true; });
        appsPanel.addView(ag, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        appsGrid = ag;
        root.addView(appsPanel, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void buildMusicCard(ViewGroup widgets) {
        LinearLayout music = card(LinearLayout.HORIZONTAL);
        music.setGravity(Gravity.CENTER_VERTICAL);
        music.setPadding(dp(12), dp(10), dp(12), dp(10));
        music.setOnClickListener(v -> onMusicCardClick());
        widgets.addView(music, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout cover = new FrameLayout(this);
        GradientDrawable cg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF7C3AED, 0xFFEC4899});
        cg.setCornerRadius(dp(14));
        cover.setBackground(cg);
        cover.setClipToOutline(true);
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14));
            }
        });
        coverNote = label("♪", 30, Color.WHITE);
        coverNote.setGravity(Gravity.CENTER);
        cover.addView(coverNote, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        coverImg = new ImageView(this);
        coverImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.addView(coverImg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        music.addView(cover, new LinearLayout.LayoutParams(dp(76), dp(76)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ip = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ip.leftMargin = dp(12);
        music.addView(info, ip);

        tvCaption = label("Яндекс Музыка", 11, MUTED);
        tvCaption.setSingleLine(true);
        info.addView(tvCaption);
        tvTitle = label("Нажмите, чтобы открыть", 16, Color.WHITE);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(tvTitle);
        tvArtist = label("", 12, MUTED);
        tvArtist.setSingleLine(true);
        tvArtist.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(tvArtist);

        bar = new BarView(this);
        LinearLayout.LayoutParams bp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(4), 0);
        bp.topMargin = dp(6);
        info.addView(bar, bp);

        LinearLayout ctl = new LinearLayout(this);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setGravity(Gravity.CENTER_VERTICAL);
        IconView prev = new IconView(this, IconView.PREV);
        prev.setOnClickListener(v -> { if (mc != null) mc.getTransportControls().skipToPrevious(); else onMusicCardClick(); });
        btnPlay = new IconView(this, IconView.PLAY);
        btnPlay.setOnClickListener(v -> togglePlay());
        IconView next = new IconView(this, IconView.NEXT);
        next.setOnClickListener(v -> { if (mc != null) mc.getTransportControls().skipToNext(); else onMusicCardClick(); });
        ctl.addView(prev, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(42), dp(42));
        pp.leftMargin = dp(10);
        pp.rightMargin = dp(10);
        ctl.addView(btnPlay, pp);
        ctl.addView(next, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout.LayoutParams cl = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0);
        cl.topMargin = dp(4);
        info.addView(ctl, cl);
    }

    private void buildRadioCard(ViewGroup widgets) {
        LinearLayout radio = card(LinearLayout.VERTICAL);
        radio.setGravity(Gravity.CENTER_VERTICAL);
        radio.setPadding(dp(16), dp(10), dp(16), dp(10));
        radio.setClickable(true);
        radio.setOnClickListener(v -> openRadio());
        widgets.addView(radio, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView fm = label("FM", 13, 0xFF67E8F9);
        fm.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        fm.setPadding(dp(10), dp(3), dp(10), dp(3));
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(0x2E22D3EE);
        chip.setCornerRadius(dp(20));
        fm.setBackground(chip);
        radio.addView(fm, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));
        tvRadioTitle = label("Радио", 24, Color.WHITE);
        tvRadioTitle.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        tvRadioTitle.setSingleLine(true);
        tvRadioTitle.setEllipsize(TextUtils.TruncateAt.END);
        tvRadioTitle.setPadding(0, dp(4), 0, 0);
        radio.addView(tvRadioTitle);
        tvRadioSub = label("Нажмите, чтобы открыть", 12, MUTED);
        tvRadioSub.setSingleLine(true);
        tvRadioSub.setEllipsize(TextUtils.TruncateAt.END);
        radio.addView(tvRadioSub);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        IconView prev = new IconView(this, IconView.PREV);
        prev.setOnClickListener(v -> radioStep(false));
        IconView next = new IconView(this, IconView.NEXT);
        next.setOnClickListener(v -> radioStep(true));
        row.addView(prev, new LinearLayout.LayoutParams(dp(48), dp(40)));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(dp(48), dp(40));
        np.leftMargin = dp(18);
        row.addView(next, np);
        LinearLayout.LayoutParams rl = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0);
        rl.topMargin = dp(6);
        radio.addView(row, rl);
    }

    // ---------------------------------------------------------------- плитки

    private class Holder {
        final LinearLayout inner;
        final ImageView icon;
        final TextView label;
        int ver = -1;

        Holder(LinearLayout inner, int iconDp, int textSp) {
            this.inner = inner;
            icon = new ImageView(MainActivity.this);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setClipToOutline(true);
            icon.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), v.getWidth() * 0.22f);
                }
            });
            inner.addView(icon, new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));
            label = new TextView(MainActivity.this);
            label.setTextColor(Color.WHITE);
            label.setTextSize(textSp);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(3), 0, 0);
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
        outer.setPadding(dp(3), dp(3), dp(3), dp(3));
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER_HORIZONTAL);
        inner.setPadding(dp(4), dp(10), dp(4), dp(8));
        inner.setDuplicateParentStateEnabled(true);
        outer.addView(inner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Holder h = new Holder(inner, iconDp, textSp);
        outer.setTag(h);
        h.bind(app);
        return outer;
    }

    private class AppAdapter extends BaseAdapter {
        private final List<App> data;

        AppAdapter(List<App> data) { this.data = data; }

        @Override public int getCount() { return data.size(); }
        @Override public Object getItem(int i) { return data.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            App a = data.get(pos);
            if (convert == null) {
                convert = makeTile(a, 56, 13);
                convert.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                ((Holder) convert.getTag()).bind(a);
            }
            return convert;
        }
    }

    // ---------------------------------------------------------------- данные

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
                radioPkg = findRadioPkg();
                appsAdapter.notifyDataSetChanged();
                ensureDefaultFavs();
                rebuildFavs();
                refreshMedia();
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

    private Bitmap plusBitmap() {
        int sz = dp(64);
        Bitmap b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x33FFFFFF);
        c.drawRoundRect(new RectF(0, 0, sz, sz), sz * 0.22f, sz * 0.22f, p);
        p.setColor(0xFFFFFFFF);
        float t = sz * 0.06f, m = sz * 0.5f, l = sz * 0.28f;
        c.drawRoundRect(new RectF(m - l, m - t / 2, m + l, m + t / 2), t / 2, t / 2, p);
        c.drawRoundRect(new RectF(m - t / 2, m - l, m + t / 2, m + l), t / 2, t / 2, p);
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
                final String txt = Math.round(t) + "°  Ургенч";
                ui.post(() -> {
                    weather.setText(txt);
                    weather.setVisibility(View.VISIBLE);
                });
            } catch (Exception e) {
                lastWeather = 0;
            }
        });
    }

    // ---------------------------------------------------------------- GPS-спидометр

    private void startLocation() {
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOC);
                return;
            }
            lm.removeUpdates(locListener);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locListener);
        } catch (Exception e) {
            gauge.setSpeed(-1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_LOC) startLocation();
    }

    // ---------------------------------------------------------------- плеер

    private boolean hasListenerAccess() {
        String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(getPackageName());
    }

    private void refreshMedia() {
        if (!hasListenerAccess()) {
            setController(null);
            showMusicText("Музыка", "Разрешите доступ к уведомлениям", "Нажмите здесь");
            return;
        }
        try {
            if (!sessionsRegistered) {
                msm.addOnActiveSessionsChangedListener(sessionsListener, listener);
                sessionsRegistered = true;
            }
            pickController(msm.getActiveSessions(listener));
        } catch (SecurityException e) {
            setController(null);
            showMusicText("Музыка", "Разрешите доступ к уведомлениям", "Нажмите здесь");
        }
    }

    private void pickController(List<MediaController> list) {
        MediaController best = null, radio = null;
        if (list != null) {
            List<MediaController> others = new ArrayList<>();
            for (MediaController c : list) {
                if (radioPkg != null && radioPkg.equals(c.getPackageName())) radio = c; else others.add(c);
            }
            for (MediaController c : others) {
                if (MUSIC_PKG.equals(c.getPackageName())) { best = c; break; }
            }
            if (best == null) {
                for (MediaController c : others) {
                    PlaybackState st = c.getPlaybackState();
                    if (st != null && st.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
                }
            }
            if (best == null && !others.isEmpty()) best = others.get(0);
        }
        setRadioController(radio);
        setController(best);
    }

    private void setRadioController(MediaController c) {
        if (radioCtl != null) {
            if (c != null && radioCtl.getSessionToken().equals(c.getSessionToken())) { updateRadioUi(); return; }
            try { radioCtl.unregisterCallback(radioCallback); } catch (Exception ignored) { }
        }
        radioCtl = c;
        if (radioCtl != null) radioCtl.registerCallback(radioCallback, ui);
        updateRadioUi();
    }

    private void updateRadioUi() {
        if (radioCtl == null) {
            tvRadioTitle.setText("Радио");
            tvRadioSub.setText("Нажмите, чтобы открыть");
            return;
        }
        MediaMetadata m = radioCtl.getMetadata();
        String t = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
        String a = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        tvRadioTitle.setText(t == null || t.isEmpty() ? "Радио" : t);
        tvRadioSub.setText(a == null || a.isEmpty() ? "FM" : a);
    }

    private void sendMediaKey(int code) {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            long t = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
        } catch (Exception ignored) { }
    }

    private void radioStep(boolean next) {
        if (radioCtl != null) {
            if (next) radioCtl.getTransportControls().skipToNext();
            else radioCtl.getTransportControls().skipToPrevious();
        } else {
            sendMediaKey(next ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        }
    }

    private String findRadioPkg() {
        for (App a : apps) {
            String l = a.label.toLowerCase(Locale.ROOT);
            if (l.equals("радио") || l.equals("radio") || l.equals("fm")) return a.pkg;
        }
        return null;
    }

    private void setController(MediaController c) {
        if (mc != null) {
            if (c != null && mc.getSessionToken().equals(c.getSessionToken())) { updateMusicUi(); return; }
            try { mc.unregisterCallback(mcCallback); } catch (Exception ignored) { }
        }
        mc = c;
        if (mc != null) mc.registerCallback(mcCallback, ui);
        updateMusicUi();
    }

    private void showMusicText(String caption, String title, String artist) {
        tvCaption.setText(caption);
        tvTitle.setText(title);
        tvArtist.setText(artist);
        coverImg.setImageBitmap(null);
        lastArt = null;
        coverNote.setVisibility(View.VISIBLE);
        bar.setFraction(0f);
        btnPlay.setKind(IconView.PLAY);
    }

    private void updateMusicUi() {
        if (mc == null) {
            if (hasListenerAccess()) showMusicText("Яндекс Музыка", "Нажмите, чтобы открыть", "");
            return;
        }
        MediaMetadata m = mc.getMetadata();
        PlaybackState s = mc.getPlaybackState();
        String title = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        tvCaption.setText(MUSIC_PKG.equals(mc.getPackageName()) ? "Яндекс Музыка" : "Сейчас играет");
        tvTitle.setText(title == null ? "Без названия" : title);
        tvArtist.setText(artist == null ? "" : artist);
        Bitmap art = null;
        if (m != null) {
            art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        if (art != lastArt) {
            lastArt = art;
            coverImg.setImageBitmap(art);
            coverNote.setVisibility(art == null ? View.VISIBLE : View.GONE);
        }
        boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        btnPlay.setKind(playing ? IconView.PAUSE : IconView.PLAY);
        updateProgress();
    }

    private void updateProgress() {
        if (mc == null) return;
        PlaybackState s = mc.getPlaybackState();
        MediaMetadata m = mc.getMetadata();
        if (s == null || m == null) return;
        long dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (dur <= 0) { bar.setFraction(0f); return; }
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        bar.setFraction((float) pos / dur);
    }

    private void togglePlay() {
        if (mc == null) { onMusicCardClick(); return; }
        PlaybackState s = mc.getPlaybackState();
        if (s != null && s.getState() == PlaybackState.STATE_PLAYING) mc.getTransportControls().pause();
        else mc.getTransportControls().play();
    }

    private void onMusicCardClick() {
        if (!hasListenerAccess()) {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Toast.makeText(this, "Откройте: Настройки → Приложения → Доступ к уведомлениям → Лаунчер", Toast.LENGTH_LONG).show();
            }
            return;
        }
        String pkg = mc != null ? mc.getPackageName() : MUSIC_PKG;
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(MUSIC_PKG);
        if (i == null) {
            Toast.makeText(this, "Яндекс Музыка не установлена", Toast.LENGTH_SHORT).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void buildMapCard(LinearLayout content) {
        LinearLayout mapCard = card(LinearLayout.VERTICAL);
        mapCard.setPadding(dp(12), dp(10), dp(12), dp(12));
        LinearLayout.LayoutParams mp = lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.35f);
        mp.leftMargin = dp(10);
        content.addView(mapCard, mp);

        mapCard.addView(label("Навигация", 12, MUTED));

        View art = null;
        String mk = prefs.getString(KEY_MAPKEY, DEFAULT_MAPKEY).trim();
        if (mk.length() > 10 && prefs.getBoolean(KEY_LIVEMAP, true)) {
            try {
                LiveMap.init(this, mk);
                liveMap = new LiveMap(this, v -> openMaps());
                art = liveMap.view();
            } catch (Throwable t) {
                liveMap = null;
            }
        }
        if (art == null) {
            art = new MapArtView(this);
            art.setOnClickListener(v -> openMaps());
        }
        LinearLayout.LayoutParams ap = lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        ap.topMargin = dp(6);
        ap.bottomMargin = dp(10);
        mapCard.addView(art, ap);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams b1 = lp(0, dp(48), 1f);
        b1.rightMargin = dp(8);
        row.addView(navButton("Яндекс Карты", "ru.yandex.yandexmaps", 0xFF3B82F6, 0xFF22D3EE), b1);
        LinearLayout.LayoutParams b2 = lp(0, dp(48), 1f);
        b2.leftMargin = dp(8);
        row.addView(navButton("Навигатор", "ru.yandex.yandexnavi", 0xFFF59E0B, 0xFFFBBF24), b2);
        mapCard.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));
    }

    private TextView navButton(final String text, final String pkg, int c1, int c2) {
        TextView b = label(text, 15, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{c1, c2});
        g.setCornerRadius(dp(14));
        b.setBackground(g);
        b.setClickable(true);
        b.setOnClickListener(v -> {
            Intent i = pm.getLaunchIntentForPackage(pkg);
            if (i == null) {
                Toast.makeText(this, "Не установлено: " + text, Toast.LENGTH_SHORT).show();
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        });
        return b;
    }

    private void openMaps() {
        String[] pk = {"ru.yandex.yandexmaps", "ru.yandex.yandexnavi"};
        for (String p : pk) {
            Intent i = pm.getLaunchIntentForPackage(p);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            }
        }
        Toast.makeText(this, "Яндекс Карты не установлены", Toast.LENGTH_SHORT).show();
    }

    private void openRadio() {
        for (App a : apps) {
            String l = a.label.toLowerCase(Locale.ROOT);
            if (l.equals("радио") || l.equals("radio") || l.equals("fm")) { launch(a); return; }
        }
        Toast.makeText(this, "Приложение «Радио» не найдено", Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- избранное

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
        favItems.clear();
        for (String p : getFavs()) {
            App a = findApp(p);
            if (a != null) favItems.add(a);
        }
        favItems.add(plusApp);
        adapter.notifyDataSetChanged();
    }

    private void showPicker() {
        final List<App> list = new ArrayList<>(apps);
        final List<String> favs = getFavs();
        final boolean[] checked = new boolean[list.size()];
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            names[i] = list.get(i).label;
            checked[i] = favs.contains(list.get(i).pkg);
        }
        new AlertDialog.Builder(this)
                .setTitle("Выберите приложения")
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Готово", (d, w) -> {
                    List<String> out = new ArrayList<>();
                    for (String p : favs) {
                        for (int i = 0; i < list.size(); i++) {
                            if (list.get(i).pkg.equals(p) && checked[i]) { out.add(p); break; }
                        }
                    }
                    for (int i = 0; i < list.size(); i++) {
                        if (checked[i] && !out.contains(list.get(i).pkg)) out.add(list.get(i).pkg);
                    }
                    saveFavs(out);
                    rebuildFavs();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showApps(boolean show) {
        if (content == null || appsPanel == null) return;
        showingApps = show;
        content.setVisibility(show ? View.GONE : View.VISIBLE);
        appsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && appsGrid != null) appsGrid.setSelection(0);
    }

    private void toggleFav(App a) {
        List<String> l = getFavs();
        if (l.contains(a.pkg)) l.remove(a.pkg); else l.add(a.pkg);
        saveFavs(l);
        rebuildFavs();
    }

    // ---------------------------------------------------------------- действия

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
                fav ? "Убрать из избранного" : "Добавить в избранное",
                "О приложении",
                "Удалить"
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

    // ---------------------------------------------------------------- самообновление

    private void checkUpdate(final boolean manual) {
        prefs.edit().putLong("upd_ts", System.currentTimeMillis()).apply();
        net.execute(() -> {
            try {
                final Updater.Info info = Updater.fetchLatest();
                if (info != null && info.code > Updater.currentCode(this)) {
                    ui.post(() -> askUpdate(info));
                } else if (manual) {
                    ui.post(() -> Toast.makeText(this, "Установлена последняя версия", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                if (manual) {
                    ui.post(() -> Toast.makeText(this, "Не удалось проверить (нет интернета?)", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    private void askUpdate(final Updater.Info info) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Доступна новая версия")
                .setMessage("Вышла версия " + info.tag + ". Обновить?")
                .setPositiveButton("Обновить", (d, w) -> startUpdate(info))
                .setNegativeButton("Позже", null)
                .show();
    }

    private void startUpdate(final Updater.Info info) {
        if (!pm.canRequestPackageInstalls()) {
            Toast.makeText(this, "Дайте разрешение, затем снова нажмите «Обновить»", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        Toast.makeText(this, "Загрузка…", Toast.LENGTH_LONG).show();
        net.execute(() -> {
            try {
                Updater.downloadAndInstall(getApplicationContext(), info.url);
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Не удалось загрузить: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private String activePlayers() {
        if (!hasListenerAccess()) return "нет доступа к уведомлениям";
        try {
            List<MediaController> l = msm.getActiveSessions(listener);
            if (l == null || l.isEmpty()) return "нет активных";
            List<String> n = new ArrayList<>();
            for (MediaController c : l) n.add(c.getPackageName());
            return TextUtils.join(", ", n);
        } catch (Exception e) {
            return "ошибка";
        }
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(8));
        final TextView lbl = label("Сила стекла: " + glass() + "%", 16, Color.WHITE);
        SeekBar sb = new SeekBar(this);
        sb.setMax(10);
        sb.setProgress(glass() / 10);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                prefs.edit().putInt(KEY_GLASS, p * 10).apply();
                lbl.setText("Сила стекла: " + (p * 10) + "%");
                applyGlass();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        box.addView(lbl);
        box.addView(sb);
        final TextView ver = label("Версия: " + Updater.currentCode(this), 14, MUTED);
        ver.setPadding(0, dp(18), 0, dp(6));
        box.addView(ver);
        Button upd = new Button(this);
        upd.setText("Проверить обновления");
        upd.setOnClickListener(v -> checkUpdate(true));
        box.addView(upd);
        final TextView mt = label("Живая карта Яндекс", 16, Color.WHITE);
        mt.setPadding(0, dp(18), 0, dp(4));
        box.addView(mt);
        final android.widget.CheckBox cb = new android.widget.CheckBox(this);
        cb.setText("Показывать живую карту");
        cb.setTextColor(Color.WHITE);
        cb.setChecked(prefs.getBoolean(KEY_LIVEMAP, true));
        box.addView(cb);
        final android.widget.EditText ek = new android.widget.EditText(this);
        ek.setHint("API-ключ MapKit");
        ek.setTextColor(Color.WHITE);
        ek.setHintTextColor(MUTED);
        ek.setSingleLine(true);
        ek.setText(prefs.getString(KEY_MAPKEY, DEFAULT_MAPKEY));
        box.addView(ek);
        final TextView diag = label("Плееры: " + activePlayers(), 12, MUTED);
        diag.setPadding(0, dp(12), 0, 0);
        box.addView(diag);
        new AlertDialog.Builder(this)
                .setTitle("Настройки лаунчера")
                .setView(box)
                .setPositiveButton("Готово", (d, w) -> {
                    String nk = ek.getText().toString().trim();
                    boolean nl = cb.isChecked();
                    boolean changed = !nk.equals(prefs.getString(KEY_MAPKEY, DEFAULT_MAPKEY))
                            || nl != prefs.getBoolean(KEY_LIVEMAP, true);
                    prefs.edit().putString(KEY_MAPKEY, nk).putBoolean(KEY_LIVEMAP, nl).apply();
                    if (changed) restartSelf();
                })
                .show();
    }

    /** Перезапуск лаунчера, чтобы применить ключ/выключение живой карты. */
    private void restartSelf() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    this, 7, i, android.app.PendingIntent.FLAG_CANCEL_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            ((android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE))
                    .set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 400, pi);
        } catch (Exception ignored) { }
        finishAffinity();
        System.exit(0);
    }
}
