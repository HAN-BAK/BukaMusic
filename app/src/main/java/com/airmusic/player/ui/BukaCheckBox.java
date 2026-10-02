package com.airmusic.player.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 自绘勾选框（Material 3 / Canary 观感）：圆角方框 + 粗对勾。
 *
 * <p>未选中是浅灰描边，选中后整块填充当前封面主色、对勾用白色，带一小段
 * 勾选动画。整行的点击由 RecyclerView 的行处理，这个控件本身不接收点击。
 */
public class BukaCheckBox extends View {

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path checkPath = new Path();
    private final RectF box = new RectF();

    private boolean checked;
    private float progress;
    private float checkLength;
    private ValueAnimator animator;
    private int accent = 0xFF4FC3F7;
    private int idle = 0xFF9AA6B4;

    public BukaCheckBox(Context context) {
        this(context, null);
    }

    public BukaCheckBox(Context context, AttributeSet attrs) {
        super(context, attrs);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        checkPaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStrokeCap(Paint.Cap.ROUND);
        checkPaint.setStrokeJoin(Paint.Join.ROUND);
        checkPaint.setColor(Color.WHITE);
        accent = ColorTheme.accent();
        idle = ColorTheme.textSecondary();
        setClickable(false);
        setFocusable(false);
    }

    /** 跟着封面主色走。 */
    public void setAccentColor(int color) {
        if (accent == color) return;
        accent = color;
        invalidate();
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean value) {
        setChecked(value, isAttachedToWindow());
    }

    public void setChecked(boolean value, boolean animate) {
        if (checked == value && (value ? progress >= 1f : progress <= 0f)) {
            checked = value;
            return;
        }
        checked = value;
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        float target = value ? 1f : 0f;
        if (!animate || !isAttachedToWindow()) {
            progress = target;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(170L);
        animator.setInterpolator(new DecelerateInterpolator(1.6f));
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float size = Math.min(w, h);
        // 描边厚一点、框更「鼓」，贴近 Canary 那种厚重的手感
        float stroke = Math.max(dp(2.6f), size * 0.10f);
        strokePaint.setStrokeWidth(stroke);
        checkPaint.setStrokeWidth(Math.max(dp(2.4f), size * 0.125f));
        float inset = stroke / 2f + size * 0.03f;
        box.set(inset, inset, size - inset, size - inset);
        // 对勾：左下 -> 底 -> 右上，用 PathMeasure 做「画出来」的动画
        checkPath.reset();
        checkPath.moveTo(size * 0.28f, size * 0.52f);
        checkPath.lineTo(size * 0.43f, size * 0.67f);
        checkPath.lineTo(size * 0.73f, size * 0.34f);
        checkLength = new PathMeasure(checkPath, false).getLength();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        float radius = size * 0.30f;
        float eased = progress * progress * (3f - 2f * progress);

        // 底色：未选中只有描边，选中后填主色
        int fillColor = Color.argb(Math.round(255 * eased),
                Color.red(accent), Color.green(accent), Color.blue(accent));
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(fillColor);
        canvas.drawRoundRect(box, radius, radius, fillPaint);

        int strokeColor = blendColor(idle, accent, eased);
        strokePaint.setColor(strokeColor);
        canvas.drawRoundRect(box, radius, radius, strokePaint);

        if (eased > 0.02f) {
            // 对勾在底色铺开一点之后再画
            float drawn = Math.max(0f, Math.min(1f, (eased - 0.25f) / 0.75f));
            checkPaint.setColor(Color.argb(Math.round(255 * drawn),
                    Color.red(Color.WHITE), Color.green(Color.WHITE), Color.blue(Color.WHITE)));
            android.graphics.Path partial = new android.graphics.Path();
            new PathMeasure(checkPath, false).getSegment(0f, checkLength * drawn, partial, true);
            canvas.drawPath(partial, checkPaint);
        }
    }

    private static int blendColor(int from, int to, float amount) {
        float a = Math.max(0f, Math.min(1f, amount));
        return Color.argb(
                Math.round(Color.alpha(from) * (1 - a) + Color.alpha(to) * a),
                Math.round(Color.red(from) * (1 - a) + Color.red(to) * a),
                Math.round(Color.green(from) * (1 - a) + Color.green(to) * a),
                Math.round(Color.blue(from) * (1 - a) + Color.blue(to) * a));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
