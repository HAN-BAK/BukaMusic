package com.airmusic.player.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 自绘加载转圈：一段带「扫光」渐变的圆环在旋转（尾端渐隐、头部圆头），
 * 比系统 ProgressBar 那根细弧线好看，颜色也跟当前主色走。
 *
 * <p>显示/隐藏沿用原来的 setVisibility 调用，控件自己会在可见时开始转、隐藏时停止。
 */
public class BukaSpinner extends View {

    private static final float SWEEP_DEGREES = 300f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int color = 0xFFCFE2F5;
    private float rotation;
    private ValueAnimator animator;
    private SweepGradient gradient;
    private int gradientColor = 0;

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
        gradient = null;
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
        float stroke = Math.max(2f, Math.min(w, h) * 0.11f);
        float inset = stroke / 2f;
        rect.set(inset, inset, w - inset, h - inset);
        paint.setStrokeWidth(stroke);
        if (gradient == null || gradientColor != color) {
            gradientColor = color;
            gradient = new SweepGradient(w / 2f, h / 2f,
                    new int[]{color, Color.TRANSPARENT},
                    new float[]{0f, 0.85f});
        }
        paint.setShader(gradient);
        canvas.save();
        canvas.rotate(rotation, w / 2f, h / 2f);
        canvas.drawArc(rect, 0f, SWEEP_DEGREES, false, paint);
        canvas.restore();
        paint.setShader(null);
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
