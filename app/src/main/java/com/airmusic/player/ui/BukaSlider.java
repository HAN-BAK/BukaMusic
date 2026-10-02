package com.airmusic.player.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.google.android.material.slider.Slider;

/**
 * 播放界面进度条那套滑条样式，做成公共件给所有滑条复用：
 * 自绘胶囊轨道（已播放段 = 深色、靠手柄那头直角切口）+ 手柄两侧挖空的断口 +
 * 竖向胶囊手柄 + Material 的拖动气泡。
 */
public final class BukaSlider {

    public static final float TRACK_HEIGHT_DP = 24f;
    public static final float THUMB_WIDTH_DP = 7f;
    public static final float THUMB_HEIGHT_DP = 36f;
    public static final float THUMB_GAP_DP = 8f;

    private BukaSlider() {
    }

    /** 往 host（一个 FrameLayout）里放一套同款滑条，返回可用的 Slider。 */
    public static Slider attach(FrameLayout host) {
        Context context = host.getContext();
        float d = context.getResources().getDisplayMetrics().density;

        SliderTrackView track = new SliderTrackView(context);
        FrameLayout.LayoutParams trackParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(THUMB_HEIGHT_DP * d));
        trackParams.gravity = Gravity.CENTER_VERTICAL;
        track.setLayoutParams(trackParams);
        track.setThumb(THUMB_WIDTH_DP, THUMB_HEIGHT_DP, THUMB_GAP_DP);
        track.setTrackHeightDp(TRACK_HEIGHT_DP);
        host.addView(track);

        Slider slider = new Slider(context);
        slider.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        slider.setStepSize(1f);
        slider.setTickVisible(false);
        slider.setTrackStopIndicatorSize(0);
        slider.setHaloRadius(0);
        slider.setThumbElevation(0f);
        slider.setThumbWidth(Math.round(THUMB_WIDTH_DP * d));
        slider.setThumbHeight(Math.round(THUMB_HEIGHT_DP * d));
        slider.setThumbTrackGapSize(Math.round(THUMB_GAP_DP * d));
        // 轨道和手柄都交给自绘层画
        slider.setTrackActiveTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        slider.setTrackInactiveTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        slider.setThumbTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        slider.setBackground(null);
        slider.addOnChangeListener((s, value, fromUser) -> {
            float span = s.getValueTo() - s.getValueFrom();
            track.setFraction(span <= 0f ? 0f : (value - s.getValueFrom()) / span);
        });
        host.addView(slider);

        // 按住滑条时整层轨道轻微缩放（和按钮同一套按压反馈）
        PressFx.attachScaled(slider, host, 0.97f);
        host.post(() -> align(host, slider, track, d));
        return slider;
    }

    /**
     * 自绘轨道的手柄行程在条内各让出「半个手柄 + 一个断口」，
     * Material 的轨道要同步内缩同样的量，两边才严丝合缝；顺带把垂直位置居中对齐。
     */
    private static void align(FrameLayout host, Slider slider, SliderTrackView track, float d) {
        int trackWidth = slider.getTrackWidth();
        if (slider.getWidth() <= 0 || trackWidth <= 0 || slider.getWidth() <= trackWidth) {
            // 按住滑条时整层轨道轻微缩放（和按钮同一套按压反馈）
        PressFx.attachScaled(slider, host, 0.97f);
        host.post(() -> align(host, slider, track, d)); // 布局还没算好，下一帧再来
            return;
        }
        int pad = (slider.getWidth() - trackWidth) / 2;
        int half = Math.round(THUMB_WIDTH_DP * d / 2f);
        int margin = -(pad - half * 2 - Math.round(THUMB_GAP_DP * d));
        ViewGroup.LayoutParams raw = slider.getLayoutParams();
        if (raw instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) raw;
            lp.setMarginStart(margin);
            lp.setMarginEnd(margin);
            slider.setLayoutParams(lp);
        }
        slider.setTranslationY((host.getHeight() - slider.getHeight()) / 2f);
        track.invalidate();
    }
}
