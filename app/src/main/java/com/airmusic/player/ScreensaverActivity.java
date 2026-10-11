package com.airmusic.player;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.airmusic.player.ui.ColorTheme;
import com.airmusic.player.util.BlurBackground;
import com.airmusic.player.util.PlayerUiState;
import com.airmusic.player.util.Prefs;
import com.airmusic.player.util.StateBus;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 屏保（氛围桌面时钟）：播放页上滑进入。
 *
 * <p>显示大号时间、日期、当前播放的歌曲信息；配色全部跟随当前封面（动态取色），
 * 背景是和其他页面同一套的封面模糊图。长时间停留时整个面板做像素级缓慢位移
 * （防烧屏），亮度不做任何改动、始终跟随系统。
 */
public class ScreensaverActivity extends BaseActivity {

    private static final String TAG = "Screensaver";
    private View clockRow;
    private TextView hourView;
    private TextView colonView;
    private TextView minuteView;
    private View root;

    private Prefs prefs;
    private long driftStart;
    private float driftAmplitude;
    private float ssDownY;
    private boolean ssDragging;
    /** 本次手势是否拖动过（拖动过的松手不算点击，避免上滑也退出）。 */
    private boolean ssMoved;

    /**
     * 下滑退出：跟手把屏保往下拖，松手过阈值就滑回播放页（与播放页上滑进入对称），
     * 不够则弹回原位。这样"播放界面 ↔ 屏保"就是一对吸附式翻页。
     */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                ssDownY = ev.getY();
                ssDragging = false;
                ssMoved = false;
                break;
            case android.view.MotionEvent.ACTION_MOVE:
                float down = ev.getY() - ssDownY;
                if (Math.abs(down) > 8) ssMoved = true;
                if (down > 12) {
                    ssDragging = true;
                    root.setTranslationY(down * 0.5f);
                }
                break;
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                float moved = ev.getY() - ssDownY;
                if (ssDragging && moved > 24 * getResources().getDisplayMetrics().density) {
                    root.animate().translationY(root.getHeight()).setDuration(200L).start();
                    finish();
                    overridePendingTransition(R.anim.ss_stay, R.anim.ss_exit);
                } else if (ssDragging) {
                    root.animate().translationY(0f).setDuration(180L).start();
                }
                ssDragging = false;
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateClock();
            handler.postDelayed(this, 1000L);
        }
    };

    /** 渐隐渐显：一帧 40ms（≈25fps，盒子也画得动），一个呼吸周期 2.6 秒。 */
    private static final long COLON_FADE_STEP_MS = 40L;
    private static final long COLON_FADE_PERIOD_MS = 2600L;

    /**
     * 冒号竖直微调：字体自带的冒号位置偏低，把它单独放一个文本框后平移对齐到
     * 数字的可视中心（用同一个 Paint 量数字和冒号的墨迹中心之差，字体/字号换了
     * 也自动跟着变），这样冒号仍旧是字体本身的样式、只是位置正了。
     */
    private void alignColon() {
        if (hourView == null || colonView == null) return;
        Paint paint = hourView.getPaint();
        android.graphics.Rect digits = new android.graphics.Rect();
        android.graphics.Rect colon = new android.graphics.Rect();
        // 「0」的墨迹上下沿就是数字的上下沿；整串 "0123456789" 里可能有旧式下伸
        // 字形（实测包围盒 -445~+89），会把中心压低，所以定位只用「0」。
        paint.getTextBounds("0", 0, 1, digits);
        paint.getTextBounds(":", 0, 1, colon);
        float dy = (digits.top + digits.bottom) / 2f - (colon.top + colon.bottom) / 2f;
        colonView.setTranslationY(dy);
        android.util.Log.i(TAG, "colon align: digits(" + digits.top + "," + digits.bottom
                + ") colon(" + colon.top + "," + colon.bottom + ") dy=" + dy);
    }

    /**
     * 冒号渐隐渐显：用余弦曲线在 1 → 0.12 → 1 之间来回（不是硬闪）。
     * 冒号是独立控件，改 alpha 只重绘这一小块，盒子上也顺。
     * 关掉「冒号闪烁」设置时常亮。
     */
    private final Runnable colonFade = new Runnable() {
        @Override
        public void run() {
            boolean fade = prefs != null && prefs.isScreensaverBlink();
            float alpha = 1f;
            if (fade) {
                float phase = (android.os.SystemClock.uptimeMillis() % COLON_FADE_PERIOD_MS)
                        / (float) COLON_FADE_PERIOD_MS;
                alpha = 0.12f + 0.88f
                        * (0.5f + 0.5f * (float) Math.cos(phase * 2d * Math.PI));
            }
            if (colonView != null) colonView.setAlpha(alpha);
            if (fade) handler.postDelayed(this, COLON_FADE_STEP_MS);
        }
    };

    private final StateBus.Listener stateListener = state -> runOnUiThread(() -> render(state));

    /**
     * 屏保整页只有时钟，不跟着 16:9 的盒子走：跟着走的话 4:3 平板上时钟只能占中间
     * 一条、21:9 带鱼屏左右又各留一条边，白白把时钟挤小。这里和歌词页一样脱离
     * 缩放盒子，时钟按整块屏幕可用区域适配（见 {@link #fitClockToScreen()}）。
     */
    @Override
    protected boolean keepBoxAspectWrapper() {
        return false;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_screensaver);
        prefs = new Prefs(this);
        clockRow = findViewById(R.id.screensaver_clock);
        hourView = findViewById(R.id.screensaver_time_hour);
        colonView = findViewById(R.id.screensaver_time_colon);
        minuteView = findViewById(R.id.screensaver_time_minute);
        root = findViewById(R.id.screensaver_root);
        // 屏幕尺寸/比例变了（平板、带鱼屏、分辨率切换）就按新的可用区域重新适配
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((r - l) != (or - ol) || (b - t) != (ob - ot)) refitClock();
        });
        driftAmplitude = 6f * getResources().getDisplayMetrics().density;
        driftStart = android.os.SystemClock.elapsedRealtime();
        // 点一下退出屏保（与 ClockDesk 一样：点屏幕回到播放界面）
        root.setOnClickListener(v -> {
            if (ssMoved) return;   // 拖动过的那一下不算点击（上滑不会退出）
            finish();
        });
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        BlurBackground.apply(this, R.color.background);
        applyColors();
    }

    @Override
    protected void onResume() {
        super.onResume();
        BlurBackground.apply(this, R.color.background);
        applyColors();
        StateBus.get().addListener(stateListener);
        render(StateBus.get().getState());
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        handler.removeCallbacks(colonFade);
        handler.post(colonFade);
    }

    @Override
    protected void onPause() {
        super.onPause();
        StateBus.get().removeListener(stateListener);
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(colonFade);
        cancelReflows();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(colonFade);
        cancelReflows();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onDestroy();
    }

    /** 退出时把还没跑完的宽度动画收掉（否则会在页面离开后继续改布局）。 */
    private void cancelReflows() {
        // cancel() 会回调 onAnimationEnd，而那里会从 reflows 里移除自己，
        // 所以这里遍历副本，避免一边遍历一边改。
        for (android.animation.ValueAnimator animator
                : new java.util.ArrayList<>(reflows)) {
            animator.cancel();
        }
        reflows.clear();
    }

    /** 时间 / 日期 / 防烧屏位移。 */
    private void updateClock() {
        Date now = new Date();
        boolean h24 = prefs.isScreensaver24h();
        Locale locale = Locale.getDefault();
        // 时 / 分分开写进两个文本框，中间夹一个独立的冒号文本框（只有它做渐隐渐显）。
        String hour = new SimpleDateFormat(h24 ? "HH" : "h", locale).format(now);
        String minute = new SimpleDateFormat("mm", locale).format(now);
        if (hour.equals(lastHourText) && minute.equals(lastMinuteText)) {
            // 这一分钟里数字没变：不做任何排版动作
        } else {
            // 不同数字的字宽不一样（"50" 比 "51" 宽、"1" 比 "8" 窄），换数字时
            // 两边的字块会在一帧里横移一大截，很生硬。这里把「时 / 分」两个文本框
            // 的宽度做成缓出动画：整块跟着连续重排，看起来是慢慢滑过去的。
            float oldHourWidth = hourView.getWidth();
            float oldMinuteWidth = minuteView.getWidth();
            lastHourText = hour;
            lastMinuteText = minute;
            hourView.setText(hour);
            minuteView.setText(minute);
            if (oldHourWidth > 0 && oldMinuteWidth > 0) {
                // 关键：换完数字后立刻把宽度钉回旧值。否则紧接着那次排版会先按
                // 新宽度跳一下，动画再追上去——看起来就是「先闪一下再滑」。
                float newHourWidth = textWidth(hourView, hour);
                float newMinuteWidth = textWidth(minuteView, minute);
                applyWidth(hourView, oldHourWidth);
                applyWidth(minuteView, oldMinuteWidth);
                animateWidth(hourView, oldHourWidth, newHourWidth);
                animateWidth(minuteView, oldMinuteWidth, newMinuteWidth);
            }
        }
        updateDrift();
    }

    /** 直接给定文本框宽度（wrap_content 时由文字决定，动画期间由这里接管）。 */
    private void applyWidth(TextView view, float width) {
        if (view == null) return;
        android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
        lp.width = Math.max(1, Math.round(width));
        view.setLayoutParams(lp);
    }

    private String lastHourText;
    private String lastMinuteText;
    /** 数字变化时的横移 / 变宽动画时长（缓出，越长越软，太短会显得跳）。 */
    private static final long REFLOW_MS = 340L;
    private final java.util.List<android.animation.ValueAnimator> reflows =
            new java.util.ArrayList<>();

    /**
     * 把一个文本框的宽度从旧值动画到新值：中间的每一帧都重新排版并重算对齐，
     * 所以「时 / 冒号 / 分」会连续地滑动过去，而不是一帧跳到位。
     */
    private void animateWidth(final TextView view, float fromWidth, final float toWidth) {
        if (view == null || Math.abs(toWidth - fromWidth) < 0.5f) return;
        final android.animation.ValueAnimator animator =
                android.animation.ValueAnimator.ofFloat(fromWidth, toWidth);
        animator.setDuration(REFLOW_MS);
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        animator.addUpdateListener(a -> {
            applyWidth(view, (float) a.getAnimatedValue());
            alignClockRow();   // 每帧重算冒号对齐与整行居中，全程保持居中
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                // 结束后交回 wrap_content，避免和字体实际宽度差出零点几像素
                android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
                lp.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                view.setLayoutParams(lp);
                reflows.remove(animator);
                alignClockRow();
            }
        });
        reflows.add(animator);
        animator.start();
    }

    /** 一段文字在当前字号下的排版宽度（wrap_content 时文本框就是这么宽）。 */
    private float textWidth(TextView view, String text) {
        return view.getPaint().measureText(text);
    }

    /** 极缓慢的像素级漂移：避免长时间静态显示烧屏（不影响亮度）。 */
    private void updateDrift() {
        // 极缓慢的像素级漂移：避免长时间静态显示烧屏（不影响亮度）
        if (!ssDragging) {
            // 拖动退出时不要和防烧屏漂移抢 translationY
            long elapsed = android.os.SystemClock.elapsedRealtime() - driftStart;
            float dx = (float) Math.sin(elapsed / 90000.0) * driftAmplitude;
            float dy = (float) Math.cos(elapsed / 130000.0) * driftAmplitude;
            root.setTranslationX(dx);
            root.setTranslationY(dy);
        }
    }

    private void render(PlayerUiState state) {
        if (state == null) return;
        // 封面变了就重新取色：屏保在前台时 MainActivity 处于暂停、不会更新调色板，
        // 所以换歌后时钟颜色会停在上一次的主色上。
        if (state.art != null && state.art != lastArt) {
            lastArt = state.art;
            ColorTheme.update(state.art);
        }
        applyColors();
    }

    private android.graphics.Bitmap lastArt;

    /** 全部颜色跟随当前封面（和时间无关，换歌时由状态回调重新上色）。 */
    private void applyColors() {
        int color = ColorTheme.sliderInactive();
        String fontRes = prefs.getScreensaverFont();
        if (color == lastClockColor && fontRes.equals(lastClockFont)) return;
        // 颜色 / 字体没变就直接返回：播放时状态每 500ms 推一次，每次都重排会把
        // 数字变化时的横移动画打断（动画跑一半被直接设成目标值 = 又变回瞬移）。
        lastClockColor = color;
        lastClockFont = fontRes;
        // 时间用设置页选的装饰字体（默认 Akronim）；冒号是同一个字体、同一个字号，
        // 只是单独一个文本框，方便竖直方向微调。
        android.graphics.Typeface typeface = com.airmusic.player.ui.ScreensaverFonts
                .typeface(this, fontRes);
        for (TextView view : clockViews()) {
            view.setTextColor(color);
            if (typeface != null) view.setTypeface(typeface);
        }
        refitClock();
    }

    private int lastClockColor;
    private String lastClockFont = "";

    /** 按当前字体 / 尺寸重新对齐并铺满（取色或屏幕尺寸变化时调用）。 */
    private void refitClock() {
        alignColon();
        fitClockToScreen();
    }

    /** 时钟的三个文本框（时 / 冒号 / 分）：字体、字号、颜色都一起改。 */
    private TextView[] clockViews() {
        return new TextView[]{hourView, colonView, minuteView};
    }

    /**
     * 让主时钟几乎占满屏幕：按当前文字（如 12:34）实测宽度，反推能放下的最大
     * 字号，再受可用高度限制（给下方歌名留出空间），像 Moven 那样铺满。
     */
    private void fitClockToScreen() {
        if (root == null || hourView == null) return;
        int availableWidth = root.getWidth() - root.getPaddingStart() - root.getPaddingEnd();
        if (availableWidth <= 0) {
            root.post(this::fitClockToScreen);
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        // 只有时钟一屏内容：先按宽度铺满，再看高度差多少
        int innerHeight = root.getHeight() - root.getPaddingTop() - root.getPaddingBottom();
        int availableHeight = Math.round(innerHeight * 0.74f);
        android.text.TextPaint paint = hourView.getPaint();
        android.graphics.Typeface typeface = hourView.getTypeface();
        if (typeface != null) paint.setTypeface(typeface);
        // 按「最宽的时间」反推字号（两个数字 + 冒号 + 两个数字）：
        // 单数字小时（12 小时制的 9:05）不会让字号忽大忽小，每天尺寸都稳定。
        float widthAtCurrent = paint.measureText("88") * 2f
                + paint.measureText(":");
        if (widthAtCurrent <= 0) return;
        float sizeForWidth = hourView.getTextSize() * (availableWidth * 0.79f) / widthAtCurrent;
        android.graphics.Paint.FontMetrics metrics = paint.getFontMetrics();
        float lineHeightRatio = (metrics.descent - metrics.ascent) / paint.getTextSize();
        float sizeForHeight = availableHeight / Math.max(0.5f, lineHeightRatio);
        float size = Math.min(sizeForWidth, sizeForHeight);
        size = Math.max(48f * density, size);   // 别缩得太小
        if (Math.abs(size - hourView.getTextSize()) > 1f) {
            for (TextView view : clockViews()) {
                view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size);
            }
        }
        // 时间在 16:9 屏上先顶到宽度上限，竖直方向还空着——像 Moven 那样把数字在
        // 竖直方向拉伸填满屏幕（有上限，避免过于夸张）。整行一起拉伸，冒号跟着走。
        clockRow.post(() -> {
            if (clockRow == null) return;
            int textHeight = clockRow.getHeight();
            if (textHeight <= 0 || innerHeight <= 0) return;
            float target = innerHeight * 0.70f;
            float scale = Math.min(1.25f, Math.max(1f, target / textHeight));
            // 竖直拉伸要以「数字墨迹中心」为支点：行盒（ascent~descent）的中心和
            // 数字墨迹中心并不重合，若以控件中心为支点，拉伸会把这个偏差放大
            // （实测 1080 高的屏上会偏上 18~24px，屏越高越明显）。
            android.graphics.Rect ink = new android.graphics.Rect();
            paint.getTextBounds("0", 0, 1, ink);
            // TextView.getBaseline() 直接给出基线在本控件内的位置，不用自己推
            // （hourView.getTop() 已经是相对这一行的坐标，不要再减 clockRow.getTop()）
            float inkCenterInRow = hourView.getTop() + hourView.getBaseline()
                    + (ink.top + ink.bottom) / 2f;
            clockRow.setPivotY(inkCenterInRow);
            clockRow.setScaleY(scale);
            // 注意：这里**不**再把墨迹中心硬补到屏幕正中。字体里部分数字是旧式
            // 下伸字形（"0123456789" 的墨迹范围是 -445~+89），数字不同、可见墨迹
            // 的重心本来就会上下浮动几十像素；按某个固定参照硬对中，换一组数字就
            // 会看着偏。支点定在墨迹中心只为一件事：竖直拉伸时不放大偏差。
            alignClockRow();
        });
    }

    /**
     * 时钟的水平对齐：字体里冒号这个字形在自己字宽里是偏右的（实测：左边距 46px、
     * 右边还越出字宽 21px），只按字宽排版会变成「左边离时 45px、右边压着分 -33px」。
     * 这里按墨迹（不是字宽）把冒号摆到两句数字正中间，并让整行墨迹保持居中。
     */
    private void alignClockRow() {
        if (hourView == null || colonView == null || minuteView == null) return;
        Paint paint = hourView.getPaint();
        android.graphics.Rect hourInk = new android.graphics.Rect();
        android.graphics.Rect colonInk = new android.graphics.Rect();
        android.graphics.Rect minuteInk = new android.graphics.Rect();
        paint.getTextBounds(hourView.getText().toString(), 0,
                hourView.getText().length(), hourInk);
        paint.getTextBounds(":", 0, 1, colonInk);
        paint.getTextBounds(minuteView.getText().toString(), 0,
                minuteView.getText().length(), minuteInk);
        // TextView 是 gravity=center：文字原点在控件内居中
        float hourOrigin = hourView.getLeft()
                + (hourView.getWidth() - paint.measureText(hourView.getText().toString())) / 2f;
        float colonOrigin = colonView.getLeft()
                + (colonView.getWidth() - paint.measureText(":")) / 2f;
        float minuteOrigin = minuteView.getLeft()
                + (minuteView.getWidth() - paint.measureText(minuteView.getText().toString())) / 2f;
        float hourLeft = hourOrigin + hourInk.left;
        float hourRight = hourOrigin + hourInk.right;
        float colonLeft = colonOrigin + colonInk.left;
        float colonRight = colonOrigin + colonInk.right;
        float minuteLeft = minuteOrigin + minuteInk.left;
        float minuteRight = minuteOrigin + minuteInk.right;
        // 左右两个间隙相等：冒号墨迹要平移的量就是两边间隙差的一半（取负往左移）
        float colonShift = -(((colonLeft - hourRight) - (minuteLeft - colonRight)) / 2f);
        colonView.setTranslationX(colonShift);
        // 整行墨迹重新居中：按「时」的墨迹左沿到「分」的墨迹右沿取中心
        float inkCenter = (hourLeft + minuteRight) / 2f;
        float rowShift = clockRow.getWidth() / 2f - inkCenter;
        clockRow.setTranslationX(rowShift);
        android.util.Log.i(TAG, "clock align: hour ink " + (int) hourLeft + ".."
                + (int) hourRight + " colon ink " + (int) colonLeft + ".." + (int) colonRight
                + " minute ink " + (int) minuteLeft + ".." + (int) minuteRight
                + " => colonShift=" + (int) colonShift + " rowShift=" + (int) rowShift
                + " gap=" + (int) ((colonLeft + colonShift) - hourRight) + "/"
                + (int) (minuteLeft - (colonRight + colonShift)));
    }

    /** 中文/日文/韩文字形用的衬线字体（assets 里已有，避免掉回系统字体）。 */
    private android.graphics.Typeface cjkTypeface() {
        if (cjkTypeface == null) {
            try {
                cjkTypeface = android.graphics.Typeface.createFromAsset(
                        getAssets(), "NotoSerifSC.otf");
            } catch (Throwable t) {
                cjkTypeface = null;
            }
        }
        return cjkTypeface;
    }

    private android.graphics.Typeface cjkTypeface;
}
