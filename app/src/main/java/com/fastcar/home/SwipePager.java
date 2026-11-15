package com.fastcar.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/** Простой горизонтальный «пейджер»: страницы листаются свайпом, точки справа сверху. */
class SwipePager extends FrameLayout {
    private int page = 0;
    private float downX, downY;
    private boolean dragging;
    private final int slop;
    private final float density;
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

    SwipePager(Context c) {
        super(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        density = c.getResources().getDisplayMetrics().density;
    }

    @Override
    public void addView(View child, ViewGroup.LayoutParams params) {
        super.addView(child, params);
        child.setVisibility(getChildCount() == 1 ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = ev.getX() - downX, dy = ev.getY() - downY;
                if (Math.abs(dx) > slop * 2 && Math.abs(dx) > Math.abs(dy)) {
                    dragging = true;
                    return true;
                }
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    float dx = ev.getX() - downX;
                    if (dx < -60 * density) setPage(page + 1);
                    else if (dx > 60 * density) setPage(page - 1);
                }
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
            default:
                return true;
        }
    }

    void setPage(int p) {
        p = Math.max(0, Math.min(getChildCount() - 1, p));
        if (p == page) return;
        View out = getChildAt(page);
        View in = getChildAt(p);
        page = p;
        in.setAlpha(0f);
        in.setVisibility(View.VISIBLE);
        out.setVisibility(View.GONE);
        in.animate().alpha(1f).setDuration(160).start();
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        int n = getChildCount();
        if (n < 2) return;
        float cx = getWidth() - 26 * density;
        float y = 16 * density;
        for (int i = 0; i < n; i++) {
            dot.setColor(i == page ? 0xFFFFFFFF : 0x55FFFFFF);
            c.drawCircle(cx + (i - (n - 1) / 2f) * 14 * density, y, 3.5f * density, dot);
        }
    }
}
