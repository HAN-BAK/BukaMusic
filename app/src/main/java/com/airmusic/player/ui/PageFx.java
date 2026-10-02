package com.airmusic.player.ui;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import com.airmusic.player.R;

/**
 * 页面动效：进入页面时内容分层淡入（顶栏自上而下、主体自下而上），
 * 并给页面里所有可点击控件挂上按压动效。
 */
public final class PageFx {

    private static final String TAG_ANIMATED = "buka_page_animated";

    private PageFx() {
    }

    /** 给整棵视图树里的可点击控件加按压动效（按 id 去重，可重复调用）。 */
    public static void attachPress(View root) {
        if (root == null) return;
        applyButtonOutline(root);
        // 输入框、进度条/滑杆有自己的触摸语义，加缩放会干扰，跳过。
        boolean skip = root instanceof android.widget.EditText
                || root instanceof android.widget.SeekBar
                || root instanceof android.widget.ProgressBar;
        if (root.isClickable() && !skip) {
            PressFx.attach(root);
        }
        if (!(root instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            attachPress(group.getChildAt(i));
        }
    }

    /**
     * 图标按钮（返回、上一曲/下一曲、底栏各项…）原本背景是透明的，这里统一
     * 套上与对话框「已选中选项」一致的外观：主色淡填充 + 主色描边 + 圆角。
     * 只处理 ImageButton 这类图标按钮，列表行不受影响。
     */
    private static void applyButtonOutline(View view) {
        if (!(view instanceof android.widget.ImageButton)) return;
        if (!view.isClickable()) return;
        android.graphics.drawable.Drawable background = view.getBackground();
        boolean plain = background == null
                || (background instanceof android.graphics.drawable.ColorDrawable
                    && ((android.graphics.drawable.ColorDrawable) background).getColor() == 0);
        if (!plain) return;
        view.setBackgroundResource(R.drawable.bg_btn_outline);
        // 图标与描边之间留一点呼吸空间。
        int pad = Math.round(8f * view.getResources().getDisplayMetrics().density);
        view.setPadding(pad, pad, pad, pad);
    }

    /**
     * 页面入场：把直接子视图按顺序做淡入 + 位移，顶部一条从上方落下，
     * 其余内容从下方 24dp 处上浮。
     */
    public static void enter(View root) {
        if (root == null) return;
        if (TAG_ANIMATED.equals(root.getTag())) return;
        root.setTag(TAG_ANIMATED);
        // 页面外面可能套着 BoxAspectFrameLayout 之类的单子容器，往里走两层，
        // 找到真正分层的那个根布局再逐层入场。
        View target = root;
        for (int depth = 0; depth < 3; depth++) {
            if (!(target instanceof ViewGroup)) break;
            ViewGroup group = (ViewGroup) target;
            if (group.getChildCount() != 1) break;
            target = group.getChildAt(0);
        }
        if (!(target instanceof ViewGroup)) {
            fadeUp(target, 0L);
            return;
        }
        ViewGroup group = (ViewGroup) target;
        float shift = group.getResources().getDisplayMetrics().density * 24f;
        int animated = 0;
        for (int i = 0; i < group.getChildCount() && animated < 6; i++) {
            View child = group.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE) continue;
            boolean topBar = i == 0;
            child.setAlpha(0f);
            child.setTranslationY(topBar ? -shift * 0.5f : shift);
            child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(animated * 45L)
                    .setDuration(260L)
                    .setInterpolator(new DecelerateInterpolator(1.4f))
                    .start();
            animated++;
        }
    }

    private static void fadeUp(View view, long delay) {
        view.setAlpha(0f);
        view.animate().alpha(1f).setStartDelay(delay).setDuration(220L).start();
    }

    /** 便捷入口：页面创建后调用，入场 + 挂按压动效。 */
    public static void apply(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        enter(content);
        // 控件监听器通常在 onCreate 里才设置，所以延后一帧再挂按压动效。
        content.post(() -> {
            attachPress(content);
            BukaTheme.spaceButtons(activity);
        });
    }
}
