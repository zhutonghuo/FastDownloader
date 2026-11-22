package com.jisudownloader.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class ScanFrame extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ScanFrame(Context c, AttributeSet a) {
        super(c, a);
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        int size = (int) (Math.min(w, h) * 0.70f);
        int l = (w - size) / 2;
        int t = (h - size) / 2 - dp(24);
        int r = l + size;
        int b = t + size;

        paint.setColor(0x88000000);
        paint.setStyle(Paint.Style.FILL);
        cv.drawRect(0, 0, w, t, paint);
        cv.drawRect(0, t, l, b, paint);
        cv.drawRect(r, t, w, b, paint);
        cv.drawRect(0, b, w, h, paint);

        paint.setColor(0xFF2F6FED);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        int len = dp(30);
        cv.drawLine(l, t, l + len, t, paint);
        cv.drawLine(l, t, l, t + len, paint);
        cv.drawLine(r - len, t, r, t, paint);
        cv.drawLine(r, t, r, t + len, paint);
        cv.drawLine(l, b - len, l, b, paint);
        cv.drawLine(l, b, l + len, b, paint);
        cv.drawLine(r - len, b, r, b, paint);
        cv.drawLine(r, b, r, b - len, paint);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
