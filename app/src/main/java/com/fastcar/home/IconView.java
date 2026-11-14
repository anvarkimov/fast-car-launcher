package com.fastcar.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Кнопки плеера: назад / вперёд / пуск / пауза. */
class IconView extends View {
    static final int PREV = 0, NEXT = 1, PLAY = 2, PAUSE = 3;
    private int kind;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    IconView(Context c, int kind) {
        super(c);
        this.kind = kind;
    }

    void setKind(int k) {
        if (k != kind) {
            kind = k;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        path.reset();
        if (kind == PLAY || kind == PAUSE) {
            p.setColor(0xFFFFFFFF);
            c.drawCircle(w / 2, h / 2, Math.min(w, h) / 2, p);
            p.setColor(0xFF0B1022);
            if (kind == PLAY) {
                path.moveTo(w * 0.40f, h * 0.28f);
                path.lineTo(w * 0.70f, h * 0.50f);
                path.lineTo(w * 0.40f, h * 0.72f);
                path.close();
                c.drawPath(path, p);
            } else {
                c.drawRect(w * 0.36f, h * 0.30f, w * 0.46f, h * 0.70f, p);
                c.drawRect(w * 0.54f, h * 0.30f, w * 0.64f, h * 0.70f, p);
            }
        } else {
            p.setColor(0xFFFFFFFF);
            if (kind == PREV) {
                c.drawRect(w * 0.24f, h * 0.30f, w * 0.31f, h * 0.70f, p);
                path.moveTo(w * 0.76f, h * 0.30f);
                path.lineTo(w * 0.38f, h * 0.50f);
                path.lineTo(w * 0.76f, h * 0.70f);
            } else {
                c.drawRect(w * 0.69f, h * 0.30f, w * 0.76f, h * 0.70f, p);
                path.moveTo(w * 0.24f, h * 0.30f);
                path.lineTo(w * 0.62f, h * 0.50f);
                path.lineTo(w * 0.24f, h * 0.70f);
            }
            path.close();
            c.drawPath(path, p);
        }
    }
}
