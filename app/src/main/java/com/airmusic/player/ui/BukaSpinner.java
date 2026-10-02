package com.airmusic.player.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 自绘加载转圈：一圈淡淡的主色底环 + 一段会「呼吸」（长度 70°↔160°）的亮弧匀速旋转。
 *
 * <p>细描边、圆头、纯色（不用渐变），是现在常见的那种极简加载指示器。
 * 显示/隐藏沿用原来的 setVisibility 调用：可见时转、隐藏时停。
 */
public class BukaSpinner extends View {

    private static final float MIN_SWEEP_DEGREES = 70f;
    private static final float MAX_SWEEP_DEGREES = 160f;
    private static final long SWEEP_PERIOD_MS = 1500L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int color = 0xFFCFE2F5;
    private float rotation;
    private ValueAnimator animator;

    public BukaSpinner(Context context) {
        this(context, null);
    }

    public BukaSpinner(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** 颜色跟随动态主色。 */
    public void setSpinnerColor(int value) {
        int opaque = 0xFF000000 | (value & 0xFFFFFF);
        if (opaque == color) return;
        color = opaque;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // 自己或任意祖先被隐藏时不转；重新显示时自动续上
        if (!isShown()) {
            stop();
            return;
        }
        if (animator == null || !animator.isRunning()) start();
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;
        float stroke = Math.max(getResources().getDisplayMetrics().density * 1.6f,
                Math.min(w, h) * 0.085f);
        float inset = stroke / 2f;
        rect.set(inset, inset, w - inset, h - inset);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);

        // 底环：主色压到很淡，交代出「一圈」
        paint.setColor(Color.argb(0x2E, Color.red(color), Color.green(color), Color.blue(color)));
        canvas.drawCircle(w / 2f, h / 2f, (w - stroke) / 2f, paint);

        // 亮弧：长度随时间缓慢呼吸，整体匀速转
        float phase = (android.os.SystemClock.uptimeMillis() % SWEEP_PERIOD_MS)
                / (float) SWEEP_PERIOD_MS;
        float sweep = MIN_SWEEP_DEGREES + (MAX_SWEEP_DEGREES - MIN_SWEEP_DEGREES)
                * (0.5f - 0.5f * (float) Math.cos(2 * Math.PI * phase));
        paint.setColor(color);
        canvas.save();
        canvas.rotate(rotation, w / 2f, h / 2f);
        canvas.drawArc(rect, 0f, sweep, false, paint);
        canvas.restore();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) start(); else stop();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE) start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    private void start() {
        if (animator != null && animator.isRunning()) return;
        animator = ValueAnimator.ofFloat(0f, 360f);
        animator.setDuration(1100L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            rotation = (Float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }
}
