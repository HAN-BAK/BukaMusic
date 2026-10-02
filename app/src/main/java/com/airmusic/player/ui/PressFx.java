package com.airmusic.player.ui;

import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.airmusic.player.R;

/**
 * 按压动效：按下轻微缩小 + 变暗，松手带一点回弹恢复。
 *
 * <p>应用里早就关掉了系统的水波纹（{@code colorControlHighlight} 设为透明），
 * 所以按钮反馈统一走这套缩放，和专辑卡片的手感保持一致。
 */
public final class PressFx {

    private static final float DEFAULT_SCALE = 0.94f;
    private static final float DEFAULT_ALPHA = 0.82f;

    private PressFx() {
    }

    public static void attach(View view) {
        attach(view, DEFAULT_SCALE);
    }

    public static void attach(View view, float pressedScale) {
        if (view == null || view.getTag(R.id.press_fx_tag) != null) return;
        view.setTag(R.id.press_fx_tag, Boolean.TRUE);
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    press(v, pressedScale);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    release(v);
                    break;
                default:
                    break;
            }
            // 不消费事件：点击、长按等原有逻辑照旧。
            return false;
        });
    }

    /**
     * 触摸 {@code touchTarget}（比如滑条）时缩放 {@code scaleTarget}
     * （比如滑条所在的那一层轨道容器）。滑条自己要处理拖动，所以做成
     * 「监听触摸但不动缩放对象本身」，不会影响拖动。
     */
    public static void attachScaled(View touchTarget, View scaleTarget, float pressedScale) {
        if (touchTarget == null || scaleTarget == null) return;
        if (touchTarget.getTag(R.id.press_fx_tag) != null) return;
        touchTarget.setTag(R.id.press_fx_tag, Boolean.TRUE);
        touchTarget.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    scalePress(scaleTarget, pressedScale);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    scaleRelease(scaleTarget);
                    break;
                default:
                    break;
            }
            return false; // 不消费事件，拖动照旧
        });
    }

    private static void scalePress(View v, float scale) {
        v.animate().cancel();
        v.animate()
                .scaleX(scale).scaleY(scale)
                .setDuration(90L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private static void scaleRelease(View v) {
        v.animate().cancel();
        v.animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(180L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private static void press(View v, float scale) {
        v.animate().cancel();
        // 图标本身是 AnimatedVectorDrawable 的话，按下时顺手播放一次动画
        // （底栏的曲库 / 应用 / 多房间 / 设置都是这种）。
        if (v instanceof android.widget.ImageView) {
            android.graphics.drawable.Drawable drawable =
                    ((android.widget.ImageView) v).getDrawable();
            if (drawable instanceof android.graphics.drawable.Animatable) {
                ((android.graphics.drawable.Animatable) drawable).start();
                android.util.Log.d("PressFx", "图标动效播放 #" + Integer.toHexString(v.getId()));
            }
        }
        v.animate()
                .scaleX(scale).scaleY(scale)
                .alpha(DEFAULT_ALPHA)
                .setDuration(90L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private static void release(View v) {
        v.animate().cancel();
        v.animate()
                .scaleX(1f).scaleY(1f)
                .alpha(1f)
                .setDuration(180L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /** 让某个视图回到正常状态（列表回收复用前调用）。 */
    public static void reset(View v) {
        if (v == null) return;
        v.animate().cancel();
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setAlpha(1f);
    }
}
