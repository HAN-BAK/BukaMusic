package com.airmusic.player.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * 播放键上的「播放三角 ↔ 暂停两条」变形图标。
 *
 * <p>为什么不用 {@code AnimatedVectorDrawable}：它的动画器是**就地改写**矢量路径数据的，
 * 而同一份矢量资源的新实例共享常量态（ConstantState），平台版的 {@code mutate()} 又不做
 * 真正的几何复制。结果就是上一次变形结束时的目标几何留在资源里，下一次点下去时先按这份
 * 残留几何画一帧——表现为「先瞬间切到目标形状、再回到起始形状、然后才正常变形」。
 * 换基图顺序、私有矢量副本、AnimatedVectorDrawableCompat + mutate、隐藏首帧、
 * 主动 invalidate、TransitionDrawable 交叉淡化……都只能减轻、不能根除。
 *
 * <p>这里干脆自己画：几何完全由 {@link #setMorph(float)} 决定，插值过程没有任何
 * 共享状态，也就不可能出现"闪一下"。线段端点与 drawable/vd_play_morph.xml、
 * vd_pause_morph.xml 完全一致（设计坐标 24×24，描边 2.6、圆头圆角）。
 */
public class MorphGlyphDrawable extends Drawable {

    /** 设计坐标（与两个 vd_*.xml 相同）。 */
    private static final float DESIGN = 24f;
    private static final float LEFT_X = 8f;
    private static final float TOP_Y = 5f;
    private static final float MID_Y = 12f;
    private static final float BOTTOM_Y = 19f;
    private static final float PLAY_TIP_X = 18.5f;
    private static final float PAUSE_X = 16f;
    private static final float STROKE = 2.6f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 0 = 播放三角，1 = 暂停两条。 */
    private float morph;

    public MorphGlyphDrawable(int color) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    /** 0 = 播放三角，1 = 暂停两条（中间值就是变形过程）。 */
    public void setMorph(float value) {
        float clamped = Math.max(0f, Math.min(1f, value));
        if (clamped == morph) return;
        morph = clamped;
        invalidateSelf();
    }

    /** 直接切到某个形状（不带动画）。 */
    public void setPlaying(boolean playing) {
        morph = playing ? 1f : 0f;
        invalidateSelf();
    }

    /** 当前形状对应"正在播放"还是"已暂停"（1 = 播放中显示暂停两条）。 */
    public boolean isPauseShape() {
        return morph > 0.5f;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) return;
        float size = Math.min(bounds.width(), bounds.height());
        float scale = size / DESIGN;
        float originX = bounds.left + (bounds.width() - size) / 2f;
        float originY = bounds.top + (bounds.height() - size) / 2f;
        paint.setStrokeWidth(STROKE * scale);

        // 上段：三角的斜边 (8,5)→(18.5,12) 变形到暂停的上半竖线 (16,5)→(16,12)
        float topStartX = lerp(LEFT_X, PAUSE_X, morph);
        float topEndX = lerp(PLAY_TIP_X, PAUSE_X, morph);
        // 下段：三角的斜边 (18.5,12)→(8,19) 变形到暂停的下半竖线 (16,12)→(16,19)
        float bottomEndX = lerp(LEFT_X, PAUSE_X, morph);

        canvas.drawLine(originX + topStartX * scale, originY + TOP_Y * scale,
                originX + topEndX * scale, originY + MID_Y * scale, paint);
        canvas.drawLine(originX + topEndX * scale, originY + MID_Y * scale,
                originX + bottomEndX * scale, originY + BOTTOM_Y * scale, paint);
        // 左竖线：两种形状都有
        canvas.drawLine(originX + LEFT_X * scale, originY + TOP_Y * scale,
                originX + LEFT_X * scale, originY + BOTTOM_Y * scale, paint);
    }

    private static float lerp(float from, float to, float progress) {
        return from + (to - from) * progress;
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
