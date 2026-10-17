package com.airmusic.player;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.airmusic.player.service.PlaybackService;
import com.airmusic.player.util.BlurBackground;
import com.airmusic.player.util.DiagnosticLog;
import com.airmusic.player.ui.BukaNotice;
import com.airmusic.player.ui.BukaTheme;
import com.airmusic.player.ui.ColorTheme;

import java.io.File;

/**
 * "About" screen. Every link row copies its address to the clipboard instead
 * of opening a browser: a TV box has no convenient browser, while the copied
 * address can be pasted on a phone right away.
 */
public class AboutActivity extends BaseActivity {

    private static final String URL_DEVELOPER = "https://github.com/HAN-BAK";
    private static final String URL_PROJECT = "https://github.com/HAN-BAK/BukaMusic";
    private static final String URL_AIRRECEIVER = "https://github.com/phlo/airreceiver";
    private static final String URL_EXOPLAYER = "https://github.com/androidx/media";
    private static final String URL_JMDNS = "https://github.com/jmdns/jmdns";
    private static final String URL_NETTY = "https://netty.io/";
    private static final String URL_BOUNCYCASTLE = "https://www.bouncycastle.org/";
    private static final String URL_QR = "https://www.nayuki.io/page/qr-code-generator-library";
    private static final String URL_ANDROIDX = "https://github.com/androidx/androidx";

    /** 连续点版本号多少次才显示诊断。 */
    private static final int DIAGNOSTIC_TAPS = 20;
    /** 两次点击之间超过这个间隔就当作重新计数（"连续"点击）。 */
    private static final long TAP_TIMEOUT_MS = 1500L;
    private static final long STATUS_REFRESH_MS = 1500L;

    private View diagnosticsBlock;
    private TextView airplayStatus;
    private int versionTaps;
    private long lastVersionTapAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            refreshAirPlayStatus();
            handler.postDelayed(this, STATUS_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        // 全应用统一外观：卡片 + 行样式（与自定义对话框同一套）
        BlurBackground.apply(this, R.color.background);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        bindCopy(R.id.btn_github_developer, URL_DEVELOPER);
        bindCopy(R.id.btn_project, URL_PROJECT);
        bindCopy(R.id.btn_os_airreceiver, URL_AIRRECEIVER);
        bindCopy(R.id.btn_os_exoplayer, URL_EXOPLAYER);
        bindCopy(R.id.btn_os_jmdns, URL_JMDNS);
        bindCopy(R.id.btn_os_netty, URL_NETTY);
        bindCopy(R.id.btn_os_bc, URL_BOUNCYCASTLE);
        bindCopy(R.id.btn_os_qr, URL_QR);
        bindCopy(R.id.btn_os_androidx, URL_ANDROIDX);

        TextView txtVersion = findViewById(R.id.txt_version);
        try {
            String version = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            txtVersion.setText(getString(R.string.about_version, version));
        } catch (Exception e) {
            txtVersion.setText("");
        }
        setupDiagnostics(txtVersion);
    }

    /**
     * 诊断原本在「存储与设备」里，现在藏在关于页：连续点版本号 20 次才会出现。
     * 每次进入页面都要重新点满，避免误触。
     */
    private void setupDiagnostics(TextView txtVersion) {
        diagnosticsBlock = findViewById(R.id.diagnostics_block);
        airplayStatus = findViewById(R.id.airplay_status);
        if (diagnosticsBlock == null || airplayStatus == null) return;
        airplayStatus.setBackground(ColorTheme.softBlock(this));
        airplayStatus.setTextColor(ColorTheme.tooltipText());
        findViewById(R.id.btn_export_logs).setOnClickListener(v -> exportLogs());
        txtVersion.setOnClickListener(v -> {
            long now = SystemClock.elapsedRealtime();
            if (now - lastVersionTapAt > TAP_TIMEOUT_MS) versionTaps = 0;
            lastVersionTapAt = now;
            versionTaps++;
            if (versionTaps < DIAGNOSTIC_TAPS || diagnosticsBlock.getVisibility() == View.VISIBLE) {
                return;
            }
            diagnosticsBlock.setAlpha(0f);
            diagnosticsBlock.setVisibility(View.VISIBLE);
            diagnosticsBlock.animate().alpha(1f).setDuration(220L).start();
            refreshAirPlayStatus();
            BukaNotice.show(this, R.string.about_diagnostics_unlocked);
            // 展开后滚到底，免得用户以为没反应
            final android.widget.ScrollView scroll =
                    findViewById(R.id.about_scroll);
            if (scroll != null) {
                scroll.post(() -> scroll.smoothScrollBy(
                        0, diagnosticsBlock.getHeight() + (int) ColorTheme.dp(this, 24f)));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (diagnosticsBlock != null && diagnosticsBlock.getVisibility() == View.VISIBLE) {
            handler.removeCallbacks(statusTick);
            handler.post(statusTick);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(statusTick);
    }

    private void refreshAirPlayStatus() {
        if (airplayStatus == null) return;
        PlaybackService service = PlaybackService.getInstance();
        airplayStatus.setText(service != null
                ? service.getAirPlayStatus() : "service: not running");
    }

    /** 导出诊断日志（原本在设置页，随诊断一起搬过来）。 */
    private void exportLogs() {
        File logFile = DiagnosticLog.getLogFile(this);
        if (logFile == null) {
            BukaNotice.show(this, R.string.logs_empty);
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(
                    this, "com.airmusic.player.fileprovider", logFile);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_logs_share));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, getString(R.string.export_logs_share)));
        } catch (Exception e) {
            BukaNotice.show(this, R.string.export_logs_failed);
        }
    }

    /** Tapping a link row copies the address and confirms it with a toast. */
    private void bindCopy(int viewId, final String url) {
        View row = findViewById(viewId);
        if (row == null) return;
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> {
            try {
                ClipboardManager clipboard =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("url", url));
                }
            } catch (Throwable ignored) {
            }
            BukaNotice.show(this, getString(R.string.copied_link, url));
        });
    }
}
