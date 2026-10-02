package com.airmusic.player.ui;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.airmusic.player.R;

/**
 * 自己写的提示条，替代系统 Toast：圆角胶囊、应用配色、底部滑入淡出。
 */
public final class BukaNotice {

    public static final int SHORT = 1700;
    public static final int LONG = 3000;

    private static final String TAG = "buka_notice";
    private static final String TAG_ANIMATION = "buka_notice_anim";

    private BukaNotice() {
    }

    public static void show(Activity activity, int textRes) {
        if (activity == null) return;
        show(activity, activity.getString(textRes), SHORT);
    }

    public static void show(Activity activity, int textRes, int durationMs) {
        if (activity == null) return;
        show(activity, activity.getString(textRes), durationMs);
    }

    public static void show(Activity activity, CharSequence text) {
        show(activity, text, SHORT);
    }

    public static void show(Activity activity, CharSequence text, int durationMs) {
        if (activity == null || text == null || text.length() == 0) return;
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null || activity.isFinishing()) return;

        // 同一时间只留一条：旧的直接移除，新的滑入。
        View previous = root.findViewWithTag(TAG);
        if (previous != null) {
            previous.animate().cancel();
            root.removeView(previous);
        }

        TextView notice = new TextView(activity);
        notice.setText(text);
        notice.setTag(TAG);
        notice.setTextSize(14f);
        notice.setTextColor(activity.getResources().getColor(R.color.text_primary));
        notice.setBackgroundResource(R.drawable.bg_notice);
        int padH = dp(activity, 20f);
        int padV = dp(activity, 12f);
        notice.setPadding(padH, padV, padH, padV);
        notice.setMaxLines(3);
        notice.setGravity(Gravity.CENTER);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = dp(activity, 96f);
        int side = dp(activity, 24f);
        params.leftMargin = side;
        params.rightMargin = side;
        root.addView(notice, params);

        float shift = dp(activity, 36f);
        notice.setAlpha(0f);
        notice.setTranslationY(shift);
        notice.animate()
                .alpha(1f).translationY(0f)
                .setDuration(220L)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .withEndAction(() -> {
                    notice.postDelayed(() -> fadeOut(root, notice), Math.max(400, durationMs - 220));
                })
                .start();
    }

    private static void fadeOut(ViewGroup root, TextView notice) {
        if (root == null || notice == null || notice.getParent() == null) return;
        if (TAG_ANIMATION.equals(notice.getTag(R.id.press_fx_tag))) return;
        notice.setTag(R.id.press_fx_tag, TAG_ANIMATION);
        notice.animate()
                .alpha(0f)
                .translationY(notice.getTranslationY() + 16f)
                .setDuration(200L)
                .withEndAction(() -> {
                    notice.animate().setListener(null);
                    root.removeView(notice);
                })
                .start();
    }

    private static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
