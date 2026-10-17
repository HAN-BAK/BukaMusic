package com.airmusic.player;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.airmusic.player.library.NetSource;
import com.airmusic.player.net.NetClient;
import com.airmusic.player.service.PlaybackService;
import com.airmusic.player.ui.BukaDialog;
import com.airmusic.player.ui.BukaNotice;
import com.airmusic.player.ui.BukaTheme;
import com.airmusic.player.util.BlurBackground;
import com.airmusic.player.util.Prefs;

import java.util.Locale;

/**
 * 添加 / 编辑一条网络音乐路径：WebDAV、SMB、FTP 都支持账号密码（SMB 另有域），
 * 保存前可以点"测试连接"直接试一次列目录。
 */
public class NetworkPathEditActivity extends BaseActivity {

    public static final String EXTRA_ID = "net_source_id";
    public static final String EXTRA_TYPE = "net_source_type";

    private Prefs prefs;
    private NetSource editing;
    private String type = NetSource.TYPE_WEBDAV;

    private com.google.android.material.button.MaterialButton typeButton;
    private EditText nameInput;
    private EditText hostInput;
    private EditText portInput;
    private EditText folderInput;
    private EditText userInput;
    private EditText passwordInput;
    private EditText domainInput;
    private TextView status;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_network_path_edit);
        BlurBackground.apply(this, R.color.background);
        prefs = new Prefs(this);

        typeButton = findViewById(R.id.btn_net_type);
        nameInput = findViewById(R.id.edit_net_name);
        hostInput = findViewById(R.id.edit_net_host);
        portInput = findViewById(R.id.edit_net_port);
        folderInput = findViewById(R.id.edit_net_folder);
        userInput = findViewById(R.id.edit_net_user);
        passwordInput = findViewById(R.id.edit_net_password);
        domainInput = findViewById(R.id.edit_net_domain);
        status = findViewById(R.id.txt_net_status);

        for (EditText input : new EditText[]{nameInput, hostInput, portInput, folderInput,
                userInput, passwordInput, domainInput}) {
            input.setBackground(com.airmusic.player.ui.ColorTheme.optionIdle(this));
        }

        String id = getIntent().getStringExtra(EXTRA_ID);
        if (id != null) editing = prefs.getNetSource(id);
        String requestedType = getIntent().getStringExtra(EXTRA_TYPE);
        if (editing != null) {
            type = editing.type;
            nameInput.setText(editing.name);
            hostInput.setText(editing.host);
            if (editing.port > 0) portInput.setText(String.valueOf(editing.port));
            folderInput.setText(editing.path);
            userInput.setText(editing.user);
            passwordInput.setText(editing.password);
            domainInput.setText(editing.domain);
            findViewById(R.id.btn_delete_net_path).setVisibility(View.VISIBLE);
            ((TextView) findViewById(R.id.txt_title)).setText(R.string.net_path_edit);
        } else if (requestedType != null) {
            type = requestedType;
        }
        findViewById(R.id.btn_delete_net_path).setOnClickListener(v -> confirmDelete());
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        typeButton.setOnClickListener(v -> chooseType());
        findViewById(R.id.btn_net_test).setOnClickListener(v -> testConnection());
        findViewById(R.id.btn_net_save).setOnClickListener(v -> save());
        applyType();
        BukaTheme.tintView(this, findViewById(android.R.id.content));
    }

    private void chooseType() {
        String[] labels = {
                getString(R.string.net_type_webdav),
                getString(R.string.net_type_smb),
                getString(R.string.net_type_ftp),
        };
        String[] types = {NetSource.TYPE_WEBDAV, NetSource.TYPE_SMB, NetSource.TYPE_FTP};
        int checked = 0;
        for (int i = 0; i < types.length; i++) {
            if (types[i].equals(type)) checked = i;
        }
        BukaDialog.singleChoice(this, getString(R.string.net_path_type), labels, checked, which -> {
            type = types[which];
            applyType();
        }).show();
    }

    /** 切换协议时更新按钮文字、端口/路径提示与"域"字段（只有 SMB 用得上）。 */
    private void applyType() {
        if (NetSource.TYPE_SMB.equals(type)) {
            typeButton.setText(R.string.net_type_smb);
            hostInput.setHint(R.string.net_path_host_smb);
            folderInput.setHint(R.string.net_path_folder_smb);
            domainInput.setVisibility(View.VISIBLE);
        } else if (NetSource.TYPE_FTP.equals(type)) {
            typeButton.setText(R.string.net_type_ftp);
            hostInput.setHint(R.string.net_path_host);
            folderInput.setHint(R.string.net_path_folder);
            domainInput.setVisibility(View.GONE);
        } else {
            typeButton.setText(R.string.net_type_webdav);
            hostInput.setHint(R.string.net_path_host);
            folderInput.setHint(R.string.net_path_folder);
            domainInput.setVisibility(View.GONE);
        }
        portInput.setHint(defaultPortHint());
    }

    private String defaultPortHint() {
        int port = 80;
        if (NetSource.TYPE_SMB.equals(type)) port = 445;
        else if (NetSource.TYPE_FTP.equals(type)) port = 21;
        return getString(R.string.net_path_port_hint, port);
    }

    private NetSource readForm() {
        String host = hostInput.getText().toString().trim();
        if (host.isEmpty()) {
            BukaNotice.show(this, R.string.net_path_need_host);
            return null;
        }
        IntHolder port = new IntHolder();
        if (!parsePort(portInput.getText().toString().trim(), port)) {
            BukaNotice.show(this, R.string.net_path_bad_port);
            return null;
        }
        String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) name = host;
        return new NetSource(editing == null ? NetSource.newId() : editing.id, type, name,
                host, port.value, folderInput.getText().toString().trim(),
                userInput.getText().toString().trim(),
                passwordInput.getText().toString(),
                domainInput.getText().toString().trim());
    }

    private static final class IntHolder {
        int value;
    }

    private boolean parsePort(String text, IntHolder out) {
        if (text.isEmpty()) {
            out.value = 0;
            return true;
        }
        try {
            int value = Integer.parseInt(text);
            if (value <= 0 || value > 65535) return false;
            out.value = value;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void save() {
        NetSource source = readForm();
        if (source == null) return;
        if (editing == null) prefs.addNetSource(source); else prefs.updateNetSource(source);
        com.airmusic.player.library.MusicLibrary.requestNetworkRefresh();
        BukaNotice.show(this, getString(R.string.net_path_saved, source.displayName()));
        rescan();
        finish();
    }

    private void testConnection() {
        final NetSource source = readForm();
        if (source == null) return;
        status.setText(R.string.net_path_testing);
        new Thread(() -> {
            String message;
            try {
                NetClient.test(source);
                message = getString(R.string.net_path_test_ok);
            } catch (Throwable t) {
                String detail = t.getMessage();
                message = getString(R.string.net_path_test_failed,
                        detail == null ? t.getClass().getSimpleName() : detail);
            }
            final String result = message;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                status.setText(result);
            });
        }, "net-path-test").start();
    }

    private void confirmDelete() {
        if (editing == null) return;
        BukaDialog.confirm(this, getString(R.string.net_path_delete),
                getString(R.string.net_path_delete_confirm, editing.displayName()),
                getString(android.R.string.ok), () -> {
                    prefs.removeNetSource(editing.id);
                    com.airmusic.player.library.MusicLibrary.requestNetworkRefresh();
                    BukaNotice.show(this, getString(R.string.net_path_deleted,
                            editing.displayName()));
                    rescan();
                    finish();
                }).show();
    }

    private void rescan() {
        PlaybackService service = PlaybackService.getInstance();
        if (service != null) service.rescanLibrary();
    }
}
