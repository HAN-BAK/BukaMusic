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
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(colonFade);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onDestroy();
    }

    /** 时间 / 日期 / 防烧屏位移。 */
    private void updateClock() {
        Date now = new Date();
        boolean h24 = prefs.isScreensaver24h();
        Locale locale = Locale.getDefault();
        // 时 / 分分开写进两个文本框，中间夹一个独立的冒号文本框（只有它做渐隐渐显）。
        hourView.setText(new SimpleDateFormat(h24 ? "HH" : "h", locale).format(now));
        minuteView.setText(new SimpleDateFormat("mm", locale).format(now));
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
        // 时钟颜色用拖动条「竖条右边那一段」的浅色（和未播放段同色）
        int color = ColorTheme.sliderInactive();
        // 时间用设置页选的装饰字体（默认 Akronim）；冒号是同一个字体、同一个字号，
        // 只是单独一个文本框，方便竖直方向微调。
        android.graphics.Typeface typeface = com.airmusic.player.ui.ScreensaverFonts
                .typeface(this, prefs.getScreensaverFont());
        for (TextView view : clockViews()) {
            view.setTextColor(color);
            if (typeface != null) view.setTypeface(typeface);
        }
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
            clockRow.setScaleY(scale);
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
