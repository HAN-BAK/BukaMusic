package com.airmusic.player.update;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 自动更新：从缤纷云 S4 的 {@code Android/latest.json} 读取最新版本信息，
 * 需要时下载同目录下的安装包并交给系统安装器。
 *
 * <p>存储桶是私有的，所以这里用访问密钥在本地生成 S3 SigV4 预签名 URL
 * （只对 GET 生效，链接一小时过期），不必把桶设成公开读。
 */
public final class UpdateChecker {

    private static final String ENDPOINT = "https://s3.bitiful.net";
    private static final String HOST = "s3.bitiful.net";
    private static final String BUCKET = "bukamusic";
    private static final String REGION = "cn-east-1";
    private static final String ACCESS_KEY = "Y0kOrrw8wOAo2OfyWbSrbKlp";
    private static final String SECRET_KEY = "88oHn5aqdHcwkE0w0nmrK1Wipj8DxA5";
    private static final String KEY_LATEST = "Android/latest.json";
    private static final long URL_TTL_SECONDS = 3600L;

    /** latest.json 的内容。 */
    public static final class Info {
        public final int versionCode;
        public final String versionName;
        public final String key;
        public final String notes;
        public final long size;

        Info(int versionCode, String versionName, String key, String notes, long size) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.key = key;
            this.notes = notes;
            this.size = size;
        }
    }

    public interface Callback {
        void onResult(Info info, String error);
    }

    public interface Progress {
        /** @return false 表示用户取消 */
        boolean onProgress(int percent);
    }

    private UpdateChecker() {
    }

    /** 当前安装包的 versionCode。 */
    public static int currentVersionCode(Context context) {
        try {
            return (int) context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
        } catch (Throwable t) {
            return 0;
        }
    }

    public static String currentVersionName(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 后台线程读取 latest.json。 */
    public static void check(final Callback callback) {
        new Thread(() -> {
            Info info = null;
            String error = null;
            try {
                String json = readText(presign(KEY_LATEST));
                JSONObject o = new JSONObject(json);
                info = new Info(o.optInt("versionCode", 0),
                        o.optString("versionName", ""),
                        o.optString("key", ""),
                        o.optString("notes", ""),
                        o.optLong("size", 0L));
            } catch (Throwable t) {
                error = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final Info result = info;
            final String err = error;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(
                    () -> callback.onResult(result, err));
        }, "update-check").start();
    }

    /**
     * 下载安装包到应用私有目录，返回文件；失败抛异常。
     */
    public static File download(Context context, Info info, Progress progress) throws Exception {
        if (info == null || info.key.isEmpty()) throw new Exception("empty info");
        File dir = new File(context.getFilesDir(), "update");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("mkdir failed");
        // 上一版下载的文件不再需要
        File[] old = dir.listFiles();
        if (old != null) {
            for (File file : old) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
        File out = new File(dir, "BukaMusic-" + info.versionName + ".apk");
        File part = new File(dir, "update.part");
        HttpURLConnection conn = (HttpURLConnection) new URL(presign(info.key)).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        android.util.Log.i("UpdateChecker", "download " + info.key + " -> HTTP " + code);
        if (code != HttpURLConnection.HTTP_OK) {
            String body = "";
            try (InputStream err = conn.getErrorStream()) {
                if (err != null) {
                    java.io.ByteArrayOutputStream errBuf = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[2048];
                    int n;
                    while ((n = err.read(buf)) > 0) errBuf.write(buf, 0, n);
                    body = new String(errBuf.toByteArray(), StandardCharsets.UTF_8);
                }
            } catch (Throwable ignored) {
            }
            conn.disconnect();
            throw new Exception("HTTP " + code + " " + body);
        }
        long total = -1;
        try (InputStream in = conn.getInputStream();
             FileOutputStream fos = new FileOutputStream(part)) {
            total = info.size > 0 ? info.size : conn.getContentLengthLong();
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int lastPercent = -1;
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int percent = (int) (done * 100 / total);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        if (progress != null && !progress.onProgress(percent)) {
                            throw new Exception("cancelled");
                        }
                    }
                }
            }
            fos.getFD().sync();
        } catch (Exception e) {
            conn.disconnect();
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        }
        conn.disconnect();
        if (out.exists() && !out.delete()) throw new Exception("delete old failed");
        if (!part.renameTo(out)) throw new Exception("rename failed");
        return out;
    }

    /**
     * 清掉已经下载过的安装包。
     *
     * <p>系统安装器点完"安装"后应用会被替换、进程重启，收不到"安装完成"的回调；
     * 所以拉起安装器前先置一个待清理标记，下一次启动时清空 update 目录。
     */
    public static void cleanDownloads(Context context) {
        File dir = new File(context.getFilesDir(), "update");
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        android.util.Log.i("UpdateChecker", "update dir cleaned");
    }

    /** install() 的结果。 */
    public static final int INSTALL_LAUNCHED = 0;
    public static final int INSTALL_NEED_PERMISSION = 1;
    public static final int INSTALL_NO_INSTALLER = 2;

    /**
     * 拉起系统安装界面。
     *
     * @return {@link #INSTALL_LAUNCHED} / {@link #INSTALL_NEED_PERMISSION} /
     *         {@link #INSTALL_NO_INSTALLER}
     */
    public static int install(Context context, File apk) {
        boolean allowed = canInstall(context);
        if (!allowed) {
            // 有的电视盒子没有"安装未知应用"这一页，先试着直接拉起安装器；
            // 被系统拦下时再看下面的提示。
        }
        Uri uri = FileProvider.getUriForFile(context,
                "com.airmusic.player.fileprovider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            return INSTALL_LAUNCHED;
        } catch (Throwable t) {
            android.util.Log.w("UpdateChecker", "install intent failed", t);
            return allowed ? INSTALL_NO_INSTALLER : INSTALL_NEED_PERMISSION;
        }
    }

    /** 系统是否已经允许本应用安装 APK（Android 8+ 是「安装未知应用」开关）。 */
    public static boolean canInstall(Context context) {
        return Build.VERSION.SDK_INT < 26
                || context.getPackageManager().canRequestPackageInstalls();
    }

    /** 打开"安装未知应用 / 未知来源"设置页，尽量适配不同 ROM。 */
    public static boolean openUnknownSourcesSettings(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                context.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Throwable ignored) {
            }
        }
        for (String action : new String[]{Settings.ACTION_SECURITY_SETTINGS,
                Settings.ACTION_APPLICATION_SETTINGS}) {
            try {
                context.startActivity(new Intent(action)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // S3 SigV4 预签名（GET）
    // ------------------------------------------------------------------

    private static String readText(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        try (InputStream in = conn.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    static String presign(String key) {
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            String amzDate = fmt.format(new Date());
            String date = amzDate.substring(0, 8);
            String scope = date + "/" + REGION + "/s3/aws4_request";
            String credential = ACCESS_KEY + "/" + scope;
            String canonicalUri = "/" + BUCKET + "/" + encodePath(key);
            String canonicalQuery = "X-Amz-Algorithm=AWS4-HMAC-SHA256"
                    + "&X-Amz-Credential=" + encode(credential)
                    + "&X-Amz-Date=" + amzDate
                    + "&X-Amz-Expires=" + URL_TTL_SECONDS
                    + "&X-Amz-SignedHeaders=host";
            String canonicalRequest = "GET\n" + canonicalUri + "\n" + canonicalQuery + "\n"
                    + "host:" + HOST + "\n\nhost\nUNSIGNED-PAYLOAD";
            String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                    + sha256Hex(canonicalRequest);
            byte[] signingKey = hmac(("AWS4" + SECRET_KEY).getBytes(StandardCharsets.UTF_8), date);
            signingKey = hmac(signingKey, REGION);
            signingKey = hmac(signingKey, "s3");
            signingKey = hmac(signingKey, "aws4_request");
            String signature = hex(hmac(signingKey, stringToSign));
            return ENDPOINT + canonicalUri + "?" + canonicalQuery
                    + "&X-Amz-Signature=" + signature;
        } catch (Exception e) {
            throw new IllegalStateException("presign failed", e);
        }
    }

    private static byte[] hmac(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return hex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    private static String encodePath(String key) {
        StringBuilder sb = new StringBuilder();
        for (String part : key.split("/", -1)) {
            if (sb.length() > 0) sb.append('/');
            sb.append(encode(part));
        }
        return sb.toString();
    }

    /** RFC 3986：只保留 A-Z a-z 0-9 - _ . ~，其余按 UTF-8 百分号编码。 */
    private static String encode(String value) {
        StringBuilder sb = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format(Locale.US, "%02X", c));
            }
        }
        return sb.toString();
    }
}
