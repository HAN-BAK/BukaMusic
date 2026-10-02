package com.airmusic.player.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 音量条样式的滑条轨道（对齐 Android Canary 的系统音量条）：
 *
 * <ul>
 *   <li>粗的圆角轨道；</li>
 *   <li>已播放段用**实色**（偏深），未播放段用**浅色**；</li>
 *   <li>两段在滑块两侧**断开**，留出间隙，滑块本身是竖向胶囊（由 SeekBar 的
 *       thumb 画）。</li>
 * </ul>
 *
 * 自己按 level 画，不依赖 LayerDrawable 的层语义，所以不受 SeekBar / 主题 tint
 * 的影响。
 */
public class SliderDrawable extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int playedColor;
    private int restColor;
    private float thicknessPx;
    private float gapPx;
    private int level = 5000;

    public SliderDrawable(int playedColor, int restColor, float thicknessPx, float gapPx) {
        this.playedColor = playedColor;
        this.restColor = restColor;
        this.thicknessPx = thicknessPx;
        this.gapPx = gapPx;
        paint.setStyle(Paint.Style.FILL);
    }

    public void setColors(int played, int rest) {
        playedColor = played;
        restColor = rest;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        android.graphics.Rect bounds = getBounds();
        if (bounds.width() <= 0) return;
        float radius = thicknessPx / 2f;
        float cy = bounds.exactCenterY();
        float top = cy - radius;
        float bottom = cy + radius;
        float pivot = bounds.left + bounds.width() * (level / 10000f);

        // 已播放段：左边到滑块左侧留出间隙
        float playedRight = Math.max(bounds.left, pivot - gapPx);
        if (playedRight > bounds.left) {
            paint.setColor(playedColor);
            rect.set(bounds.left, top, playedRight, bottom);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }

        // 未播放段：滑块右侧留出间隙到最右
        float restLeft = Math.min(bounds.right, pivot + gapPx);
        if (restLeft < bounds.right) {
            paint.setColor(restColor);
            rect.set(restLeft, top, bounds.right, bottom);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
    }

    @Override
    protected boolean onLevelChange(int newLevel) {
        level = newLevel;
        invalidateSelf();
        return true;
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
