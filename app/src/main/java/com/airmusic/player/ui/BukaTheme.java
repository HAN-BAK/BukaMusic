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

    /** 主卡片：页面里成块的内容。 */
    public static void card(Activity activity, int... viewIds) {
        for (int id : viewIds) {
            View view = activity.findViewById(id);
            if (view == null) continue;
            view.setBackgroundResource(R.drawable.bg_page);
            int pad = dp(activity, 16f);
            view.setPadding(pad, pad, pad, pad);
            // 轻微投影代替描边：有层次但不抢眼。
            view.setElevation(dp(activity, 2f));
        }
    }

    /** 次级卡片：分组标题、行、小块信息。 */
    public static void soft(Activity activity, int... viewIds) {
        for (int id : viewIds) {
            View view = activity.findViewById(id);
            if (view == null) continue;
            view.setBackgroundResource(R.drawable.bg_card_soft);
        }
    }

    /** 次级卡片 + 内边距：设置页的分组内容这类成块但不需要描边的地方。 */
    public static void softBlock(Activity activity, int... viewIds) {
        for (int id : viewIds) {
            View view = activity.findViewById(id);
            if (view == null) continue;
            view.setBackgroundResource(R.drawable.bg_card_soft);
            int pad = dp(activity, 14f);
            view.setPadding(pad, pad, pad, pad);
        }
    }

    /** 只换背景、不加内边距（列表、网格这类自己带间距的容器）。 */
    public static void backdrop(Activity activity, int... viewIds) {
        for (int id : viewIds) {
            View view = activity.findViewById(id);
            if (view == null) continue;
            view.setBackgroundResource(R.drawable.bg_page);
        }
    }

    /**
     * 播放界面的按钮用圆形边框（同一套主色填充 + 描边）。
     * 这里先设好背景，PageFx 的通用描边逻辑看到已有背景就会跳过，不会覆盖成圆角方形。
     */
    public static void circleButtons(Activity activity, int... viewIds) {
        for (int id : viewIds) {
            View view = activity.findViewById(id);
            if (view == null) continue;
            view.setBackgroundResource(R.drawable.bg_btn_circle);
            int pad = dp(activity, 10f);
            view.setPadding(pad, pad, pad, pad);
        }
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

    /**
     * 把页面主体（第一个 ScrollView 的内容容器）变成浮起来的卡片：加圆角背景
     * 与四周外边距，和对话框卡片同一观感。页面里已有内边距，内容不会被贴边。
     */
    public static void applyPageContent(Activity activity) {
        ScrollView scroll = findScrollView(activity.findViewById(android.R.id.content));
        if (scroll == null || scroll.getChildCount() == 0) return;
        View content = scroll.getChildAt(0);
        ViewGroup.LayoutParams params = content.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            int side = dp(activity, 14f);
            margins.setMargins(side, dp(activity, 6f), side, dp(activity, 14f));
            content.setLayoutParams(params);
        }
        content.setBackgroundResource(R.drawable.bg_page);
        content.setElevation(dp(activity, 2f));
    }

    private static ScrollView findScrollView(View root) {
        if (root == null) return null;
        if (root instanceof ScrollView) return (ScrollView) root;
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            ScrollView found = findScrollView(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    /** 顶栏（含返回键的那一行）也做成一张次级卡片。 */
    public static void applyTopBar(Activity activity) {
        View back = activity.findViewById(R.id.btn_back);
        if (back == null) return;
        if (!(back.getParent() instanceof View)) return;
        View bar = (View) back.getParent();
        bar.setBackgroundResource(R.drawable.bg_card_soft);
        ViewGroup.LayoutParams params = bar.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            int side = dp(activity, 14f);
            margins.setMargins(side, dp(activity, 8f), side, 0);
            bar.setLayoutParams(params);
        }
    }
}
