package com.airmusic.player.ui;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

import com.airmusic.player.R;

/**
 * 全应用统一到自定义对话框那套外观：半透深蓝圆角卡片 + 主色描边。
 *
 * <p>页面容器、列表行、分组标题都用这里的几个方法套样式，避免每个布局文件
 * 各写一套背景。
 */
public final class BukaTheme {

    private BukaTheme() {
    }

    /**
     * 播放界面的按钮用圆形边框（同一套主色填充 + 描边）。
     * 这里先设好背景，PageFx 的通用描边逻辑看到已有背景就会跳过，不会覆盖成圆角方形。
     */
    public static void circleButtons(Activity activity, int... viewIds) {
        for (int id : viewIds) {
            View view = activity.findViewById(id);
            if (view == null) continue;
            view.setTag(R.id.btn_shape_tag, Boolean.TRUE);
            view.setBackground(ColorTheme.circle(activity));
            int pad = dp(activity, 10f);
            view.setPadding(pad, pad, pad, pad);
        }
    }

    private static final java.util.WeakHashMap<Activity, Integer> appliedPalette =
            new java.util.WeakHashMap<>();

    /**
     * 按当前封面主色给按钮上色（同一主色只做一次）。MaterialButton 用 tint /
     * stroke，图标按钮换运行时背景；播放键保持实心主色圆。
     */
    public static void tintButtons(Activity activity) {
        int accent = ColorTheme.accent();
        Integer last = appliedPalette.get(activity);
        if (last != null && last == accent) return;
        appliedPalette.put(activity, accent);
        tintButtons(activity, activity.findViewById(android.R.id.content));
    }

    private static void tintButtons(Activity activity, View view) {
        if (view == null) return;
        if (view instanceof com.google.android.material.button.MaterialButton) {
            com.google.android.material.button.MaterialButton button =
                    (com.google.android.material.button.MaterialButton) view;
            button.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(ColorTheme.fill()));
            button.setStrokeColor(android.content.res.ColorStateList.valueOf(ColorTheme.stroke()));
            button.setStrokeWidth(ColorTheme.dp(activity, 1f));
            button.setElevation(0f);
            button.setStateListAnimator(null);
        } else if (view instanceof android.widget.ImageButton) {
            if (view.getId() == R.id.btn_play) {
                view.setBackground(ColorTheme.solidCircle(activity));
            } else if (Boolean.TRUE.equals(view.getTag(R.id.btn_shape_tag))) {
                view.setBackground(ColorTheme.circle(activity));
            } else {
                view.setBackground(ColorTheme.capsule(activity));
            }
        } else if (view instanceof android.widget.TextView) {
            // 所有「主色 / 强调色」的文字也跟着一起变（分组标题、当前值、说明等）。
            android.widget.TextView text = (android.widget.TextView) view;
            android.content.res.ColorStateList list = text.getTextColors();
            if (list != null && isAccentColor(activity, list.getDefaultColor())) {
                text.setTextColor(ColorTheme.accent());
            }
        }
        if (view instanceof android.widget.SeekBar) {
            // 全部滑条统一成两段式粗圆角条，颜色跟随当前封面主色。
            android.widget.SeekBar bar = (android.widget.SeekBar) view;
            bar.setProgressDrawable(ColorTheme.sliderTrack(activity));
            bar.setThumb(ColorTheme.sliderThumb(activity));
            bar.setThumbOffset(0);
            bar.setSplitTrack(false);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                tintButtons(activity, group.getChildAt(i));
            }
        }
    }

    private static boolean isAccentColor(Activity activity, int color) {
        // 资源里的强调色，以及布局里写死的旧强调色（天蓝 / 浅蓝）都算，
        // 统一换成当前主色。
        return color == activity.getResources().getColor(R.color.accent)
                || color == 0xFF4FC3F7
                || color == 0xFF81D4FA;
    }

    /**
     * 相邻按钮之间留出间距：所有「横向一行里放了两个以上按钮」的容器，给除第一个
     * 之外的子项加 marginStart。之前底栏、传输栏、均衡器预设那一排的边框是贴在一起的。
     */
    public static void spaceButtons(Activity activity) {
        spaceButtons(activity, activity.findViewById(android.R.id.content));
    }

    private static void spaceButtons(Activity activity, View root) {
        if (root == null) return;
        int gap = dp(activity, 16f);
        int vGap = dp(activity, 12f);
        if (root instanceof android.widget.LinearLayout) {
            android.widget.LinearLayout row = (android.widget.LinearLayout) root;
            if (row.getOrientation() == android.widget.LinearLayout.HORIZONTAL) {
                int buttons = 0;
                for (int i = 0; i < row.getChildCount(); i++) {
                    if (isButtonLike(row.getChildAt(i))) buttons++;
                }
                if (buttons >= 2) {
                    boolean first = true;
                    for (int i = 0; i < row.getChildCount(); i++) {
                        View child = row.getChildAt(i);
                        ViewGroup.LayoutParams params = child.getLayoutParams();
                        if (params instanceof ViewGroup.MarginLayoutParams) {
                            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                            margins.setMarginStart(first ? margins.leftMargin : gap);
                            child.setLayoutParams(params);
                        }
                        first = false;
                    }
                }
            } else {
                // 竖排里相邻的两个按钮也要留出间距（原来只有 8dp，几乎贴着）。
                View previous = null;
                for (int i = 0; i < row.getChildCount(); i++) {
                    View child = row.getChildAt(i);
                    ViewGroup.LayoutParams params = child.getLayoutParams();
                    if (isButtonLike(child) && previous != null
                            && params instanceof ViewGroup.MarginLayoutParams) {
                        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                        if (margins.topMargin < vGap) {
                            margins.topMargin = vGap;
                            child.setLayoutParams(params);
                        }
                    }
                    previous = child;
                }
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                spaceButtons(activity, group.getChildAt(i));
            }
        }
    }

    private static boolean isButtonLike(View view) {
        return view instanceof com.google.android.material.button.MaterialButton
                || view instanceof android.widget.ImageButton
                || view instanceof android.widget.Button;
    }

    private static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

}
