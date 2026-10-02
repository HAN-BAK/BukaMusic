package com.airmusic.player;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.airmusic.player.service.PlaybackService;
import com.airmusic.player.util.BlurBackground;
import com.airmusic.player.util.DiagnosticLog;
import com.airmusic.player.util.Prefs;
import com.airmusic.player.util.StorageHelper;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import com.airmusic.player.ui.BukaNotice;
import com.airmusic.player.ui.BukaDialog;
import com.airmusic.player.ui.BukaTheme;

public class SettingsActivity extends BaseActivity {

    private Prefs prefs;
    private TextInputEditText inputName;
    private TextView pathDisplay;
    private com.google.android.material.button.MaterialButton btnPlayMode;
    private Switch switchAutoPlay;
    private Switch switchOnlineLyrics;
    private Switch switchShowApps;
    private com.google.android.material.button.MaterialButton btnBlurMode;
    private SeekBar seekBalance;
    private TextView airplayStatus;
    private TextView txtStorageInfo;
    private com.google.android.material.button.MaterialButton btnTransfer;
    private View btnEqualizer;

    private final ActivityResultLauncher<Intent> folderPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String path = result.getData().getStringExtra(FolderPickerActivity.EXTRA_RESULT_PATH);
                    if (path != null) {
                        String display = result.getData().getStringExtra(
                                FolderPickerActivity.EXTRA_RESULT_DISPLAY);
                        if (display == null) display = path;
                        // Auto-save the folder and rescan immediately.
                        prefs.setMusicFolderPath(path, display);
                        pathDisplay.setText(formatFolderDisplay(display, path));
                        PlaybackService svc = PlaybackService.getInstance();
                        if (svc != null) svc.rescanLibrary();
                    }
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        // 全应用统一外观：卡片 + 行样式（与自定义对话框同一套）
        BlurBackground.apply(this, R.color.background);

        prefs = new Prefs(this);
        inputName = findViewById(R.id.input_airplay_name);
        pathDisplay = findViewById(R.id.path_display);
        btnPlayMode = findViewById(R.id.btn_play_mode);
        switchAutoPlay = findViewById(R.id.switch_auto_play);
        switchOnlineLyrics = findViewById(R.id.switch_online_lyrics);
        switchShowApps = findViewById(R.id.switch_show_apps);
        btnBlurMode = findViewById(R.id.btn_blur_mode);
        seekBalance = findViewById(R.id.seek_balance);
        airplayStatus = findViewById(R.id.airplay_status);
        txtStorageInfo = findViewById(R.id.txt_storage_info);
        btnTransfer = findViewById(R.id.btn_transfer);
        btnEqualizer = findViewById(R.id.btn_equalizer);

        inputName.setText(prefs.getAirPlayName());
        String folderPath = prefs.getMusicFolderPath();
        String folderUri = prefs.getMusicFolderUri();
        String folderDisplay = prefs.getMusicFolderDisplay();
        if (folderPath != null) {
            pathDisplay.setText(formatFolderDisplay(folderDisplay, folderPath));
        } else if (folderUri != null) {
            pathDisplay.setText(formatFolderDisplay(folderDisplay, folderUri));
        }

        setupPlayModeButton();
        switchAutoPlay.setChecked(prefs.isAutoPlayOnStart());
        switchAutoPlay.setOnCheckedChangeListener((b, checked) ->
                prefs.setAutoPlayOnStart(checked));
        switchOnlineLyrics.setChecked(prefs.isOnlineLyrics());
        switchOnlineLyrics.setOnCheckedChangeListener((b, checked) ->
                prefs.setOnlineLyrics(checked));
        switchShowApps.setChecked(prefs.isShowAppsButton());
        switchShowApps.setOnCheckedChangeListener((b, checked) ->
                prefs.setShowAppsButton(checked));
        setupBlurModeButton();

