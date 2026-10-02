package com.airmusic.player.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Locks its child content to the reference TV-box aspect ratio (16:9),
 * centered in whatever screen the app runs on. On the box itself the sizes
 * match exactly, so nothing changes; on phones / tablets the content keeps
 * the box's proportions with the blurred background filling the letterbox
 * areas. The window's content area already excludes the status / navigation
 * bars, so the fitted content never overlaps them.
 */
public class BoxAspectFrameLayout extends FrameLayout {

    /** Reference box ratio: 1920x1080. */
    private static final float REF_ASPECT = 16f / 9f;
    /**
     * 参考盒子（1920x1080、density 2.1875）折算成 dp 的尺寸。
     *
     * <p>比这个尺寸更大的屏幕（平板 / 大屏电视）不再把界面「摊开」到更多的 dp
     * 空间——那样字会显得偏小、位置也会显得偏上——而是按盒子的尺寸排版再整体
     * 等比放大，看到的比例和盒子上完全一致。比盒子小的屏幕（手机）仍然按原来的
     * 方式重排，避免整体缩小到看不清。
     */
    private static final float REF_WIDTH_DP = 1920f / 2.1875f;
    private static final float REF_HEIGHT_DP = 1080f / 2.1875f;
    /**
     * The reference UI stays readable from 4:3 to 21:9; outside that range we
     * still letterbox rather than distorting the composition.
     */
    private static final float MIN_ASPECT = 1.45f;
    private static final float MAX_ASPECT = 1.85f;

    private int insetLeft;
    private int insetTop;
    private int insetRight;
    private int insetBottom;
    /** When > 0 the box always uses this exact ratio (the lyric stage). */
    private float forcedAspect;
    /** 贴屏幕上下沿、横向铺满整屏的例外组件（顶栏 / 底栏）。 */
    private final List<View> topBars = new ArrayList<>();
    private final List<View> bottomBars = new ArrayList<>();

    public BoxAspectFrameLayout(Context context) {
        super(context);
    }

    public BoxAspectFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setInsets(int left, int top, int right, int bottom) {
        insetLeft = left;
        insetTop = top;
        insetRight = right;
        insetBottom = bottom;
        requestLayout();
    }

    /** Locks the content to exactly this ratio instead of the screen's. */
    public void setForcedAspect(float aspect) {
        forcedAspect = aspect;
        requestLayout();
    }

    /**
     * 把顶栏 / 底栏登记成「贴边组件」：它们不再跟着内容一起缩放居中，
     * 而是横向铺满整屏、纵向贴着屏幕的上沿 / 下沿。
     */
    public void addBar(View bar, boolean top) {
        if (bar == null) return;
        ViewGroup parent = bar.getParent() instanceof ViewGroup
                ? (ViewGroup) bar.getParent() : null;
        if (parent != null) parent.removeView(bar);
        (top ? topBars : bottomBars).add(bar);
        addView(bar);
    }

    private boolean isBar(View view) {
        return topBars.contains(view) || bottomBars.contains(view);
    }

    private int measuredBarHeight(List<View> bars) {
        int height = 0;
        for (View bar : bars) height = Math.max(height, bar.getMeasuredHeight());
        return height;
    }

    /** 内容区（去掉两条栏）的排版高度：栏本来就在页面里占这么多，摘出来之后要补回去。 */
    private int contentLayoutHeight(int topH, int bottomH) {
        return Math.max(1, referenceHeight() - topH - bottomH);
    }

    /** 整体缩放倍数：两条栏 + 内容合起来正好是盒子的高度，盒子本身为 1:1。 */
    private float scaleFor(int cw, int ch) {
        float density = getResources().getDisplayMetrics().density;
        float baseW = REF_WIDTH_DP * density;
        float baseH = REF_HEIGHT_DP * density;
        return Math.min(cw / baseW, ch / baseH);
    }

    private int contentWidth(int totalW, int totalH) {
        int availW = Math.max(1, totalW - insetLeft - insetRight);
        int availH = Math.max(1, totalH - insetTop - insetBottom);
        float target = targetAspect(availW, availH);
        if (availW / (float) availH > target) {
            return Math.round(availH * target);
        }
        return availW;
    }

    private int contentHeight(int totalW, int totalH) {
        int availW = Math.max(1, totalW - insetLeft - insetRight);
        int availH = Math.max(1, totalH - insetTop - insetBottom);
        float target = targetAspect(availW, availH);
        if (availW / (float) availH > target) {
            return availH;
        }
        return Math.round(availW / target);
    }

    /** Fills the screen for common tablet/TV ratios, clamps extreme ones. */
    private float targetAspect(int availW, int availH) {
        if (forcedAspect > 0f) return forcedAspect;
        float screenAspect = availW / (float) Math.max(1, availH);
        return Math.max(MIN_ASPECT, Math.min(MAX_ASPECT, screenAspect));
    }

