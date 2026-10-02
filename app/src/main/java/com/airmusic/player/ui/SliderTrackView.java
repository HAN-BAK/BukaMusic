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
    /** 嵌在条里的图标（音量图标）：它也要跟着切口一起被切掉。 */
    private View iconSource;

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

    /** 嵌在条左侧的图标控件；它的 drawable 由本 View 代画，才能被切口裁开。 */
    public void setIconSource(View icon) {
        if (iconSource == icon) return;
        iconSource = icon;
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

        // 手柄行程在轨道内部再各让出半个手柄宽：拖到两端时手柄正好贴在条的内侧，
        // 不会半个身子探到条外面去（那样看着像渲染错位）。
        float thumbCenterX = left + half + fraction * Math.max(0f, (right - left) - half * 2f);
        float gapPx = thumbGapDp * d;
        // 切口（断口）只在这侧有足够空间时才留：手柄贴到端点时这一侧不留切口，
        // 轨道直接画到手柄下面——否则端点会剩下一小块孤零零的端头，看着像渲染错误。
        float capKeep = radius * 0.5f;
        float leftCut = thumbCenterX - half - gapPx;
        boolean leftGap = leftCut > left + capKeep;
        float leftPieceEnd = leftGap ? leftCut : thumbCenterX;
        float rightCut = thumbCenterX + half + gapPx;
        boolean rightGap = rightCut < right - capKeep;
        float rightPieceStart = rightGap ? rightCut : thumbCenterX;

        // 轨道两端永远是半圆：先按整条胶囊算形状，再按左右两段分别裁切。
        if (leftPieceEnd > left + 0.5f) {
            paint.setColor(trackColor);
            canvas.save();
            canvas.clipRect(left, 0f, leftPieceEnd, h);
            rect.set(left, trackTop, right, trackBottom);
            canvas.drawRoundRect(rect, radius, radius, paint);
            canvas.restore();
        }
        if (right > rightPieceStart + 0.5f) {
            paint.setColor(trackColor);
            canvas.save();
            canvas.clipRect(rightPieceStart, 0f, right, h);
            rect.set(left, trackTop, right, trackBottom);
            canvas.drawRoundRect(rect, radius, radius, paint);
            canvas.restore();
        }
        // 已播放段：左端跟着轨道圆角，靠手柄那头是直角切口
        float edge = Math.min(leftPieceEnd, Math.max(left, fillRight()));
        if (edge > left + 0.5f) {
            paint.setColor(fillColor);
            // 同样用「整条胶囊 + 裁切」：左端永远是圆的，右端被裁成直角
            canvas.save();
            canvas.clipRect(left, 0f, edge, h);
            rect.set(left, trackTop, right, trackBottom);
            canvas.drawRoundRect(rect, radius, radius, paint);
            canvas.restore();
        }

        // 手柄：竖着的圆角条，悬在挖空处
        float thumbHeight = Math.min(h, thumbHeightDp * d);
        paint.setColor(thumbColor);
        rect.set(thumbCenterX - half, (h - thumbHeight) / 2f,
                thumbCenterX + half, (h + thumbHeight) / 2f);
        float thumbRadius = Math.min(half, thumbHeight / 2f);
        canvas.drawRoundRect(rect, thumbRadius, thumbRadius, paint);

        // 图标最后画（压在手柄上面），但同样被切口裁掉
        drawIcon(canvas, leftPieceEnd, rightPieceStart, left, right);
    }

    /** 图标分左右两段裁切绘制：切口那一段不画，视觉上就是被切断。 */
    private void drawIcon(Canvas canvas, float leftPieceEnd, float rightPieceStart,
                          float left, float right) {
        if (iconSource == null) return;
        if (iconSource.getVisibility() == View.GONE) return;
        int iconW = iconSource.getWidth();
        int iconH = iconSource.getHeight();
        if (iconW <= 0 || iconH <= 0) return;
        // 本 View 在音量条里是垂直居中的，坐标原点和图标控件不一样，必须先换算
        float iconX = iconSource.getLeft() - getLeft();
        float iconY = iconSource.getTop() - getTop();
        int save = canvas.save();
        // 音量换档时那个「弹一下」的动效（缩放在图标控件上）
        float scaleX = iconSource.getScaleX();
        float scaleY = iconSource.getScaleY();
        if (scaleX != 1f || scaleY != 1f) {
            canvas.scale(scaleX, scaleY, iconX + iconW / 2f, iconY + iconH / 2f);
        }
        if (leftPieceEnd > left + 0.5f) {
            canvas.save();
            canvas.clipRect(left, 0f, leftPieceEnd, getHeight());
            canvas.translate(iconX, iconY);
            iconSource.draw(canvas);
            canvas.restore();
        }
        if (right > rightPieceStart + 0.5f) {
            canvas.save();
            canvas.clipRect(rightPieceStart, 0f, right, getHeight());
            canvas.translate(iconX, iconY);
            iconSource.draw(canvas);
            canvas.restore();
        }
        canvas.restoreToCount(save);
    }

}