        inputName.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String name = s.toString().trim();
                if (name.length() > 0) {
                    prefs.setAirPlayName(name);
                }
            }
        });
        inputName.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                PlaybackService service = PlaybackService.getInstance();
                if (service != null) service.restartAirPlay();
            }
        });

        setupTabs();
        applyIcons();
        seekBalance.setProgress((int) ((prefs.getBalance() + 1f) * 100f));
        seekBalance.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    PlaybackService service = PlaybackService.getInstance();
                    if (service != null) service.setBalance((progress / 100f) - 1f);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        findViewById(R.id.btn_reset_balance).setOnClickListener(v -> {
            seekBalance.setProgress(100);
            prefs.setBalance(0f);
            PlaybackService service = PlaybackService.getInstance();
            if (service != null) service.setBalance(0f);
            BukaNotice.show(this, R.string.balance_reset);
        });

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_choose_folder).setOnClickListener(v ->
                folderPicker.launch(new Intent(this, FolderPickerActivity.class)));
        findViewById(R.id.btn_clear_path).setOnClickListener(v -> {
            prefs.clearMusicFolder();
            pathDisplay.setText(R.string.pref_music_path_hint);
            PlaybackService service = PlaybackService.getInstance();
            if (service != null) service.rescanLibrary();
            BukaNotice.show(this, R.string.path_cleared);
        });

        findViewById(R.id.btn_rescan).setOnClickListener(v -> {
            PlaybackService service = PlaybackService.getInstance();
            if (service != null) service.rescanLibrary();
            BukaNotice.show(this, R.string.rescanning);
        });
        findViewById(R.id.btn_set_home).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
            } catch (Exception e) {
                BukaNotice.show(this, R.string.home_settings_unavailable);
            }
        });
        findViewById(R.id.btn_wifi_settings).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            } catch (Exception e) {
                BukaNotice.show(this, R.string.home_settings_unavailable);
            }
        });
        findViewById(R.id.btn_equalizer).setOnClickListener(v ->
                startActivity(new Intent(this, EqualizerActivity.class)));
        btnTransfer.setOnClickListener(v -> {
            // Low disk space: refuse to open the transfer page.
            if (!StorageHelper.hasEnoughSpace(prefs.getMusicFolderPath(), 0)) {
                BukaNotice.show(this, R.string.storage_low_warning, BukaNotice.LONG);
                return;
            }
            startActivity(new Intent(this, TransferActivity.class));
        });
        findViewById(R.id.btn_export_logs).setOnClickListener(v -> exportLogs());
        setupLanguageButton();
        findViewById(R.id.btn_about).setOnClickListener(v ->
                startActivity(new Intent(this, AboutActivity.class)));
        // Replays the spotlight tour; it highlights the playback screen, so it
        // closes settings first.
        findViewById(R.id.btn_show_tour).setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(MainActivity.EXTRA_SHOW_TOUR, true));
        });
        refreshStorageInfo();
    }

    @Override
    protected void onResume() {
        super.onResume();
        BlurBackground.apply(this, R.color.background);
        refreshAirPlayStatus();
        refreshStorageInfo();
        // The equalizer now uses a dedicated instance for the multi-room
        // output, so it can stay enabled while multi-room is active.
        btnEqualizer.setEnabled(true);
        btnEqualizer.setAlpha(1f);
    }

    private void refreshStorageInfo() {
        String folder = prefs.getMusicFolderPath();
        long total = StorageHelper.getTotalBytes(folder);
        long usable = StorageHelper.getUsableBytes(folder);
        txtStorageInfo.setText(getString(R.string.storage_info,
                StorageHelper.formatSize(total), StorageHelper.formatSize(usable)));
        boolean enough = StorageHelper.hasEnoughSpace(folder, 0);
        btnTransfer.setAlpha(enough ? 1f : 0.45f);
        btnTransfer.setClickable(true);
    }

    private static final int[] TAB_TITLES = {
            R.string.settings_section_airplay,
            R.string.settings_section_local,
            R.string.settings_section_ui,
            R.string.settings_section_system,
            R.string.settings_section_about,
    };
    private static final int[] TAB_SECTIONS = {
            R.id.section_airplay, R.id.section_local, R.id.section_ui,
            R.id.section_system, R.id.section_about,
    };
    private static final int[] TAB_CONTENTS = {
            R.id.airplay_content, R.id.local_content, R.id.ui_content,
            R.id.system_content, R.id.about_content,
    };

    private final java.util.List<TextView> tabViews = new java.util.ArrayList<>();
    private int currentTab;

    /**
     * 设置页改成选项卡：顶部常驻一排标签（不随内容滚动），点哪个显示哪一组；
     * 原来的分组标题隐掉，内容本身不作改动。
     */
    private void setupTabs() {
        View back = findViewById(R.id.btn_back);
        if (back == null || !(back.getParent() instanceof View)) return;
        View topBar = (View) back.getParent();
        if (!(topBar.getParent() instanceof ViewGroup)) return;
        ViewGroup page = (ViewGroup) topBar.getParent();

        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        int pad = Math.round(14f * getResources().getDisplayMetrics().density);
        bar.setPadding(pad, Math.round(6f * getResources().getDisplayMetrics().density),
                pad, Math.round(2f * getResources().getDisplayMetrics().density));
        scroller.addView(bar, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < TAB_TITLES.length; i++) {
            final int index = i;
            TextView tab = new TextView(this);
            tab.setText(TAB_TITLES[i]);
            tab.setTextSize(14f);
            tab.setGravity(android.view.Gravity.CENTER);
            int hPad = Math.round(16f * getResources().getDisplayMetrics().density);
            int vPad = Math.round(9f * getResources().getDisplayMetrics().density);
            tab.setPadding(hPad, vPad, hPad, vPad);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) params.setMarginStart(Math.round(8f * getResources().getDisplayMetrics().density));
            tab.setLayoutParams(params);
            tab.setOnClickListener(v -> selectTab(index));
            com.airmusic.player.ui.PressFx.attach(tab);
            bar.addView(tab);
            tabViews.add(tab);
        }
        // 插在顶栏下面：滚动内容时标签栏不动
        page.addView(scroller, Math.min(1, page.getChildCount()));
        // 原来的分组标题不再需要
        for (int id : TAB_SECTIONS) {
            View header = findViewById(id);
            if (header != null) header.setVisibility(View.GONE);
        }
        selectTab(0);
        com.airmusic.player.ui.BukaTheme.tintButtons(this);
    }

    private void selectTab(int index) {
        if (index < 0 || index >= TAB_CONTENTS.length) return;
        currentTab = index;
        for (int i = 0; i < TAB_CONTENTS.length; i++) {
            View content = findViewById(TAB_CONTENTS[i]);
            if (content != null) {
                content.setVisibility(i == index ? View.VISIBLE : View.GONE);
            }
        }
        for (int i = 0; i < tabViews.size(); i++) {
            TextView tab = tabViews.get(i);
            boolean selected = i == index;
            tab.setTextColor(selected
                    ? com.airmusic.player.ui.ColorTheme.accent()
                    : getResources().getColor(R.color.text_secondary));
            tab.setBackground(selected
                    ? com.airmusic.player.ui.ColorTheme.capsule(this)
                    : null);
        }
    }

    /** 给各类选项 / 按钮加线性图标（颜色跟随动态主色）。 */
    private void applyIcons() {
        // 选项卡也带图标
        int[] tabIcons = {
                R.drawable.ic_row_wifi, R.drawable.ic_row_autoplay, R.drawable.ic_row_apps,
                R.drawable.ic_row_storage, R.drawable.ic_row_info,
        };
        for (int i = 0; i < tabViews.size() && i < tabIcons.length; i++) {
            com.airmusic.player.ui.BukaIcons.row(tabViews.get(i), tabIcons[i]);
        }
        // 按钮
        com.airmusic.player.ui.BukaIcons.row(findViewById(R.id.btn_wifi_settings),
                R.drawable.ic_row_wifi);
        com.airmusic.player.ui.BukaIcons.row(findViewById(R.id.btn_transfer),
                R.drawable.ic_row_transfer);
        com.airmusic.player.ui.BukaIcons.row(findViewById(R.id.btn_choose_folder),
                R.drawable.ic_row_folder);
        com.airmusic.player.ui.BukaIcons.row(findViewById(R.id.btn_clear_path),
                R.drawable.ic_row_rescan);
        com.airmusic.player.ui.BukaIcons.row(findViewById(R.id.btn_play_mode),
                R.drawable.ic_row_playmode);
        com.airmusic.player.ui.BukaIcons.row(findViewById(R.id.btn_language),
                R.drawable.ic_row_language);
        com.airmusic.player.ui.BukaIcons.row(findViewById(R.id.btn_blur_mode),
                R.drawable.ic_row_blur);
        com.airmusic.player.ui.BukaIcons.row(findViewById(R.id.btn_set_home),
                R.drawable.ic_row_launcher);
        com.airmusic.player.ui.BukaIcons.row(findViewById(R.id.btn_export_logs),
                R.drawable.ic_row_logs);
        com.airmusic.player.ui.BukaIcons.row(findViewById(R.id.btn_rescan),
                R.drawable.ic_row_rescan);
        com.airmusic.player.ui.BukaIcons.row(findViewById(R.id.btn_about),
                R.drawable.ic_row_info);
        com.airmusic.player.ui.BukaIcons.row(findViewById(R.id.btn_show_tour),
                R.drawable.ic_row_device);
        // 开关 / 滑条 / 信息行：给行标签加图标
        com.airmusic.player.ui.BukaIcons.rowLabel(findViewById(R.id.switch_auto_play),
                R.drawable.ic_row_autoplay);
        com.airmusic.player.ui.BukaIcons.rowLabel(findViewById(R.id.switch_online_lyrics),
                R.drawable.ic_row_lyrics);
        com.airmusic.player.ui.BukaIcons.rowLabel(findViewById(R.id.switch_show_apps),
                R.drawable.ic_row_apps);
        com.airmusic.player.ui.BukaIcons.rowLabel(findViewById(R.id.seek_balance),
                R.drawable.ic_row_balance);
        com.airmusic.player.ui.BukaIcons.rowLabel(findViewById(R.id.path_display),
                R.drawable.ic_row_folder);
        // 均衡器入口用动态图标（点击时播放动画，动画在原有的点击处理里启动）
        View eq = findViewById(R.id.btn_equalizer);
        if (eq instanceof TextView) {
            com.airmusic.player.ui.BukaIcons.animated((TextView) eq, R.drawable.ic_anim_equalizer);
            eq.setOnClickListener(v -> {
                com.airmusic.player.ui.BukaIcons.play((TextView) v);
                startActivity(new Intent(this, EqualizerActivity.class));
            });
        }
    }

    private void refreshAirPlayStatus() {
        PlaybackService service = PlaybackService.getInstance();
        if (service != null) {
            airplayStatus.setText(service.getAirPlayStatus());
        } else {
            airplayStatus.setText("service: not running");
        }
    }

    private void exportLogs() {
        File logFile = DiagnosticLog.getLogFile(this);
        if (logFile == null) {
            BukaNotice.show(this, R.string.logs_empty);
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, "com.airmusic.player.fileprovider", logFile);
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

    private void setupLanguageButton() {
        com.google.android.material.button.MaterialButton btnLanguage =
                findViewById(R.id.btn_language);
        updateLanguageLabel(btnLanguage);
        btnLanguage.setOnClickListener(v -> {
            String[] langs = {"zh", "en", "ja", "ko"};
            String[] labels = {
                    getString(R.string.language_zh),
                    getString(R.string.language_en),
                    getString(R.string.language_ja),
                    getString(R.string.language_ko)
            };
            String current = prefs.getLanguage();
            int checked = 0;
            for (int i = 0; i < langs.length; i++) {
                if (langs[i].equals(current)) checked = i;
            }
            BukaDialog.singleChoice(this, getString(R.string.language_dialog_title), labels,
                    checked, which -> {
                        prefs.setLanguage(langs[which]);
                        PlaybackService service = PlaybackService.getInstance();
                        if (service != null) {
                            // The service may have been running since boot in
                            // the old language; rebuild its UI strings now.
                            service.applyUiLanguage();
                        }
                        updateLanguageLabel(btnLanguage);
                        // Recreate this screen and let the other activities
                        // refresh themselves when they resume.
                        recreate();
                    }).show();
        });
    }

    /**
     * 播放方式：与「语言」一致的行式 UI —— 左侧标题，右侧按钮显示当前值，
     * 点开用自定义单选对话框选择（原来是 4 个单选框）。
     */
    private void setupPlayModeButton() {
        updatePlayModeLabel();
        btnPlayMode.setOnClickListener(v -> {
            String[] modes = {
                    Prefs.PLAY_MODE_SEQUENCE,
                    Prefs.PLAY_MODE_FOLDER_LOOP,
                    Prefs.PLAY_MODE_SHUFFLE,
                    Prefs.PLAY_MODE_REPEAT_ONE
            };
            String[] labels = {
                    getString(R.string.mode_sequence),
                    getString(R.string.mode_folder_loop),
                    getString(R.string.mode_shuffle),
                    getString(R.string.mode_repeat_one)
            };
            String current = prefs.getPlayMode();
            int checked = 0;
            for (int i = 0; i < modes.length; i++) {
                if (modes[i].equals(current)) checked = i;
            }
            BukaDialog.singleChoice(this, getString(R.string.pref_play_mode), labels, checked,
                    which -> {
                        prefs.setPlayMode(modes[which]);
                        PlaybackService service = PlaybackService.getInstance();
                        if (service != null) service.applyPlayMode(modes[which]);
                        updatePlayModeLabel();
                    }).show();
        });
    }

    private void updatePlayModeLabel() {
        String mode = prefs.getPlayMode();
        if (Prefs.PLAY_MODE_FOLDER_LOOP.equals(mode)) {
            btnPlayMode.setText(R.string.mode_folder_loop);
        } else if (Prefs.PLAY_MODE_SHUFFLE.equals(mode)) {
            btnPlayMode.setText(R.string.mode_shuffle);
        } else if (Prefs.PLAY_MODE_REPEAT_ONE.equals(mode)) {
            btnPlayMode.setText(R.string.mode_repeat_one);
        } else {
            btnPlayMode.setText(R.string.mode_sequence);
        }
    }

    private void setupBlurModeButton() {
        updateBlurModeLabel();
        btnBlurMode.setOnClickListener(v -> {
            String[] modes = {Prefs.BLUR_DARK, Prefs.BLUR_OFF};
            String[] labels = {
                    getString(R.string.blur_mode_dark),
                    getString(R.string.blur_mode_off)
            };
            String current = prefs.getBlurMode();
            int checked = 0;
            for (int i = 0; i < modes.length; i++) {
                if (modes[i].equals(current)) checked = i;
            }
            BukaDialog.singleChoice(this, getString(R.string.blur_dialog_title), labels, checked,
                    which -> {
                        prefs.setBlurMode(modes[which]);
                        BlurBackground.apply(SettingsActivity.this, R.color.background);
                        updateBlurModeLabel();
                    }).show();
        });
    }

    private void updateBlurModeLabel() {
        String mode = prefs.getBlurMode();
        String label;
        if (Prefs.BLUR_OFF.equals(mode)) label = getString(R.string.blur_mode_off);
        else label = getString(R.string.blur_mode_dark);
        btnBlurMode.setText(label);
    }

    private void updateLanguageLabel(com.google.android.material.button.MaterialButton btn) {
        String lang = prefs.getLanguage();
        String label;
        if ("en".equals(lang)) label = getString(R.string.language_en);
        else if ("ja".equals(lang)) label = getString(R.string.language_ja);
        else if ("ko".equals(lang)) label = getString(R.string.language_ko);
        else label = getString(R.string.language_zh);
        btn.setText(label);
    }

    /** Shows the folder display name and path on one line when they are identical. */
    private String formatFolderDisplay(String display, String path) {
        if (display == null || display.trim().length() == 0 || display.equals(path)) {
            return path;
        }
        return display + "\n" + path;
    }

}
