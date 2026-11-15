package com.fastcar.home;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.yandex.mapkit.MapKitFactory;
import com.yandex.mapkit.geometry.Point;
import com.yandex.mapkit.map.CameraPosition;
import com.yandex.mapkit.mapview.MapView;

/**
 * Живая карта Яндекс MapKit (lite). Весь код MapKit собран здесь: если карта выключена
 * или ключа нет, класс не загружается и лаунчер остаётся лёгким.
 * Карта без жестов, камера следует за GPS; нажатие открывает приложение «Яндекс Карты».
 */
final class LiveMap {
    private static boolean inited;
    private final MapView map;
    private final FrameLayout root;
    private long lastMove;
    private boolean on;

    static void init(Context c, String key) {
        if (inited) return;
        MapKitFactory.setApiKey(key);
        MapKitFactory.initialize(c.getApplicationContext());
        inited = true;
    }

    LiveMap(Context c, View.OnClickListener click) {
        float d = c.getResources().getDisplayMetrics().density;
        root = new FrameLayout(c);
        map = new MapView(c);
        map.getMap().setNightModeEnabled(true);
        map.getMap().setRotateGesturesEnabled(false);
        map.getMap().setTiltGesturesEnabled(false);
        map.getMap().setZoomGesturesEnabled(false);
        map.getMap().setScrollGesturesEnabled(false);
        map.getMap().move(new CameraPosition(new Point(41.55, 60.63), 14f, 0f, 0f));
        root.addView(map, new FrameLayout.LayoutParams(-1, -1));

        // точка «Вы здесь» в центре
        View dot = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFF3B82F6);
        g.setStroke(Math.round(3 * d), Color.WHITE);
        dot.setBackground(g);
        FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(Math.round(18 * d), Math.round(18 * d));
        dp.gravity = android.view.Gravity.CENTER;
        root.addView(dot, dp);

        // прозрачный слой ловит нажатие
        View tap = new View(c);
        tap.setOnClickListener(click);
        root.addView(tap, new FrameLayout.LayoutParams(-1, -1));
    }

    View view() { return root; }

    void onStart() {
        if (on) return;
        on = true;
        try { MapKitFactory.getInstance().onStart(); map.onStart(); } catch (Throwable ignored) { }
    }

    void onStop() {
        if (!on) return;
        on = false;
        try { map.onStop(); MapKitFactory.getInstance().onStop(); } catch (Throwable ignored) { }
    }

    void moveTo(double lat, double lon, float bearing) {
        long now = System.currentTimeMillis();
        if (now - lastMove < 2000) return;
        lastMove = now;
        try {
            map.getMap().move(new CameraPosition(new Point(lat, lon), 15f, bearing, 0f));
        } catch (Throwable ignored) { }
    }
}
