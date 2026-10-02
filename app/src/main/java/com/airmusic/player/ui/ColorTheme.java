package com.airmusic.player.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态配色：从当前专辑封面里取一个主色，降饱和后作为按钮/滑块的强调色。
 *
 * <p>取值流程：缩到 24×24 → 逐像素转 HSV → 丢掉太灰、太黑、太亮的像素 →
 * 按色相分桶，取权重最高的一桶（权重 = 饱和度 × 明度 × 数量）→ 得到一个
 * 平均色 → **把饱和度压到 0.42 以内、明度限制在 0.58~0.78**，保证柔和不刺眼。
 *
 * <p>页面通过 {@link #addListener} 订阅，换歌或换封面后自动重新上色。
 */
public final class ColorTheme {

    /** 默认强调色（没有封面时用）：偏白的浅蓝。 */
    private static final int DEFAULT_ACCENT = 0xFFCFE2F5;

    /**
     * 偏白的彩色：保留封面的色相，饱和度收到中等偏低、明度提到很高，
     * 得到浅彩（粉彩）色调 —— 一看就是「偏白的那个颜色」，而不是灰白，
     * 也不会是高饱和的艳色。
     */
    private static final float MIN_SATURATION = 0.26f;
    private static final float MAX_SATURATION = 0.34f;
    private static final float MIN_VALUE = 0.88f;
    private static final float MAX_VALUE = 0.94f;

    private static int accent = DEFAULT_ACCENT;
    private static Bitmap source;
    private static final List<Listener> listeners = new ArrayList<>();

    public interface Listener {
        void onPaletteChanged();
    }

    private ColorTheme() {
    }

    public static int accent() {
        return accent;
    }

    /** 按钮底色（主色 20%）。 */
    public static int fill() {
        return withAlpha(accent, 0.20f);
    }

    /** 按钮描边（主色 50%）。 */
    public static int stroke() {
        return withAlpha(accent, 0.50f);
    }

    /** 更淡的底（主色 12%），用于次级块。 */
    public static int fillSoft() {
        return withAlpha(accent, 0.12f);
    }

    public static int withAlpha(int color, float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    /**
     * 换封面时调用；同一张封面不会重复计算。
     * 传 null（没有封面）时回到默认天蓝。
     */
    public static void update(Bitmap art) {
        if (art == source) return;
        source = art;
        int next = art == null ? DEFAULT_ACCENT : extract(art);
        if (next == accent) return;
        accent = next;
        android.util.Log.i("ColorTheme", String.format(
                "封面主色 → #%06X（饱和 %.2f）", accent & 0xFFFFFF, saturation(accent)));
        notifyChanged();
    }

    private static float saturation(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        return hsv[1];
    }

    public static void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private static void notifyChanged() {
        // 复制一份，回调里可能会解绑。
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onPaletteChanged();
        }
    }

    /** 从封面里取主色并做「低饱和」处理。 */
    private static int extract(Bitmap art) {
        try {
            final int size = 24;
            Bitmap small = Bitmap.createScaledBitmap(art, size, size, true);
            float[] hsv = new float[3];
            float[] weight = new float[24];
            float[] sumR = new float[24];
            float[] sumG = new float[24];
            float[] sumB = new float[24];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int color = small.getPixel(x, y);
                    Color.colorToHSV(color, hsv);
                    if (hsv[1] < 0.15f || hsv[2] < 0.15f || hsv[2] > 0.96f) continue;
                    int bucket = Math.min(23, (int) (hsv[0] / 15f));
                    float w = hsv[1] * hsv[2];
                    weight[bucket] += w;
                    sumR[bucket] += Color.red(color) * w;
                    sumG[bucket] += Color.green(color) * w;
                    sumB[bucket] += Color.blue(color) * w;
                }
            }
            int best = -1;
            for (int i = 0; i < 24; i++) {
                if (weight[i] <= 0f) continue;
                if (best < 0 || weight[i] > weight[best]) best = i;
            }
            if (best < 0) return DEFAULT_ACCENT;
            int r = Math.round(sumR[best] / weight[best]);
            int g = Math.round(sumG[best] / weight[best]);
            int b = Math.round(sumB[best] / weight[best]);
            if (small != art) small.recycle();
            return mute(Color.rgb(r, g, b));
        } catch (Throwable ignored) {
            return DEFAULT_ACCENT;
        }
    }

    /** 压低饱和度与明度范围，避免高饱和的艳色。 */
    private static int mute(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        // 饱和度落在浅彩区间（太低会变灰白，太高会刺眼）。
        hsv[1] = Math.max(MIN_SATURATION, Math.min(hsv[1], MAX_SATURATION));
        hsv[2] = Math.max(MIN_VALUE, Math.min(MAX_VALUE, hsv[2]));
        return Color.HSVToColor(hsv);
    }

    // ------------------------------------------------------------------
    // 运行时背景（按钮/滑块用动态色，布局里的固定 drawable 只留形状）
    // ------------------------------------------------------------------

    public static GradientDrawable capsule(Context context) {
        return shape(context, fill(), stroke(), false);
    }

    public static GradientDrawable circle(Context context) {
        return shape(context, fill(), stroke(), true);
    }

    public static GradientDrawable softBlock(Context context) {
        return shape(context, fillSoft(), Color.TRANSPARENT, false, 16f);
    }

    /** 确认类按钮：底色稍重一点。 */
    public static GradientDrawable capsulePrimary(Context context) {
        return shape(context, withAlpha(accent, 0.34f), withAlpha(accent, 0.62f), false);
    }

    /** 实心圆形（播放键）：主色实心 + 深一点的描边。 */
    public static GradientDrawable solidCircle(Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(accent);
        return drawable;
    }

    private static GradientDrawable shape(Context context, int fillColor, int strokeColor,
                                         boolean circle) {
        return shape(context, fillColor, strokeColor, circle, 14f);
    }

    private static GradientDrawable shape(Context context, int fillColor, int strokeColor,
                                          boolean circle, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(circle ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        if (!circle) {
            drawable.setCornerRadius(dp(context, radiusDp));
        }
        if (Color.alpha(strokeColor) > 0) {
            drawable.setStroke(Math.max(1, Math.round(dp(context, 1f))), strokeColor);
        }
        return drawable;
    }

    public static int dp(Context context, float value) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return Math.round(value * metrics.density);
    }
}
