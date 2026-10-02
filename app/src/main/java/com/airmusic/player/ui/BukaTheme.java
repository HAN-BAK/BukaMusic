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
