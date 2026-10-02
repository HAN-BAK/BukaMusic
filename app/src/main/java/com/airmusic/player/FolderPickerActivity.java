package com.airmusic.player;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.airmusic.player.library.AudioExt;
import com.airmusic.player.util.BlurBackground;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import com.airmusic.player.ui.BukaNotice;
import com.airmusic.player.ui.BukaTheme;

/**
 * A lightweight, self-contained folder browser used to pick the local music
 * path. It browses internal storage and USB volumes directly via the
 * filesystem, so it works on devices that ship without a system file manager.
 */
public class FolderPickerActivity extends BaseActivity {

    public static final String EXTRA_RESULT_PATH = "result_path";
    public static final String EXTRA_RESULT_DISPLAY = "result_display";
    /** 选文件模式：只列该后缀的文件，点文件即返回。 */
    public static final String EXTRA_PICK_FILE = "pick_file";
    public static final String EXTRA_FILE_SUFFIX = "file_suffix";
    public static final String EXTRA_RESULT_IS_FILE = "result_is_file";

    private TextView pathText;
    private TextView statusText;
    private Button btnGrant;
    private ListView listView;
    private Button btnSelect;
    /** 选文件模式（均衡器导入预设用）：列出指定后缀的文件，点文件即返回。 */
    private boolean pickFile;
    private String fileSuffix;

