package com.fastcar.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/** Спидометр: дуга 270°, скорость в км/ч (GPS). Лёгкий, без анимации. */
class GaugeView extends View {
    private static final int MAX = 200;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint prog = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int speed = 0; // -1 = нет данных

    GaugeView(Context c) {
        super(c);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(0x1AFFFFFF);
        prog.setStyle(Paint.Style.STROKE);
        prog.setStrokeCap(Paint.Cap.ROUND);
        num.setColor(0xFFFFFFFF);
        num.setTextAlign(Paint.Align.CENTER);
        num.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        unit.setColor(0xFF9AA3C0);
        unit.setTextAlign(Paint.Align.CENTER);
    }

    void setSpeed(int s) {
        if (s < -1) s = -1;
        if (s == speed) return;
        speed = s;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        prog.setShader(new LinearGradient(0, 0, w, 0, Theme.acc1, Theme.acc2, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float s = Math.min(w, h);
        float stroke = s * 0.075f;
        track.setStrokeWidth(stroke);
        prog.setStrokeWidth(stroke);
        float cx = w / 2f, cy = h / 2f, r = s / 2f - stroke;
        rect.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(rect, 135, 270, false, track);
        if (speed > 0) {
            c.drawArc(rect, 135, 270f * Math.min(speed, MAX) / MAX, false, prog);
        }
        num.setTextSize(s * 0.30f);
        c.drawText(speed < 0 ? "--" : String.valueOf(speed), cx, cy + s * 0.07f, num);
        unit.setTextSize(s * 0.08f);
        c.drawText("км/ч", cx, cy + s * 0.07f + s * 0.12f, unit);
    }
}
