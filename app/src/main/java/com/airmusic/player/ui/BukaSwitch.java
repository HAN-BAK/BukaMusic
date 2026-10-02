package com.airmusic.player.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 自绘开关（对齐 Android Canary 那种新样式）：整条圆角跑道 + 会变大变小的圆形滑块，
 * 打开时跑道用动态主色、滑块里带一个对勾；关闭时跑道是浅灰、滑块是白色小圆。
 *
 * <p>不用系统 Switch：原生样式和应用现在的对话框 / 按钮风格不一致，
 * 而且它的轨道颜色写死，跟不上封面取色。
 */
public class BukaSwitch extends View {

    /** 和 CompoundButton.OnCheckedChangeListener 一样的用法。 */
    public interface OnCheckedChangeListener {
        void onCheckedChanged(BukaSwitch view, boolean isChecked);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path check = new Path();

    private static final float WIDTH_DP = 52f;
    private static final float HEIGHT_DP = 32f;
    private static final float THUMB_OFF_DP = 10f;
    private static final float THUMB_ON_DP = 13f;
    private static final float PAD_DP = 2f;

    private boolean checked;
    private boolean enabled = true;
    private float fraction;           // 0 = 关，1 = 开（带动画）
    private int accent = 0xFF94B2E0;
    private OnCheckedChangeListener listener;
    private ValueAnimator animator;

    public BukaSwitch(Context context) {
        this(context, null);
    }

    public BukaSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(true);
        setFocusable(true);
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean value) {
        setChecked(value, false);
    }

    public void setChecked(boolean value, boolean animate) {
        if (checked == value && (fraction == (value ? 1f : 0f) || !animate)) {
            checked = value;
            fraction = value ? 1f : 0f;
            invalidate();
            return;
        }
        checked = value;
        if (animator != null) animator.cancel();
        if (!animate) {
            fraction = value ? 1f : 0f;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(fraction, value ? 1f : 0f);
        animator.setDuration(220L);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            fraction = (Float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        this.listener = listener;
    }

    /** 动态主色（打开时的跑道颜色）。 */
    public void setAccentColor(int color) {
        int opaque = 0xFF000000 | (color & 0xFFFFFF);
        if (opaque == accent) return;
        accent = opaque;
        invalidate();
    }

    @Override
    public void setEnabled(boolean value) {
        super.setEnabled(value);
        enabled = value;
        invalidate();
    }

    @Override
    public boolean performClick() {
        if (enabled) {
            setChecked(!checked, true);
            if (listener != null) listener.onCheckedChanged(this, checked);
        }
        return super.performClick();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(Math.round(dp(WIDTH_DP)), Math.round(dp(HEIGHT_DP)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;
        float radius = h / 2f;

        // 跑道：关 = 浅灰，开 = 动态主色
        int trackColor = blend(0xFF3A4350, accent, fraction);
        paint.setColor(enabled ? trackColor : Color.argb(
                0x80, Color.red(trackColor), Color.green(trackColor), Color.blue(trackColor)));
        rect.set(0f, 0f, w, h);
        canvas.drawRoundRect(rect, radius, radius, paint);

        // 滑块：垫圈大小随开关变化，位置从左边滑到右边
        float thumbRadius = dp(THUMB_OFF_DP + (THUMB_ON_DP - THUMB_OFF_DP) * fraction) / 2f;
        float pad = dp(PAD_DP);
        float cx = pad + thumbRadius + (w - 2f * (pad + thumbRadius)) * fraction;
        float cy = h / 2f;
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(cx, cy, thumbRadius, paint);

        // 打开时滑块里画个对勾
        if (fraction > 0.35f) {
            int alpha = (int) (255 * Math.min(1f, (fraction - 0.35f) / 0.4f));
            paint.setColor((alpha << 24) | (accent & 0xFFFFFF));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, thumbRadius * 0.22f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            check.reset();
            check.moveTo(cx - thumbRadius * 0.45f, cy + thumbRadius * 0.02f);
            check.lineTo(cx - thumbRadius * 0.10f, cy + thumbRadius * 0.38f);
            check.lineTo(cx + thumbRadius * 0.48f, cy - thumbRadius * 0.36f);
            canvas.drawPath(check, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private static int blend(int from, int to, float amount) {
        float a = Math.max(0f, Math.min(1f, amount));
        return Color.rgb(
                Math.round(Color.red(from) * (1 - a) + Color.red(to) * a),
                Math.round(Color.green(from) * (1 - a) + Color.green(to) * a),
                Math.round(Color.blue(from) * (1 - a) + Color.blue(to) * a));
    }
}
