package com.airmusic.player;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

    private TextView timeView;
    private View root;

    private Prefs prefs;
    private boolean blinkOn = true;
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

    private final StateBus.Listener stateListener = state -> runOnUiThread(() -> render(state));

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_screensaver);
        prefs = new Prefs(this);
        timeView = findViewById(R.id.screensaver_time);
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
    }

    @Override
    protected void onPause() {
        super.onPause();
        StateBus.get().removeListener(stateListener);
        handler.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(ticker);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onDestroy();
    }

    /** 时间 / 日期 / 防烧屏位移。 */
    private void updateClock() {
        Date now = new Date();
        boolean h24 = prefs.isScreensaver24h();
        String pattern = h24 ? "HH:mm" : "h:mm";
        String text = new SimpleDateFormat(pattern, Locale.getDefault()).format(now);
        if (prefs.isScreensaverBlink() && text.contains(":")) {
            blinkOn = !blinkOn;
        }
        // 冒号闪烁不能改变文字宽度：以前把 ":" 换成空格（更窄），居中显示时
        // 两侧数字会左右跳。现在保留冒号、只把它的颜色设成透明。
        CharSequence display = text;
        if (!blinkOn) {
            android.text.SpannableString span = new android.text.SpannableString(text);
            int index = text.indexOf(':');
            if (index >= 0) {
                span.setSpan(new android.text.style.ForegroundColorSpan(0x00000000),
                        index, index + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            display = span;
        }
        timeView.setText(display);
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
        timeView.setTextColor(ColorTheme.sliderInactive());
        // 时间用设置页选的装饰字体（默认 Akronim）；歌名/歌手可能是中文、日文、
        // 韩文，装饰字体没有这些字形会掉回系统字体（看着很突兀），所以统一用
        // 工程里打包的中文衬线字体，字形和整体观感更接近。
        android.graphics.Typeface typeface = com.airmusic.player.ui.ScreensaverFonts
                .typeface(this, prefs.getScreensaverFont());
        if (typeface != null) {
            timeView.setTypeface(typeface);
        }
        fitClockToScreen();
    }

    /**
     * 让主时钟几乎占满屏幕：按当前文字（如 12:34）实测宽度，反推能放下的最大
     * 字号，再受可用高度限制（给下方歌名留出空间），像 Moven 那样铺满。
     */
    private void fitClockToScreen() {
        if (root == null || timeView == null) return;
        int availableWidth = root.getWidth() - root.getPaddingStart() - root.getPaddingEnd();
        if (availableWidth <= 0) {
            root.post(this::fitClockToScreen);
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        // 只有时钟一屏内容：先按宽度铺满，再看高度差多少
        int innerHeight = root.getHeight() - root.getPaddingTop() - root.getPaddingBottom();
        int availableHeight = Math.round(innerHeight * 0.74f);
        android.text.TextPaint paint = timeView.getPaint();
        float widthAtCurrent = paint.measureText(timeView.getText().toString());
        if (widthAtCurrent <= 0) return;
        float sizeForWidth = timeView.getTextSize() * (availableWidth * 0.79f) / widthAtCurrent;
        android.graphics.Paint.FontMetrics metrics = paint.getFontMetrics();
        float lineHeightRatio = (metrics.descent - metrics.ascent) / paint.getTextSize();
        float sizeForHeight = availableHeight / Math.max(0.5f, lineHeightRatio);
        float size = Math.min(sizeForWidth, sizeForHeight);
        size = Math.max(48f * density, size);   // 别缩得太小
        if (Math.abs(size - timeView.getTextSize()) > 1f) {
            timeView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size);
        }
        // 5 个字符的时间在 16:9 屏上先顶到宽度上限，竖直方向还空着——像 Moven 那样
        // 把数字在竖直方向拉伸填满屏幕（有上限，避免过于夸张）。
        timeView.post(() -> {
            int textHeight = timeView.getHeight();
            if (textHeight <= 0 || innerHeight <= 0) return;
            float target = innerHeight * 0.70f;
            float scale = Math.min(1.25f, Math.max(1f, target / textHeight));
            timeView.setScaleY(scale);
        });
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