    private final List<FolderEntry> entries = new ArrayList<>();
    private final List<File> roots = new ArrayList<>();
    private final List<String> rootLabels = new ArrayList<>();
    private ArrayAdapter<FolderEntry> adapter;
    private File currentDir;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), granted -> {
                // Android 10 及以下同时要读写：选目录后还要在里面建文件夹。
                if (granted.containsValue(Boolean.FALSE)) {
                    showStatus(getString(R.string.folder_permission_denied));
                } else {
                    loadRoots();
                }
            });

    private static class FolderEntry {
        final File dir;
        final String name;
        final String sub;
        final boolean isFile;

        FolderEntry(File dir, String name, String sub) {
            this(dir, name, sub, false);
        }

        FolderEntry(File dir, String name, String sub, boolean isFile) {
            this.dir = dir;
            this.name = name;
            this.sub = sub;
            this.isFile = isFile;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_folder_picker);
        // 全应用统一外观：卡片 + 行样式（与自定义对话框同一套）
        BlurBackground.apply(this, R.color.background);

        pathText = findViewById(R.id.path_text);
        // 路径框用和按钮同一套动态取色（描边+淡底），不再是那块写死的蓝框
        pathText.setBackground(com.airmusic.player.ui.ColorTheme.capsule(this));
        pathText.setTextColor(com.airmusic.player.ui.ColorTheme.tooltipText());
        statusText = findViewById(R.id.status_text);
        btnGrant = findViewById(R.id.btn_grant);
        listView = findViewById(R.id.folder_list);
        btnSelect = findViewById(R.id.btn_select);
        pickFile = getIntent().getBooleanExtra(EXTRA_PICK_FILE, false);
        fileSuffix = getIntent().getStringExtra(EXTRA_FILE_SUFFIX);
        if (pickFile) {
            ((android.widget.TextView) findViewById(R.id.folder_title))
                    .setText(R.string.folder_pick_file);
            btnSelect.setEnabled(false);
        }

        adapter = new ArrayAdapter<FolderEntry>(this, R.layout.item_folder, R.id.item_name, entries) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                FolderEntry entry = getItem(position);
                TextView name = view.findViewById(R.id.item_name);
                TextView sub = view.findViewById(R.id.item_sub);
                View card = view.findViewById(R.id.item_card);
                android.widget.ImageView icon = view.findViewById(R.id.item_icon);
                name.setText(entry.name);
                sub.setText(entry.sub);
                icon.setImageResource(entry.isFile ? R.drawable.ic_row_logs : R.drawable.ic_folder);
                if (card != null) {
                    card.setBackground(com.airmusic.player.ui.ColorTheme.softBlock(view.getContext()));
                }
                if (icon != null) {
                    // 图标做成主色淡淡的圆底，和曲库 / 设置页的观感一致
                    android.graphics.drawable.GradientDrawable chip =
                            new android.graphics.drawable.GradientDrawable();
                    chip.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                    chip.setColor(com.airmusic.player.ui.ColorTheme.fill());
                    icon.setBackground(chip);
                    icon.setImageTintList(android.content.res.ColorStateList.valueOf(
                            com.airmusic.player.ui.ColorTheme.accent()));
                }
                return view;
            }
        };
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            FolderEntry entry = entries.get(position);
            if (entry.isFile) {
                Intent data = new Intent();
                data.putExtra(EXTRA_RESULT_PATH, entry.dir.getAbsolutePath());
                data.putExtra(EXTRA_RESULT_DISPLAY, entry.name);
                data.putExtra(EXTRA_RESULT_IS_FILE, true);
                setResult(Activity.RESULT_OK, data);
                finish();
            } else {
                openFolder(entry.dir);
            }
        });

        btnSelect.setOnClickListener(v -> confirmSelection());
        btnGrant.setOnClickListener(v -> openAllFilesSettings());
        findViewById(R.id.btn_back).setOnClickListener(v -> navigateUp());

        if (hasStorageAccess()) {
            loadRoots();
        } else {
            requestStoragePermission();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from the "All files access" settings screen.
        if (hasStorageAccess() && roots.isEmpty() && currentDir == null) {
            loadRoots();
        }
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            showStatus(getString(R.string.folder_need_permission));
            btnGrant.setVisibility(View.VISIBLE);
        } else {
            btnGrant.setVisibility(View.GONE);
            showStatus(getString(R.string.folder_need_permission));
            // The picker also creates folders, so on Android 10 and below the
            // write permission has to be part of the same request.
            permissionLauncher.launch(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE});
        }
    }

    private void openAllFilesSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ex) {
                BukaNotice.show(this, R.string.folder_permission_denied);
            }
        }
    }

    private void showStatus(String text) {
        statusText.setVisibility(View.VISIBLE);
        statusText.setText(text);
    }

    private void loadRoots() {
        btnGrant.setVisibility(View.GONE);
        statusText.setVisibility(View.GONE);
        currentDir = null;
        roots.clear();
        rootLabels.clear();

        File primary = Environment.getExternalStorageDirectory();
        if (primary != null && primary.isDirectory()) {
            roots.add(primary);
            rootLabels.add(getString(R.string.folder_internal_storage));
        }

        // USB / removable volumes show up as mount points under /storage.
        File storageDir = new File("/storage");
        if (storageDir.isDirectory()) {
            File[] mounts = storageDir.listFiles();
            if (mounts != null) {
                Arrays.sort(mounts, (a, b) -> a.getName().compareTo(b.getName()));
                for (File mount : mounts) {
                    if (!mount.isDirectory() || mount.getName().startsWith(".")) continue;
                    if (primary != null && (mount.equals(primary)
                            || mount.getAbsolutePath().startsWith(primary.getAbsolutePath() + "/"))) {
                        continue;
                    }
                    roots.add(mount);
                    rootLabels.add(labelForVolume(mount));
                }
            }
        }

        if (roots.isEmpty()) {
            showStatus(getString(R.string.folder_permission_denied));
            return;
        }

        entries.clear();
        for (int i = 0; i < roots.size(); i++) {
            File root = roots.get(i);
            entries.add(new FolderEntry(root, rootLabels.get(i), root.getAbsolutePath()));
        }
        pathText.setText(getString(R.string.folder_pick_hint));
        btnSelect.setEnabled(false);
        adapter.notifyDataSetChanged();
    }

    private String labelForVolume(File mount) {
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
                if (sm != null) {
                    for (StorageVolume volume : sm.getStorageVolumes()) {
                        String uuid = volume.getUuid();
                        if (uuid != null && uuid.equalsIgnoreCase(mount.getName())) {
                            String desc = volume.getDescription(this);
                            if (desc != null && desc.trim().length() > 0) {
                                return desc;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return getString(R.string.folder_usb_storage) + " (" + mount.getName() + ")";
    }

    private void openFolder(File dir) {
        if (dir == null || !dir.isDirectory()) {
            BukaNotice.show(this, R.string.folder_permission_denied);
            return;
        }
        currentDir = dir;
        pathText.setText(dir.getAbsolutePath());
        entries.clear();
        File[] files = dir.listFiles();
        if (files != null) {
            Arrays.sort(files, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                return a.getName().toLowerCase(Locale.US)
                        .compareTo(b.getName().toLowerCase(Locale.US));
            });
            for (File f : files) {
                if (f.isDirectory() && !f.isHidden()) {
                    int count = countAudioFiles(f);
                    String sub = count > 0
                            ? getString(R.string.folder_audio_count, count)
                            : getString(R.string.folder_empty);
                    entries.add(new FolderEntry(f, f.getName(), sub));
                } else if (pickFile && f.isFile() && !f.isHidden()
                        && (fileSuffix == null
                            || f.getName().toLowerCase(Locale.US)
                                    .endsWith(fileSuffix.toLowerCase(Locale.US)))) {
                    entries.add(new FolderEntry(f, f.getName(),
                            getString(R.string.folder_pick_file_tap), true));
                }
            }
        }
        if (entries.isEmpty()) {
            showStatus(getString(R.string.folder_empty));
        } else {
            statusText.setVisibility(View.GONE);
        }
        btnSelect.setEnabled(true);
        adapter.notifyDataSetChanged();
    }

    /** Counts audio files directly inside a folder (cheap, non-recursive). */
    private static int countAudioFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int count = 0;
        for (File f : files) {
            if (f.isFile() && AudioExt.isAudio(f.getName())) count++;
        }
        return count;
    }

    private void navigateUp() {
        if (currentDir == null) {
            finish();
            return;
        }
        File parent = currentDir.getParentFile();
        if (parent != null && parent.isDirectory() && isInsideRoots(parent)) {
            openFolder(parent);
        } else {
            loadRoots();
        }
    }

    private boolean isInsideRoots(File dir) {
        String path = dir.getAbsolutePath();
        for (File root : roots) {
            if (path.equals(root.getAbsolutePath()) || path.startsWith(root.getAbsolutePath() + "/")) {
                return true;
            }
        }
        return false;
    }

    private void confirmSelection() {
        if (currentDir == null) return;
        Intent data = new Intent();
        data.putExtra(EXTRA_RESULT_PATH, currentDir.getAbsolutePath());
        data.putExtra(EXTRA_RESULT_DISPLAY, currentDir.getAbsolutePath());
        setResult(Activity.RESULT_OK, data);
        finish();
    }

    @Override
    public void onBackPressed() {
        navigateUp();
    }
}
