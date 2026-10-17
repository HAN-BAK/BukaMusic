package com.airmusic.player;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.airmusic.player.library.NetSource;
import com.airmusic.player.service.PlaybackService;
import com.airmusic.player.ui.BukaDialog;
import com.airmusic.player.ui.BukaNotice;
import com.airmusic.player.ui.BukaTheme;
import com.airmusic.player.ui.ColorTheme;
import com.airmusic.player.util.BlurBackground;
import com.airmusic.player.util.Prefs;

import java.util.List;

/**
 * 网络存储音乐路径的管理页：列出已添加的 WebDAV / SMB / FTP 路径，
 * 可以添加、点进去编辑（含账号密码与连接测试）、删除。
 */
public class NetworkPathsActivity extends BaseActivity {

    private LinearLayout list;
    private TextView hint;
    private Prefs prefs;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_network_paths);
        BlurBackground.apply(this, R.color.background);
        prefs = new Prefs(this);
        list = findViewById(R.id.net_paths_list);
        hint = findViewById(R.id.net_paths_hint);
        hint.setTextColor(ColorTheme.textSecondary());
        ImageButton back = findViewById(R.id.btn_back);
        back.setImageResource(R.drawable.ic_prev);
        back.setOnClickListener(v -> finish());
        findViewById(R.id.btn_add_net_path).setOnClickListener(v -> chooseType());
        TextView topTitle = findViewById(R.id.txt_title);
        topTitle.setTextColor(ColorTheme.tooltipText());
        TextView addButton = findViewById(R.id.btn_add_net_path);
        addButton.setTextColor(ColorTheme.textAccent());
        BukaTheme.tintView(this, findViewById(android.R.id.content));
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    /** 先选协议，再进编辑页填地址与账号。 */
    private void chooseType() {
        String[] labels = {
                getString(R.string.net_type_webdav),
                getString(R.string.net_type_smb),
                getString(R.string.net_type_ftp),
        };
        String[] types = {NetSource.TYPE_WEBDAV, NetSource.TYPE_SMB, NetSource.TYPE_FTP};
        BukaDialog.singleChoice(this, getString(R.string.net_path_type), labels, -1, which -> {
            Intent intent = new Intent(this, NetworkPathEditActivity.class);
            intent.putExtra(NetworkPathEditActivity.EXTRA_TYPE, types[which]);
            startActivity(intent);
        }).show();
    }

    private void render() {
        list.removeAllViews();
        List<NetSource> sources = prefs.getNetSources();
        hint.setVisibility(sources.isEmpty() ? View.VISIBLE : View.GONE);
        for (NetSource source : sources) {
            list.addView(row(source));
        }
    }

    private View row(final NetSource source) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(ColorTheme.softBlock(this));
        int padding = Math.round(16f * getResources().getDisplayMetrics().density);
        card.setPadding(padding, padding, padding, padding);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Math.round(10f * getResources().getDisplayMetrics().density);
        card.setLayoutParams(params);

        LinearLayout textBlock = new LinearLayout(this);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        textBlock.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText(source.displayName());
        title.setTextSize(16f);
        title.setTextColor(ColorTheme.tooltipText());
        textBlock.addView(title);

        TextView detail = new TextView(this);
        detail.setText(source.label());
        detail.setTextSize(13f);
        detail.setTextColor(ColorTheme.textSecondary());
        textBlock.addView(detail);
        card.addView(textBlock);

        TextView delete = new TextView(this);
        delete.setText(R.string.net_path_delete);
        delete.setTextSize(13f);
        delete.setTextColor(ColorTheme.tooltipText());
        delete.setPadding(padding, padding / 2, padding, padding / 2);
        // 同设置页：不透明深主色胶囊 + 近白文字，避免和浅色卡片融在一起
        android.graphics.drawable.GradientDrawable deletePill =
                new android.graphics.drawable.GradientDrawable();
        deletePill.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        deletePill.setColor(ColorTheme.tooltipFill());
        deletePill.setCornerRadius(20f * getResources().getDisplayMetrics().density);
        delete.setBackground(deletePill);
        // 和左边两行文字居中对齐（不贴底）
        delete.setGravity(android.view.Gravity.CENTER);
        delete.setOnClickListener(v -> confirmDelete(source));
        card.addView(delete);

        card.setOnClickListener(v -> {
            Intent intent = new Intent(this, NetworkPathEditActivity.class);
            intent.putExtra(NetworkPathEditActivity.EXTRA_ID, source.id);
            startActivity(intent);
        });
        return card;
    }

    private void confirmDelete(final NetSource source) {
        BukaDialog.confirm(this, getString(R.string.net_path_delete),
                getString(R.string.net_path_delete_confirm, source.displayName()),
                getString(android.R.string.ok),
                () -> {
                    prefs.removeNetSource(source.id);
                    com.airmusic.player.library.MusicLibrary.requestNetworkRefresh();
                    BukaNotice.show(this, getString(R.string.net_path_deleted,
                            source.displayName()));
                    render();
                    rescan();
                }).show();
    }

    private void rescan() {
        PlaybackService service = PlaybackService.getInstance();
        if (service != null) service.rescanLibrary();
    }
}
