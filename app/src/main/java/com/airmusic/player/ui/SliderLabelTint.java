package com.airmusic.player.ui;

import android.content.res.ColorStateList;
import android.util.Log;
import android.view.View;

import com.google.android.material.resources.TextAppearance;
import com.google.android.material.tooltip.TooltipDrawable;

import java.lang.reflect.Field;
import java.util.List;

/**
 * 给 Material 滑条的拖动气泡（TooltipDrawable）上色。
 *
 * <p>Material 1.12 的 BaseSlider（包内可见，不能直接写类型）把气泡实例放在私有字段 {@code labels}
 * 里（构造时由 {@code createLabelPool()} 建好，之后一直复用），公开 API 只允许
 * 换文字、不允许换颜色，所以这里用反射拿到这些实例：
 * 底色 → {@code setFillColor}，文字 → {@code TextAppearance.setTextColor}。
 * 颜色在每次 {@code ColorTheme} 变化后重新写一遍，于是换歌换封面时气泡跟着变色。
 */
public final class SliderLabelTint {

    private static final String TAG = "SliderLabelTint";
    private static Field labelsField;
    private static boolean reflectBroken;

    private SliderLabelTint() {
    }

    /** 用当前主色给气泡上色（重复调用没有副作用）。 */
    public static void apply(View slider) {
        if (slider == null || reflectBroken) return;
        List<?> labels = labels(slider);
        if (labels == null) return;
        int fill = ColorTheme.tooltipFill();
        int text = ColorTheme.tooltipText();
        for (Object item : labels) {
            if (!(item instanceof TooltipDrawable)) continue;
            TooltipDrawable tooltip = (TooltipDrawable) item;
            tooltip.setFillColor(ColorStateList.valueOf(fill));
            TextAppearance appearance = tooltip.getTextAppearance();
            if (appearance != null) {
                // 气泡在 draw 前会刷一遍文字画笔，改完这个就够了。
                appearance.setTextColor(ColorStateList.valueOf(text));
            }
            tooltip.invalidateSelf();
        }
    }

    private static List<?> labels(View slider) {
        try {
            Field field = labelsField;
            if (field == null) {
                field = findLabelsField(slider.getClass());
                if (field == null) {
                    reflectBroken = true;
                    Log.w(TAG, "找不到气泡字段，退回 Material 默认配色");
                    return null;
                }
                field.setAccessible(true);
                labelsField = field;
            }
            Object value = field.get(slider);
            return value instanceof List ? (List<?>) value : null;
        } catch (Throwable t) {
            reflectBroken = true;
            Log.w(TAG, "气泡上色失败，退回 Material 默认配色", t);
            return null;
        }
    }

    private static Field findLabelsField(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField("labels");
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            }
        }
        return null;
    }
}
