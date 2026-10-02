package com.airmusic.player;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.airmusic.player.util.BlurBackground;
import com.airmusic.player.ui.BukaNotice;
import com.airmusic.player.ui.BukaTheme;

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

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        // 全应用统一外观：卡片 + 行样式（与自定义对话框同一套）
        BukaTheme.applyPageContent(this);
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
