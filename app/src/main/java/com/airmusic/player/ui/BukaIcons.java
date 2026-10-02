package com.airmusic.player.ui;

import android.content.res.ColorStateList;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
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

    /**
     * 同上，但把图标往上挪一点点。
     *
     * <p>图标挂在「标签 TextView」上时，compoundDrawable 是按行盒居中的，而行盒比字形
     * 低一点，于是图标看着比文字偏下；挂在按钮上的图标没有这个问题。
     * 所以只对标签那几行做这个小修正。
     */
    public static void rowAligned(TextView view, @DrawableRes int icon) {
        if (view == null) return;
        // MaterialButton 默认关掉了字体额外留白，图标才对得准；普通标签要保持一致
        view.setIncludeFontPadding(false);
        row(view, icon);
    }

    /**
     * 同上，并做一点微调：{@code shiftUpDp > 0} 表示图标上移，&lt;0 表示下移。
     *
     * <p>compoundDrawable 是按「行盒」居中的，不同行盒高度会让图标看着偏上/偏下，
     * 这里按行做几个像素的修正。
     */
    public static void rowAligned(TextView view, @DrawableRes int icon, float shiftUpDp) {
        if (view == null || shiftUpDp == 0f) {
            rowAligned(view, icon);
            return;
        }
        view.setIncludeFontPadding(false);
        float density = view.getResources().getDisplayMetrics().density;
        view.setCompoundDrawablePadding(Math.round(10f * density));
        android.graphics.drawable.Drawable drawable =
                androidx.core.content.ContextCompat.getDrawable(view.getContext(), icon);
        if (drawable == null) {
            row(view, icon);
            return;
        }
        int inset = Math.round(2f * Math.abs(shiftUpDp) * density);
        // 下侧留白 -> 图标相对行盒中心上移；上侧留白 -> 下移
        android.graphics.drawable.InsetDrawable wrapped = shiftUpDp > 0f
                ? new android.graphics.drawable.InsetDrawable(drawable, 0, 0, 0, inset)
                : new android.graphics.drawable.InsetDrawable(drawable, 0, inset, 0, 0);
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(wrapped, null, null, null);
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
