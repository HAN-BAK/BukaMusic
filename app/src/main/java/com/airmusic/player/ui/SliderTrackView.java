package com.airmusic.player.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 音量条的整根条：轨道 + 已播放段 + 手柄，全部由这一个 View 画。
 *
 * <p>为什么不直接用 Material 的轨道和手柄：
 * <ul>
 *   <li>Material 把轨道按内部的 widgetHeight 排版，**在控件里并不居中**，
 *       自绘才能让轨道、手柄、音量图标三者严格同心；</li>
 *   <li>Material 的轨道两端永远是半圆、且是它的内部几何；自绘可以两头半圆、
 *       底色不透明（模糊封面不会透出来变成小点阵）；</li>
 *   <li>整条（含音量图标那一段）都要能触摸，所以 Material 的滑条铺满整条，
 *       但手柄我们自己画，位置就永远和轨道一致。</li>
 * </ul>
 */
public class SliderTrackView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int trackColor = 0xFF9DB2C4;
    private int fillColor = 0xFF13293D;
    private int thumbColor = 0xFF0F2436;
    private float cornerDp = 999f;
    private float trackHeightDp = 0f;
    private float thumbHalfDp = 4f;
    private float thumbHeightDp = 48f;
    private float thumbGapDp = 8f;
    private float fraction = 0f;

    public SliderTrackView(Context context) {
        this(context, null);
    }

    public SliderTrackView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setColors(int track, int fill, int thumb) {
        // 不透明：轨道底下是模糊封面，半透明会让背景纹理透出来。
        track = 0xFF000000 | (track & 0xFFFFFF);
        fill = 0xFF000000 | (fill & 0xFFFFFF);
        thumb = 0xFF000000 | (thumb & 0xFFFFFF);
        if (track == trackColor && fill == fillColor && thumb == thumbColor) return;
        trackColor = track;
        fillColor = fill;
        thumbColor = thumb;
        invalidate();
    }

    /** 手柄宽度 / 高度（dp）与手柄和轨道之间的断口（dp）。 */
    public void setThumb(float widthDp, float heightDp, float gapDp) {
        if (widthDp == thumbHalfDp * 2f && heightDp == thumbHeightDp && gapDp == thumbGapDp) {
            return;
        }
        thumbHalfDp = widthDp / 2f;
        thumbHeightDp = heightDp;
        thumbGapDp = gapDp;
        invalidate();
    }

    /** 轨道高度（dp）；0 表示用 View 自身高度。 */
    public void setTrackHeightDp(float dp) {
        if (dp == trackHeightDp) return;
        trackHeightDp = dp;
        invalidate();
    }

    /** 0~1 的音量进度。 */
    public void setFraction(float value) {
        float next = Math.max(0f, Math.min(1f, value));
        if (next == fraction) return;
        fraction = next;
        invalidate();
    }

    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    /** 已播放段的右端（本 View 坐标），手柄左侧还留了断口。 */
    private float fillRight() {
        float d = density();
        float half = thumbHalfDp * d;
        float trackWidth = Math.max(0f, getWidth() - half * 2f);
        float thumbCenter = half + fraction * trackWidth;
        return thumbCenter - half - thumbGapDp * d;
    }

    /** 某个 x（本 View 坐标，也就是整根条的坐标）是否已被深色段覆盖。 */
    public boolean covers(float x) {
        float d = density();
        return x >= thumbHalfDp * d && x <= fillRight();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;
        float d = density();
        float half = thumbHalfDp * d;
        float left = half;
        float right = w - half;
        if (right <= left) return;

        // 轨道：两头半圆的胶囊，垂直居中（比 View 矮的话上下就留出手柄的余地）。
        // 手柄两侧要「挖空」——那里什么都不画，直接露出页面的背景模糊层，
        // 所以轨道是分左右两段画的，而不是整条 + 一块遮挡色。
        float trackHeight = trackHeightDp > 0f ? Math.min(h, trackHeightDp * d) : h;
        float trackTop = (h - trackHeight) / 2f;
        float trackBottom = trackTop + trackHeight;
        float radius = Math.min(cornerDp * d, trackHeight / 2f);

        float cutHalf = half + thumbGapDp * d;
        float thumbCenterX = half + fraction * (right - left);
        float cutLeft = Math.max(left, thumbCenterX - cutHalf);
        float cutRight = Math.min(right, thumbCenterX + cutHalf);

        // 左段：未播放底色 + 已播放段（靠手柄那头是直角）
        if (cutLeft > left + 0.5f) {
            paint.setColor(trackColor);
            drawSegment(canvas, left, cutLeft, trackTop, trackBottom, radius, true, false);
            float edge = Math.min(cutLeft, Math.max(left, fillRight()));
            if (edge > left + 0.5f) {
                paint.setColor(fillColor);
                drawSegment(canvas, left, edge, trackTop, trackBottom, radius, true, false);
            }
        }
        // 右段：只剩未播放底色
        if (right > cutRight + 0.5f) {
            paint.setColor(trackColor);
            drawSegment(canvas, cutRight, right, trackTop, trackBottom, radius, false, true);
        }

        // 手柄：竖着的圆角条，悬在挖空处
        float thumbHeight = Math.min(h, thumbHeightDp * d);
        paint.setColor(thumbColor);
        rect.set(thumbCenterX - half, (h - thumbHeight) / 2f,
                thumbCenterX + half, (h + thumbHeight) / 2f);
        float thumbRadius = Math.min(half, thumbHeight / 2f);
        canvas.drawRoundRect(rect, thumbRadius, thumbRadius, paint);
    }

    /** 画一段轨道：可以指定某一头是圆角（胶囊端），另一头直角。 */
    private void drawSegment(Canvas canvas, float segLeft, float segRight,
                             float top, float bottom, float radius,
                             boolean roundLeft, boolean roundRight) {
        if (segRight <= segLeft) return;
        rect.set(segLeft, top, segRight, bottom);
        float maxRadius = Math.min(radius, Math.min(segRight - segLeft, bottom - top) / 2f);
        float tl = roundLeft ? maxRadius : 0f;
        float tr = roundRight ? maxRadius : 0f;
        android.graphics.Path path = new android.graphics.Path();
        path.addRoundRect(rect, new float[]{
                tl, tl, tr, tr, tr, tr, tl, tl},
                android.graphics.Path.Direction.CW);
        canvas.drawPath(path, paint);
    }
}
