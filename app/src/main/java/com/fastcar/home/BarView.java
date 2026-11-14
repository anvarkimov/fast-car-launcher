package com.fastcar.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Полоса прогресса трека. */
class BarView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float frac = 0f;

    BarView(Context c) {
        super(c);
        track.setColor(0x26FFFFFF);
    }

    void setFraction(float f) {
        f = Math.max(0f, Math.min(1f, f));
        if (Math.abs(f - frac) < 0.002f) return;
        frac = f;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        fill.setShader(new LinearGradient(0, 0, w, 0, 0xFFA78BFA, 0xFFF472B6, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        r.set(0, 0, w, h);
        c.drawRoundRect(r, h / 2, h / 2, track);
        if (frac > 0f) {
            r.set(0, 0, Math.max(h, w * frac), h);
            c.drawRoundRect(r, h / 2, h / 2, fill);
        }
    }
}
