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
        if (view.getId() == R.id.volume_icon && view instanceof android.widget.ImageView) {
            // 图标在浅色胶囊里，用已播放段的深色同色系，保证看得清。
            ((android.widget.ImageView) view).setImageTintList(
                    android.content.res.ColorStateList.valueOf(ColorTheme.sliderActive()));
        }
        // 底栏四个图标必须永远同一色：以前只有「多房间」在每次刷新时重新上色，
        // 换歌取到新主色后其余三个还是旧色，看着就是两种颜色。
        if (view instanceof android.widget.ImageView && isNavIcon(view.getId())) {
            ((android.widget.ImageView) view).setImageTintList(
                    android.content.res.ColorStateList.valueOf(ColorTheme.accent()));
        }
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
            // 颜色只认「角色」：第一次按布局里的颜色判定它是主色 / 纯白 / 次级灰，
            // 之后每次都按角色重新算。否则上过一轮色之后就再也认不出来，
            // 换歌时文字颜色就不刷新了（歌手名不刷新就是这个原因）。
            Object tagged = text.getTag(R.id.palette_role_tag);
            String role = tagged instanceof String ? (String) tagged : null;
            if (role == null) {
                android.content.res.ColorStateList list = text.getTextColors();
                if (list != null) {
                    int color = list.getDefaultColor();
                    if (isAccentColor(activity, color)) {
                        role = "accent";
                    } else if (isWhiteText(activity, color)) {
                        role = "white";
                    } else if (isSecondaryText(activity, color)) {
                        role = "secondary";
                    }
                    if (role != null) text.setTag(R.id.palette_role_tag, role);
                }
            }
            if (role != null) {
                if ("accent".equals(role)) {
                    // 文字用提饱和版的主色，换歌时看得出来在变
                    text.setTextColor(ColorTheme.textAccent());
                } else if ("white".equals(role)) {
                    // 纯白字统一成气泡那种同色系近白
                    text.setTextColor(ColorTheme.tooltipText());
                } else if ("secondary".equals(role)) {
                    // 次级灰字（歌手名、专辑名、说明）掺一半主色，跟着封面变
                    text.setTextColor(ColorTheme.textSecondary());
                }
            }
            // 行首的线性图标（compound drawable）同样要保持当前主色。
            if (text.getCompoundDrawableTintList() != null) {
                text.setCompoundDrawableTintList(
                        android.content.res.ColorStateList.valueOf(ColorTheme.accent()));
            }
        }
        // 开关：轨道 / 滑块也跟动态主色走（原来写死成固定灰蓝）
        if (view instanceof android.widget.Switch) {
            android.widget.Switch toggle = (android.widget.Switch) view;
            toggle.setThumbTintList(switchThumbColors());
            toggle.setTrackTintList(switchTrackColors());
        } else if (view instanceof androidx.appcompat.widget.SwitchCompat) {
            androidx.appcompat.widget.SwitchCompat toggle =
                    (androidx.appcompat.widget.SwitchCompat) view;
            toggle.setThumbTintList(switchThumbColors());
            toggle.setTrackTintList(switchTrackColors());
        }
        // 设置页里 OutlinedButton 是白字、TextButton 是主色字，两套看着不一样，
        // 统一成拖动气泡里那种同色系近白（高对比、带一点封面色）。
        if (view instanceof android.widget.Button) {
            ((android.widget.Button) view).setTextColor(ColorTheme.tooltipText());
        }
        if (view instanceof android.widget.SeekBar) {
            // 全部滑条统一成两段式粗圆角条，颜色跟随当前封面主色。
            android.widget.SeekBar bar = (android.widget.SeekBar) view;
            bar.setProgressDrawable(ColorTheme.sliderTrack(activity));
            bar.setThumb(ColorTheme.sliderThumb(activity));
            // 主题里的默认 tint 会盖掉自定义 drawable 的两段颜色，必须清空
            bar.setProgressTintList(null);
            bar.setBackgroundTintList(null);
            bar.setProgressBackgroundTintList(null);
            bar.setThumbTintList(null);
            bar.setThumbOffset(0);
            bar.setSplitTrack(false);
        } else if (view instanceof com.google.android.material.slider.Slider) {
            // Material 滑条：轨道粗细 / 手柄尺寸 / 断开间隙都在布局里配好，
            // 这里只按当前封面主色刷新三段颜色（已播放=实色偏深、未播放=浅色、
            // 手柄=更深的同色系）。
            com.google.android.material.slider.Slider slider =
                    (com.google.android.material.slider.Slider) view;
            // 音量条的整根条（轨道 + 深色段 + 手柄）是自绘的，Material 只负责
            // 触摸和拖动气泡；进度条用 Material 自己的轨道。
            boolean customTrack = view.getId() == R.id.volume_seek
                    || view.getId() == R.id.seek_bar;
            int transparent = android.graphics.Color.TRANSPARENT;
            slider.setTrackActiveTintList(android.content.res.ColorStateList.valueOf(
                    customTrack ? transparent : ColorTheme.sliderActive()));
            slider.setTrackInactiveTintList(android.content.res.ColorStateList.valueOf(
                    customTrack ? transparent : ColorTheme.sliderInactive()));
            // 步进刻度 / 末端圆点都不要。
            slider.setTickVisible(false);
            slider.setTrackStopIndicatorSize(0);
            // 进度条的轨道两端也要半圆（Material 默认的内侧圆角是方的）
            if (!customTrack) {
                slider.setTrackInsideCornerSize(slider.getTrackHeight() / 2);
            }
            slider.setThumbTintList(
                    android.content.res.ColorStateList.valueOf(
                            customTrack ? transparent : ColorTheme.sliderThumb()));
            slider.setHaloTintList(android.content.res.ColorStateList.valueOf(
                    ColorTheme.withAlpha(ColorTheme.accent(), 0.18f)));
            // 拖动时上方那个数值气泡也跟着封面主色走（深底 + 近白字）。
            SliderLabelTint.apply(slider);
            SliderTrackView track = null;
            if (customTrack) {
                track = trackViewFor(activity, view);
            }
            if (track != null) {
                track.setColors(ColorTheme.sliderInactive(), ColorTheme.sliderActive(),
                        ColorTheme.sliderThumb());
            }
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
        // 统一换成当前主色。连当前主色本身也算上——否则「上一帧刚被设成主色」
        // 的元素（选中的标签页等）换歌后就不会再更新了。
        return color == activity.getResources().getColor(R.color.accent)
                || color == ColorTheme.accent()
                || color == 0xFF4FC3F7
                || color == 0xFF81D4FA;
    }

    /** 纯白文字（@color/text_primary）——这些是要跟着动态取色走的漏网之鱼。 */
    private static boolean isWhiteText(Activity activity, int color) {
        return color == activity.getResources().getColor(R.color.text_primary)
                || color == 0xFFFFFFFF;
    }

    /** 次级灰字（@color/text_secondary）。 */
    private static boolean isSecondaryText(Activity activity, int color) {
        return color == activity.getResources().getColor(R.color.text_secondary);
    }

    private static android.content.res.ColorStateList switchThumbColors() {
        return new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xFFFFFFFF, 0xFFB6C3D3});
    }

    private static android.content.res.ColorStateList switchTrackColors() {
        return new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xFF000000 | (ColorTheme.accent() & 0xFFFFFF), 0x33FFFFFF});
    }

    /** 底栏四个图标按钮。 */
    private static boolean isNavIcon(int id) {
        return id == R.id.btn_library || id == R.id.btn_settings
                || id == R.id.btn_apps || id == R.id.btn_multicast;
    }

    /** 自绘整条（轨道 + 深色段 + 手柄）的配对。 */
    private static SliderTrackView trackViewFor(Activity activity, View slider) {
        if (slider.getId() == R.id.volume_seek) {
            return activity.findViewById(R.id.volume_track);
        }
        if (slider.getId() == R.id.seek_bar) {
            return activity.findViewById(R.id.seek_track);
        }
        return null;
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
