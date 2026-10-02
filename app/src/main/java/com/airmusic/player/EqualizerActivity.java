package com.airmusic.player;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.airmusic.player.service.PlaybackService;
import com.airmusic.player.util.Prefs;
import com.airmusic.player.util.BlurBackground;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import com.airmusic.player.ui.BukaNotice;
import com.airmusic.player.ui.BukaDialog;
import com.airmusic.player.ui.BukaTheme;

public class EqualizerActivity extends BaseActivity {

    private static final String[] BAND_LABELS = {
            "31.5", "63", "125", "250", "500", "1K", "2K", "4K", "8K", "16K"
    };

    private Prefs prefs;
    private final SeekBar[] sliders = new SeekBar[10];
    private final TextView[] values = new TextView[10];
    private double[] gains = new double[10];

    private final ActivityResultLauncher<Intent> exportLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) writeExport(uri);
                }
            });

    private final ActivityResultLauncher<Intent> importLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) readImport(uri);
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_equalizer);
        // 全应用统一外观：卡片 + 行样式（与自定义对话框同一套）
        BukaTheme.applyPageContent(this);
        BlurBackground.apply(this, R.color.background);

        prefs = new Prefs(this);
        gains = prefs.getEqGains();

        LinearLayout container = findViewById(R.id.eq_bands);
        for (int i = 0; i < 10; i++) {
            container.addView(buildBandRow(i));
        }
        refreshAllValues();

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_save_preset).setOnClickListener(v -> savePreset());
        findViewById(R.id.btn_load_preset).setOnClickListener(v -> loadPreset());
        findViewById(R.id.btn_delete_preset).setOnClickListener(v -> deletePreset());
        findViewById(R.id.btn_export_presets).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "buka_eq_presets.json");
            exportLauncher.launch(intent);
        });
        findViewById(R.id.btn_import_presets).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            importLauncher.launch(intent);
        });
        findViewById(R.id.btn_reset_eq).setOnClickListener(v -> {
            BukaDialog.confirm(this, getString(R.string.equalizer_reset),
                    getString(R.string.equalizer_reset_confirm),
                    getString(android.R.string.ok), () -> {
                        gains = new double[10];
                        refreshAllValues();
                        applyGains();
                    }).show();
        });
    }

    private View buildBandRow(final int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, 4, 0, 4);

        TextView label = new TextView(this);
        label.setText(BAND_LABELS[index] + "Hz");
        label.setTextColor(getColor(R.color.text_secondary));
        label.setTextSize(13);
        row.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        SeekBar bar = new SeekBar(this);
        bar.setMax(240); // -12 .. +12 dB, 0.1 dB steps
        bar.setProgress((int) Math.round((gains[index] + 12) * 10));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                0, dp(48), 3.0f));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    gains[index] = Math.round((progress / 10.0 - 12.0) * 10.0) / 10.0;
                    values[index].setText(String.format(Locale.US, "%+.1f", gains[index]));
                    applyGains();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        row.addView(bar);

        TextView value = new TextView(this);
        value.setTextColor(getColor(R.color.text_primary));
        value.setTextSize(13);
        value.setGravity(android.view.Gravity.END);
        row.addView(value, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        sliders[index] = bar;
        values[index] = value;
        return row;
    }

    private void refreshAllValues() {
        for (int i = 0; i < 10; i++) {
            sliders[i].setProgress((int) Math.round((gains[i] + 12) * 10));
            values[i].setText(String.format(Locale.US, "%+.1f", gains[i]));
        }
    }

    private void applyGains() {
        PlaybackService service = PlaybackService.getInstance();
        if (service != null) {
            service.setEqualizerGains(gains.clone());
        } else {
            prefs.setEqGains(gains);
        }
    }

    private void savePreset() {
        String defaultName = nextPresetName();
        BukaDialog.input(this, getString(R.string.equalizer_save_preset),
                getString(R.string.equalizer_preset_name_hint), defaultName,
                getString(android.R.string.ok), value -> {
                    String name = value == null ? "" : value.trim();
                    if (prefs.addEqPreset(name, gains)) {
                        BukaNotice.show(this, R.string.equalizer_saved);
                    } else {
                        BukaNotice.show(this, R.string.equalizer_name_exists);
                    }
                }).show();
    }

    /** Returns the next unused "预设N" name (localized). */
    private String nextPresetName() {
        java.util.List<String> names = prefs.getEqPresetNames();
        int n = 1;
        while (names.contains(getString(R.string.equalizer_default_name, n))) {
            n++;
        }
        return getString(R.string.equalizer_default_name, n);
    }

    private void loadPreset() {
        final List<String> names = prefs.getEqPresetNames();
        if (names.isEmpty()) {
            BukaNotice.show(this, R.string.equalizer_no_presets);
            return;
        }
        BukaDialog.singleChoice(this, getString(R.string.equalizer_load_preset),
                names.toArray(new String[0]), -1, which -> {
                    gains = prefs.getEqPresetGains(names.get(which));
                    refreshAllValues();
                    applyGains();
                }).show();
    }

    private void deletePreset() {
        final List<String> names = prefs.getEqPresetNames();
        if (names.isEmpty()) {
            BukaNotice.show(this, R.string.equalizer_no_presets);
            return;
        }
        BukaDialog.singleChoice(this, getString(R.string.equalizer_delete_preset),
                names.toArray(new String[0]), -1, which -> {
                    prefs.removeEqPreset(names.get(which));
                    BukaNotice.show(this, R.string.equalizer_deleted);
                }).show();
    }

    private void writeExport(Uri uri) {
        try {
            OutputStreamWriter w = new OutputStreamWriter(
                    getContentResolver().openOutputStream(uri, "w"), StandardCharsets.UTF_8);
            w.write(prefs.exportEqPresets());
            w.flush();
            w.close();
        } catch (Throwable t) {
            BukaNotice.show(this, R.string.equalizer_export_failed);
        }
    }

    private void readImport(Uri uri) {
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    getContentResolver().openInputStream(uri), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            int n = prefs.importEqPresets(sb.toString());
            BukaNotice.show(this, getString(R.string.equalizer_imported) + " (" + n + ")");
        } catch (Throwable t) {
            BukaNotice.show(this, R.string.equalizer_import_failed);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
