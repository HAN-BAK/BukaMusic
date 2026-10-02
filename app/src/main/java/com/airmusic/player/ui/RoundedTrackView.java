package com.airmusic.player.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 圆角矩形滑条轨道。
 *
 * <p>Material 自带轨道两端永远是半圆（圆角半径写死成「高度的一半」），
 * 做不出圆角矩形的效果，所以这里自己画：底色是未播放段，左边一段是已播放段，
 * 靠近手柄的位置留出手柄的空隙（和 Material 的 thumbTrackGapSize 一致）。
 * 手柄、拖动手感、拖动气泡仍然用 Material 的 Slider，只是把它的轨道设成透明。
 */
public class RoundedTrackView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int trackColor = 0xFF9DB2C4;
    private int fillColor = 0xFF13293D;
    private float cornerDp = 6f;
    private float fraction = 0f;
    private float thumbGapPx = 0f;

    public RoundedTrackView(Context context) {
        this(context, null);
    }

    public RoundedTrackView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setColors(int track, int fill) {
        // 必须不透明：轨道底下是模糊封面，半透明会让背景纹理透出来变成小点阵。
        track = 0xFF000000 | (track & 0xFFFFFF);
        fill = 0xFF000000 | (fill & 0xFFFFFF);
        if (track == trackColor && fill == fillColor) return;
        trackColor = track;
        fillColor = fill;
        invalidate();
    }

    public void setCornerDp(float dp) {
        if (dp == cornerDp) return;
        cornerDp = dp;
        invalidate();
    }

    /** 手柄两侧空出来的距离（手柄半宽 + 断开间隙）。 */
    public void setThumbGapPx(float px) {
        if (px == thumbGapPx) return;
        thumbGapPx = px;
        invalidate();
    }

    /** 0~1 的播放/音量进度。 */
    public void setFraction(float value) {
        float next = Math.max(0f, Math.min(1f, value));
        if (next == fraction) return;
        fraction = next;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;
        float density = getResources().getDisplayMetrics().density;
        float radius = Math.min(cornerDp * density, Math.min(h, w) / 2f);

        paint.setColor(trackColor);
        rect.set(0f, 0f, w, h);
        canvas.drawRoundRect(rect, radius, radius, paint);

        float edge = fraction * w - thumbGapPx;
        if (edge <= 1f) return;
        float fillRadius = Math.min(radius, edge / 2f);
        paint.setColor(fillColor);
        rect.set(0f, 0f, edge, h);
        canvas.drawRoundRect(rect, fillRadius, fillRadius, paint);
    }
}
