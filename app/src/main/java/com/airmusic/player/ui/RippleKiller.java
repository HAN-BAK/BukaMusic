package com.airmusic.player.ui;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.drawable.DrawableCompat;

import com.airmusic.player.R;

/**
 * 彻底关掉系统水波纹。
 *
 * <p>为什么只调 {@code MaterialButton.setRippleColor()} 不够：Material 1.12 里按钮的
 * 背景是 {@link LayerDrawable}，真正的 {@link RippleDrawable} 藏在层里，而
 * {@code MaterialButtonHelper.setRippleColor()} 只在「背景本身就是 RippleDrawable」
 * 时才把新颜色写进去——所以那句调用在新版材料库上是空操作，涟漪仍按样式里的
 * 颜色画出来（平板上漏出来的就是这个）。
 *
 * <p>这里做两件事：
 * <ol>
 *   <li>把背景 / 前景（含 LayerDrawable 的每一层、Material 的 RippleDrawableCompat）
 *       里所有涟漪层的颜色改成全透明，只保留内容层，视觉完全不变；</li>
 *   <li>给每个容器挂子视图监听，后加入的视图（列表行、动态创建的行、对话框内容）
 *       一加进来就同样处理。</li>
 * </ol>
 *
 * <p>注意必须在「按封面主色上色」之后调用：{@code setBackgroundTintList()} 会让
 * MaterialButton 重建背景，重建出来的背景又把涟漪层带回来了。
 */
public final class RippleKiller {

    private static final String TAG = "BukaRipple";
    private static final ColorStateList TRANSPARENT =
            ColorStateList.valueOf(Color.TRANSPARENT);
    private static ColorStateList killColor() {
        return TRANSPARENT;
    }

    private RippleKiller() {
    }

    /** 处理整棵视图树（含后加入视图）。可重复调用。 */
    public static void kill(View root) {
        if (root == null) return;
        killOne(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            guard(group);
            for (int i = 0; i < group.getChildCount(); i++) {
                kill(group.getChildAt(i));
            }
        }
    }

    /** 容器里以后动态加进来的视图也自动处理。 */
    private static void guard(ViewGroup group) {
        if (Boolean.TRUE.equals(group.getTag(R.id.ripple_guard_tag))) return;
        group.setTag(R.id.ripple_guard_tag, Boolean.TRUE);
        group.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                kill(child);
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
            }
        });
    }

    private static void killOne(View view) {
        // 这里跑在布局过程中（RecyclerView 填充行会走 onChildViewAdded），
        // 任何异常都会直接把应用带崩，所以单个视图出问题只记日志、跳过。
        try {
            killOneOrThrow(view);
        } catch (Throwable t) {
            Log.w(TAG, "去涟漪失败，已跳过：" + idName(view), t);
        }
    }

    private static void killOneOrThrow(View view) {
        boolean touched = false;
        if (view instanceof com.google.android.material.button.MaterialButton) {
            ((com.google.android.material.button.MaterialButton) view)
                    .setRippleColor(killColor());
            touched = true;
        } else if (view instanceof com.google.android.material.card.MaterialCardView) {
            ((com.google.android.material.card.MaterialCardView) view)
                    .setRippleColor(killColor());
            touched = true;
        }
        Drawable background = view.getBackground();
        boolean bgChanged = neutralize(background);
        Drawable foreground = view.getForeground();
        boolean fgChanged = neutralize(foreground);
        if (bgChanged || fgChanged || touched) {
            view.invalidate();
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "ripple cleared id=" + idName(view) + " class="
                        + view.getClass().getSimpleName() + " bg=" + (bgChanged ? bgClass(background) : "-")
                        + " fg=" + (fgChanged ? bgClass(foreground) : "-"));
            }
        }
    }

    /**
     * 把 drawable 里的涟漪层改成全透明（保留内容层和层级结构）。
     *
     * @return 是否真的动过这个 drawable
     */
    private static boolean neutralize(Drawable drawable) {
        if (drawable == null) return false;
        if (drawable instanceof RippleDrawable) {
            RippleDrawable ripple = (RippleDrawable) drawable;
            ripple.setColor(killColor());
            // 内容层可能不存在（有些涟漪是「只有遮罩、没有内容」的空层），
            // 直接 getDrawable(0) 会 IndexOutOfBounds。
            if (ripple.getNumberOfLayers() > 0) {
                // 内容层里还可能套着别的涟漪（罕见），一并处理。
                neutralize(ripple.getDrawable(0));
            }
            return true;
        }
        if (drawable instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) drawable;
            boolean changed = false;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                if (neutralize(layers.getDrawable(i))) changed = true;
            }
            return changed;
        }
        if (drawable instanceof com.google.android.material.ripple.RippleDrawableCompat) {
            // 材料库给低版本准备的自绘涟漪
            DrawableCompat.setTintList(drawable, killColor());
            return true;
        }
        // 涟漪也可能被 InsetDrawable / DrawableWrapper 包着（材料的
        // wrapDrawableWithInset、以及各种 LayerDrawable 嵌套），必须拆开看，
        // 否则会整层漏掉。
        Drawable wrapped = unwrap(drawable);
        if (wrapped != null && wrapped != drawable) {
            return neutralize(wrapped);
        }
        return false;
    }

    private static Drawable unwrap(Drawable drawable) {
        // 最常见的一层包装：InsetDrawable（材料库给按钮加内缩用的就是它）
        if (drawable instanceof android.graphics.drawable.InsetDrawable) {
            return ((android.graphics.drawable.InsetDrawable) drawable).getDrawable();
        }
        // 平台的 DrawableWrapper 是隐藏类，androidx 的版本各版本名称不稳，
        // 统一用反射取内层。
        for (String method : new String[]{"getWrappedDrawable", "getDrawable"}) {
            try {
                Object inner = drawable.getClass().getMethod(method).invoke(drawable);
                if (inner instanceof Drawable && inner != drawable) {
                    return (Drawable) inner;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String bgClass(Drawable drawable) {
        return drawable == null ? "null" : drawable.getClass().getSimpleName();
    }

    private static String idName(View view) {
        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Throwable ignored) {
            return "#" + Integer.toHexString(view.getId());
        }
    }
}
