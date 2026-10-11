package com.airmusic.player;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
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
    private TextView dateView;
    private TextView trackView;
    private TextView artistView;
    private TextView sourceView;
    private View root;

    private Prefs prefs;
    private boolean blinkOn = true;
    private long driftStart;
    private float driftAmplitude;

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
        dateView = findViewById(R.id.screensaver_date);
        trackView = findViewById(R.id.screensaver_track);
        artistView = findViewById(R.id.screensaver_artist);
        sourceView = findViewById(R.id.screensaver_source);
        root = findViewById(R.id.screensaver_root);
        sourceView.setBackground(ColorTheme.capsule(this));
        driftAmplitude = 6f * getResources().getDisplayMetrics().density;
        driftStart = android.os.SystemClock.elapsedRealtime();
        // 点一下退出屏保（与 ClockDesk 一样：点屏幕回到播放界面）
        root.setOnClickListener(v -> finish());
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
            if (!blinkOn) {
                text = text.replace(":", " ");
            }
        }
        timeView.setText(text);
        dateView.setVisibility(prefs.isScreensaverDate() ? View.VISIBLE : View.GONE);
        if (prefs.isScreensaverDate()) {
            dateView.setText(new SimpleDateFormat("M月d日 EEEE", Locale.getDefault()).format(now));
        }
        // 极缓慢的像素级漂移：避免长时间静态显示烧屏（不影响亮度）
        long elapsed = android.os.SystemClock.elapsedRealtime() - driftStart;
        float dx = (float) Math.sin(elapsed / 90000.0) * driftAmplitude;
        float dy = (float) Math.cos(elapsed / 130000.0) * driftAmplitude;
        root.setTranslationX(dx);
        root.setTranslationY(dy);
    }

    private void render(PlayerUiState state) {
        if (state == null) return;
        boolean showTrack = prefs.isScreensaverTrack();
        trackView.setVisibility(showTrack ? View.VISIBLE : View.GONE);
        artistView.setVisibility(showTrack ? View.VISIBLE : View.GONE);
        sourceView.setVisibility(showTrack ? View.VISIBLE : View.GONE);
        if (!showTrack) return;
        String title = state.title == null || state.title.trim().isEmpty()
                ? getString(R.string.unknown_title) : state.title.trim();
        String artist = state.artist == null || state.artist.trim().isEmpty()
                ? getString(R.string.unknown_artist) : state.artist.trim();
        trackView.setText(title);
        artistView.setText(artist);
        sourceView.setText(state.source == PlayerUiState.Source.AIRPLAY
                ? getString(R.string.source_airplay)
                : state.source == PlayerUiState.Source.REMOTE
                ? getString(R.string.source_remote) : getString(R.string.source_local));
        applyColors();
    }

    /** 全部颜色跟随当前封面（和时间无关，换歌时由状态回调重新上色）。 */
    private void applyColors() {
        timeView.setTextColor(ColorTheme.textAccent());
        dateView.setTextColor(ColorTheme.textSecondary());
        trackView.setTextColor(ColorTheme.tooltipText());
        artistView.setTextColor(ColorTheme.textSecondary());
        sourceView.setTextColor(ColorTheme.tooltipText());
        sourceView.setBackground(ColorTheme.capsule(this));
    }
}
