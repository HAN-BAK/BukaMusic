package com.airmusic.player.ui;

import android.content.res.ColorStateList;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;

import com.airmusic.player.R;

/**
 * 图标挂载工具：把线性图标放到行的标签 / 按钮左侧（compound drawable），
 * 颜色跟随当前动态主色；动态图标（AnimatedVectorDrawable）点击时自动播放。
 */
public final class BukaIcons {

    private BukaIcons() {
    }

    /** 给 TextView / Button 左侧加图标（颜色走动态主色）。 */
    public static void row(TextView view, @DrawableRes int icon) {
        if (view == null) return;
        int gap = Math.round(10f * view.getResources().getDisplayMetrics().density);
        view.setCompoundDrawablePadding(gap);
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        tint(view);
    }

    /** 动态图标：挂上后由调用方在点击处理里调用 {@link #play} 播放动画。 */
    public static void animated(TextView view, @DrawableRes int animRes) {
        if (view == null) return;
        row(view, animRes);
    }

    /** 播放某个 TextView 左侧的动画图标（如果它是 AnimatedVectorDrawable）。 */
    public static void play(TextView view) {
        if (view == null) return;
        for (Drawable part : view.getCompoundDrawablesRelative()) {
            if (part instanceof Animatable) {
                ((Animatable) part).start();
                break;
            }
        }
    }

    /**
     * 给「标签 + 控件」这种行的标签加图标：控件所在的容器里第一个纯文本 TextView
     * 就是标签（开关、按钮、输入框都不算）。
     */
    public static void rowLabel(View control, @DrawableRes int icon) {
        if (control == null || !(control.getParent() instanceof ViewGroup)) return;
        ViewGroup row = (ViewGroup) control.getParent();
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            if (child instanceof android.widget.Button) continue;
            if (child instanceof android.widget.Switch) continue;
            if (child instanceof android.widget.EditText) continue;
            row((TextView) child, icon);
            return;
        }
    }

    /**
     * 把行左侧的图标做成**独立 ImageView**（插在标签前面），而不是 compoundDrawable。
     *
     * <p>这样图标和文字各自定位、互不牵动：图标在行里垂直居中，需要精调时直接给
     * 返回的 ImageView 设 translationY 即可（{@code iconUpDp} 正值 = 图标上移）。
     */
    public static android.widget.ImageView attach(TextView label, @DrawableRes int icon,
                                                  float iconUpDp) {
        if (label == null || !(label.getParent() instanceof ViewGroup)) return null;
        ViewGroup row = (ViewGroup) label.getParent();
        float density = label.getResources().getDisplayMetrics().density;
        android.widget.ImageView view = new android.widget.ImageView(label.getContext());
        view.setImageResource(icon);
        view.setImageTintList(ColorStateList.valueOf(ColorTheme.accent()));
        int size = Math.round(24f * density);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.gravity = android.view.Gravity.CENTER_VERTICAL;
        view.setLayoutParams(lp);
        int index = row.indexOfChild(label);
        row.addView(view, index);
        // 图标与文字之间的间距
        ViewGroup.LayoutParams raw = label.getLayoutParams();
        if (raw instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams labelParams = (LinearLayout.LayoutParams) raw;
            labelParams.setMarginStart(Math.round(10f * density));
            label.setLayoutParams(labelParams);
        }
        if (iconUpDp != 0f) {
            view.setTranslationY(-iconUpDp * density);
        }
        return view;
    }

    /** ImageView 版本（底栏、工具按钮）。 */
    public static void view(ImageView view, @DrawableRes int icon, boolean playOnClick) {
        if (view == null) return;
        view.setImageResource(icon);
        view.setImageTintList(ColorStateList.valueOf(ColorTheme.accent()));
        if (playOnClick) {
            view.setOnClickListener(v -> {
                Drawable drawable = ((ImageView) v).getDrawable();
                if (drawable instanceof Animatable) ((Animatable) drawable).start();
            });
        }
    }

    private static void tint(TextView view) {
        // 图标与当前主色保持一致（compound drawable 的 tint 需要 API 23+）。
        view.setCompoundDrawableTintList(ColorStateList.valueOf(ColorTheme.accent()));
    }
}
