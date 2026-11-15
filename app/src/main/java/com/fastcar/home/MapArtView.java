package com.fastcar.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Декоративная «карта» с маршрутом. Нажатие открывает настоящее приложение карт. */
class MapArtView extends View {
    private final float d;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint road = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint route = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path clip = new Path();
    private final RectF rf = new RectF();

    MapArtView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        road.setStyle(Paint.Style.STROKE);
        road.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        glow.setStrokeJoin(Paint.Join.ROUND);
        glow.setColor(0x4422D3EE);
        glow.setPathEffect(new CornerPathEffect(16 * d));
        route.setStyle(Paint.Style.STROKE);
        route.setStrokeCap(Paint.Cap.ROUND);
        route.setStrokeJoin(Paint.Join.ROUND);
        route.setPathEffect(new CornerPathEffect(16 * d));
        setClickable(true);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        route.setShader(new LinearGradient(0, h, w, 0, 0xFF3B82F6, 0xFF22D3EE, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        rf.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(rf, 16 * d, 16 * d, Path.Direction.CW);
        c.save();
        c.clipPath(clip);

        fill.setColor(0x55060B1A);
        c.drawRect(rf, fill);

        // парки и река
        fill.setColor(0x2234D399);
        rf.set(w * 0.04f, h * 0.06f, w * 0.34f, h * 0.30f);
        c.drawRoundRect(rf, 14 * d, 14 * d, fill);
        rf.set(w * 0.70f, h * 0.60f, w * 0.96f, h * 0.90f);
        c.drawRoundRect(rf, 14 * d, 14 * d, fill);
        road.setColor(0x333B82F6);
        road.setStrokeWidth(22 * d);
        path.reset();
        path.moveTo(-10, h * 0.78f);
        path.quadTo(w * 0.45f, h * 0.55f, w + 10, h * 0.18f);
        c.drawPath(path, road);

        // улицы
        road.setColor(0x16FFFFFF);
        road.setStrokeWidth(4 * d);
        float[] hy = {0.15f, 0.40f, 0.65f, 0.88f};
        float[] vx = {0.16f, 0.42f, 0.68f, 0.90f};
        for (float y : hy) c.drawLine(0, h * y, w, h * y, road);
        for (float x : vx) c.drawLine(w * x, 0, w * x, h, road);
        road.setColor(0x10FFFFFF);
        road.setStrokeWidth(3 * d);
        c.drawLine(0, h * 0.95f, w * 0.7f, 0, road);
        c.drawLine(w * 0.3f, h, w, h * 0.3f, road);

        // маршрут
        path.reset();
        path.moveTo(w * 0.16f, h * 0.88f);
        path.lineTo(w * 0.16f, h * 0.65f);
        path.lineTo(w * 0.68f, h * 0.65f);
        path.lineTo(w * 0.68f, h * 0.40f);
        path.lineTo(w * 0.90f, h * 0.40f);
        path.lineTo(w * 0.90f, h * 0.15f);
        glow.setStrokeWidth(16 * d);
        c.drawPath(path, glow);
        route.setStrokeWidth(6 * d);
        c.drawPath(path, route);

        // старт
        pt.setColor(0x3322D3EE);
        c.drawCircle(w * 0.16f, h * 0.88f, 14 * d, pt);
        pt.setColor(0xFFFFFFFF);
        c.drawCircle(w * 0.16f, h * 0.88f, 7 * d, pt);
        pt.setColor(0xFF3B82F6);
        c.drawCircle(w * 0.16f, h * 0.88f, 3.5f * d, pt);
        // финиш
        pt.setColor(0xFFF472B6);
        c.drawCircle(w * 0.90f, h * 0.15f, 10 * d, pt);
        pt.setColor(0xFFFFFFFF);
        c.drawCircle(w * 0.90f, h * 0.15f, 3.5f * d, pt);

        c.restore();
    }
}