    /**
     * 按盒子尺寸排版时的缩放倍数（方向不限）：大屏放大、小屏缩小，
     * 保证任何设备看到的都是盒子的比例。歌词页要铺满整屏，不参与。
     */
    private float referenceScale(int cw, int ch) {
        if (!usesReferenceLayout()) return 1f;
        float density = getResources().getDisplayMetrics().density;
        float baseW = REF_WIDTH_DP * density;
        float baseH = REF_HEIGHT_DP * density;
        return Math.min(cw / baseW, ch / baseH);
    }

    /** 除了歌词页（强制比例、要铺满整屏），其余页面一律按盒子尺寸排版再等比缩放。 */
    private boolean usesReferenceLayout() {
        return forcedAspect <= 0f;
    }

    private int referenceWidth() {
        return Math.round(REF_WIDTH_DP * getResources().getDisplayMetrics().density);
    }

    private int referenceHeight() {
        return Math.round(REF_HEIGHT_DP * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int totalW = MeasureSpec.getSize(widthMeasureSpec);
        int totalH = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(totalW, totalH);
        int cw = contentWidth(totalW, totalH);
        int ch = contentHeight(totalW, totalH);
        if (!usesReferenceLayout()) {
            // 歌词页：铺满整屏，保持原样
            int specW = MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY);
            int specH = MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY);
            for (int i = 0; i < getChildCount(); i++) {
                measureChild(getChildAt(i), specW, specH);
            }
            return;
        }
        // 顶栏 / 底栏：按盒子宽度测一次，拿到它们的高度
        int barW = MeasureSpec.makeMeasureSpec(referenceWidth(), MeasureSpec.EXACTLY);
        int barH = MeasureSpec.makeMeasureSpec(ch, MeasureSpec.AT_MOST);
        for (View bar : topBars) bar.measure(barW, barH);
        for (View bar : bottomBars) bar.measure(barW, barH);
        int topBarHeight = measuredBarHeight(topBars);
        int bottomBarHeight = measuredBarHeight(bottomBars);
        int scaleW = MeasureSpec.makeMeasureSpec(referenceWidth(), MeasureSpec.EXACTLY);
        int scaleH = MeasureSpec.makeMeasureSpec(
                contentLayoutHeight(topBarHeight, bottomBarHeight), MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (isBar(child)) continue;
            child.measure(scaleW, scaleH);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int totalW = right - left;
        int totalH = bottom - top;
        int availW = Math.max(1, totalW - insetLeft - insetRight);
        int availH = Math.max(1, totalH - insetTop - insetBottom);
        int cw = contentWidth(totalW, totalH);
        int ch = contentHeight(totalW, totalH);
        int cx = insetLeft + (availW - cw) / 2;
        int cy = insetTop + (availH - ch) / 2;
        if (!usesReferenceLayout()) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.layout(cx, cy, cx + cw, cy + ch);
                child.setPivotX(0f);
                child.setPivotY(0f);
                child.setScaleX(1f);
                child.setScaleY(1f);
            }
            return;
        }
        int baseW = referenceWidth();
        int baseH = referenceHeight();
        int topBarHeight = measuredBarHeight(topBars);
        int bottomBarHeight = measuredBarHeight(bottomBars);
        int contentLayoutH = contentLayoutHeight(topBarHeight, bottomBarHeight);
        float scale = scaleFor(cw, ch);
        // 栏按「屏幕宽度 / scale」排版再整体放大，放大后正好铺满整屏且不拉伸变形
        int barWidth = Math.max(1, Math.round(cw / scale));

        // 顶栏：贴上沿
        for (View bar : topBars) {
            int height = bar.getMeasuredHeight();
            bar.layout(cx, cy, cx + barWidth, cy + height);
            bar.setPivotX(0f);
            bar.setPivotY(0f);
            bar.setScaleX(scale);
            bar.setScaleY(scale);
        }
        // 底栏：贴下沿（以底边为基准放大）
        int bottomTop = cy + ch - bottomBarHeight;
        for (View bar : bottomBars) {
            int height = bar.getMeasuredHeight();
            bar.layout(cx, bottomTop + (bottomBarHeight - height), cx + barWidth,
                    bottomTop + bottomBarHeight);
            bar.setPivotX(0f);
            bar.setPivotY(bottomBarHeight);
            bar.setScaleX(scale);
            bar.setScaleY(scale);
        }
        // 内容：夹在两条栏之间，按盒子尺寸等比缩放
        int regionTop = cy + Math.round(topBarHeight * scale);
        int regionBottom = cy + ch - Math.round(bottomBarHeight * scale);
        int contentHeight = Math.round(contentLayoutH * scale);
        int contentTop = regionTop
                + Math.max(0, (regionBottom - regionTop - contentHeight) / 2);
        int contentLeft = cx + Math.round((cw - baseW * scale) / 2f);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (isBar(child)) continue;
            child.layout(contentLeft, contentTop, contentLeft + baseW,
                    contentTop + contentLayoutH);
            child.setPivotX(0f);
            child.setPivotY(0f);
            child.setScaleX(scale);
            child.setScaleY(scale);
        }
    }
}
