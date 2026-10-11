package com.airmusic.player.ui;

import android.content.Context;
import android.graphics.Typeface;

import androidx.core.content.res.ResourcesCompat;

/**
 * 屏保可选字体：沿用参考项目 ClockDesk 自带的那套字体（已打包在 res/font 里），
 * 资源名与显示名一一对应，设置页的「字体」选项就是按这个列表渲染的。
 */
public final class ScreensaverFonts {

    public static final String[] RESOURCES = {
            "moven_akronim",
            "moven_lilitaone",
            "moven_lobster",
            "moven_monoton",
            "moven_neoneon",
    };

    public static final String[] LABELS = {
            "Akronim", "Lilita One", "Lobster", "Monoton", "Neon Neon",
    };

    /** 默认字体：Moven 的 Akronim（按用户指定）。 */
    public static final String DEFAULT = RESOURCES[0];

    private ScreensaverFonts() {
    }

    public static int indexOf(String resource) {
        for (int i = 0; i < RESOURCES.length; i++) {
            if (RESOURCES[i].equals(resource)) return i;
        }
        return 0;
    }

    /** 取字体；资源找不到时返回 null，调用方保持原字体。 */
    public static Typeface typeface(Context context, String resource) {
        String name = resource == null || resource.isEmpty() ? DEFAULT : resource;
        int id = context.getResources().getIdentifier(name, "font", context.getPackageName());
        if (id == 0) return null;
        try {
            return ResourcesCompat.getFont(context, id);
        } catch (Throwable t) {
            return null;
        }
    }
}
