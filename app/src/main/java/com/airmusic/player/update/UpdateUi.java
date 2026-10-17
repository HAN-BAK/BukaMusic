package com.airmusic.player.update;

import android.app.Activity;

import com.airmusic.player.R;
import com.airmusic.player.ui.BukaDialog;
import com.airmusic.player.ui.BukaNotice;
import com.airmusic.player.util.Prefs;

import java.io.File;

/** 更新检查 / 下载 / 安装的界面流程。 */
public final class UpdateUi {

    private static final long AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000;
    /** 同一次运行里同一个版本只弹一次，免得每次回播放页都弹。 */
    private static int lastPromptedVersion;
    /** 去系统设置授权「安装未知应用」后，回来自动继续下载的版本。 */
    private static UpdateChecker.Info pendingAfterPermission;

    private UpdateUi() {
    }

    /** 启动时静默检查：24 小时内查过就跳过，发现新版本才弹窗。 */
    public static void checkAutomatically(Activity activity) {
        Prefs prefs = new Prefs(activity);
        // 上次已经拉起过系统安装器：这里把下载的 apk 删掉（安装完成后进程是新的）
        if (prefs.isPendingUpdateCleanup()) {
            UpdateChecker.cleanDownloads(activity);
            prefs.setPendingUpdateCleanup(false);
        }
        long last = prefs.getLastUpdateCheck();
        if (System.currentTimeMillis() - last < AUTO_INTERVAL_MS) return;
        prefs.setLastUpdateCheck(System.currentTimeMillis());
        check(activity, false);
    }

    /** 设置里手动点「检查更新」：无论结果都给提示。 */
    public static void checkManually(Activity activity) {
        new Prefs(activity).setLastUpdateCheck(System.currentTimeMillis());
        check(activity, true);
    }

    private static void check(final Activity activity, final boolean manual) {
        UpdateChecker.check((info, error) -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (info == null) {
                if (manual) BukaNotice.show(activity, R.string.update_check_failed);
                return;
            }
            int current = UpdateChecker.currentVersionCode(activity);
            if (info.versionCode <= current) {
                if (manual) {
                    BukaNotice.show(activity,
                            activity.getString(R.string.update_latest,
                                    UpdateChecker.currentVersionName(activity)));
                }
                return;
            }
            // 自动检查同一个版本只弹一次；手动点「检查更新」必须每次都给反馈，
            // 否则刚启动时弹过一次，再去设置里点就什么都不出现了。
            if (!manual && info.versionCode == lastPromptedVersion) return;
            lastPromptedVersion = info.versionCode;
            showAvailable(activity, info);
        });
    }

    private static void showAvailable(final Activity activity, final UpdateChecker.Info info) {
        String title = activity.getString(R.string.update_available_title, info.versionName);
        String message = info.notes == null || info.notes.isEmpty()
                ? activity.getString(R.string.update_available_hint) : info.notes;
        BukaDialog.confirm(activity, title, message,
                activity.getString(R.string.update_now),
                () -> download(activity, info)).show();
    }

    private static void download(final Activity activity, final UpdateChecker.Info info) {
        // Android 8+ 没有运行时的"安装应用"弹窗，只能由用户去系统设置里打开开关；
        // 所以在下载前就检查，避免先下 30MB 再失败。
        if (!UpdateChecker.canInstall(activity)) {
            showPermissionDialog(activity, info);
            return;
        }
        final BukaDialog dialog = BukaDialog.loading(activity,
                activity.getString(R.string.update_downloading, 0));
        dialog.show();
        final android.content.Context app = activity.getApplicationContext();
        new Thread(() -> {
            try {
                File apk = UpdateChecker.download(app, info, percent -> {
                    activity.runOnUiThread(() -> {
                        if (!activity.isFinishing()) {
                            dialog.setLoadingText(
                                    activity.getString(R.string.update_downloading, percent));
                        }
                    });
                    return true;
                });
                activity.runOnUiThread(() -> {
                    dialog.dismiss();
                    if (activity.isFinishing()) return;
                    // 标记：下一次启动时删除这个安装包
                    new Prefs(activity).setPendingUpdateCleanup(true);
                    int result = UpdateChecker.install(activity, apk);
                    if (result == UpdateChecker.INSTALL_NEED_PERMISSION) {
                        showPermissionDialog(activity, info);
                    } else if (result == UpdateChecker.INSTALL_NO_INSTALLER) {
                        BukaNotice.show(activity, R.string.update_no_installer);
                    }
                });
            } catch (Throwable t) {
                android.util.Log.w("UpdateUi", "download failed", t);
                activity.runOnUiThread(() -> {
                    dialog.dismiss();
                    if (!activity.isFinishing()) {
                        BukaNotice.show(activity, R.string.update_download_failed);
                    }
                });
            }
        }, "update-download").start();
    }

    /** 没有"安装未知应用"权限：说明原因并给一个去设置的按钮。 */
    private static void showPermissionDialog(final Activity activity,
                                             final UpdateChecker.Info info) {
        BukaDialog.confirm(activity,
                activity.getString(R.string.update_permission_title),
                activity.getString(R.string.update_permission_message),
                activity.getString(R.string.update_permission_open),
                () -> {
                    pendingAfterPermission = info;
                    if (!UpdateChecker.openUnknownSourcesSettings(activity)) {
                        pendingAfterPermission = null;
                        BukaNotice.show(activity, R.string.update_permission_manual);
                    }
                }).show();
    }

    /**
     * 每个页面 onResume 时调用：从「安装未知应用」设置页回来、并且已经授权时，
     * 自动接着下载刚发现的版本。
     */
    public static void onActivityResume(Activity activity) {
        if (pendingAfterPermission == null) return;
        if (!UpdateChecker.canInstall(activity)) return;
        UpdateChecker.Info info = pendingAfterPermission;
        pendingAfterPermission = null;
        download(activity, info);
    }
}
