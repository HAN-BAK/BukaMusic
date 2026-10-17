package com.airmusic.player.library;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 一条网络音乐路径（WebDAV / SMB / FTP）。
 *
 * <p>曲目里的 uri 用自定义 scheme {@code buka-net://<sourceId>/<相对路径>}，
 * 真正的地址与账号在做数据源时按 id 查回来——账号密码不进入 uri，避免被日志、
 * 通知、桌面控制台接口带出去。
 */
public final class NetSource {

    public static final String TYPE_WEBDAV = "webdav";
    public static final String TYPE_SMB = "smb";
    public static final String TYPE_FTP = "ftp";

    public static final String SCHEME = "buka-net";

    public final String id;
    /** webdav / smb / ftp */
    public final String type;
    /** 显示名，同时作为曲目里的"文件夹"分组名。 */
    public final String name;
    public final String host;
    /** 0 表示用该协议的默认端口。 */
    public final int port;
    /** 服务器上的起始目录，可为空（表示根）。 */
    public final String path;
    public final String user;
    public final String password;
    /** SMB 的域 / 工作组，可为空。 */
    public final String domain;

    public NetSource(String id, String type, String name, String host, int port,
                     String path, String user, String password, String domain) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.host = host;
        this.port = port;
        this.path = path;
        this.user = user;
        this.password = password;
        this.domain = domain;
    }

    public static String newId() {
        return "s" + Long.toHexString(System.currentTimeMillis())
                + Integer.toHexString((int) (Math.random() * 0xFFFF));
    }

    public int effectivePort() {
        if (port > 0) return port;
        switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
            case TYPE_WEBDAV:
                return 80;
            case TYPE_FTP:
                return 21;
            default:
                return 445;
        }
    }

    public boolean hasCredentials() {
        return user != null && !user.isEmpty();
    }

    /** 列表里显示的一行（不含密码）。 */
    public String label() {
        StringBuilder sb = new StringBuilder();
        sb.append(typeLabel()).append("  ·  ").append(host);
        int defaultPort = "webdav".equals(type) ? 80 : ("ftp".equals(type) ? 21 : 445);
        if (effectivePort() != defaultPort) sb.append(':').append(effectivePort());
        if (path != null && !path.isEmpty()) {
            sb.append(path.startsWith("/") ? "" : "/").append(path);
        }
        if (hasCredentials()) sb.append("  ·  ").append(user);
        return sb.toString();
    }

    public String typeLabel() {
        switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
            case TYPE_WEBDAV:
                return "WebDAV";
            case TYPE_FTP:
                return "FTP";
            default:
                return "SMB";
        }
    }

    public String displayName() {
        return name != null && !name.isEmpty() ? name : host;
    }

    /** 该源里某个相对路径对应的曲目 uri。 */
    public android.net.Uri trackUri(String relativePath) {
        String encoded = android.net.Uri.encode(relativePath == null ? "" : relativePath);
        return android.net.Uri.parse(SCHEME + "://" + id + "/" + encoded);
    }

    /** 从曲目 uri 里取回相对路径（未解码的原始串）。 */
    public static String relativePathOf(android.net.Uri uri) {
        if (uri == null) return null;
        String path = uri.getPath();
        return path == null ? "" : path;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("type", type);
        json.put("name", name == null ? "" : name);
        json.put("host", host == null ? "" : host);
        json.put("port", port);
        json.put("path", path == null ? "" : path);
        json.put("user", user == null ? "" : user);
        json.put("password", password == null ? "" : password);
        json.put("domain", domain == null ? "" : domain);
        return json;
    }

    public static NetSource fromJson(JSONObject json) {
        if (json == null) return null;
        String host = json.optString("host", "");
        if (host.isEmpty()) return null;
        String type = json.optString("type", TYPE_SMB);
        String id = json.optString("id", "");
        if (id.isEmpty()) id = newId();
        return new NetSource(id, type, json.optString("name", ""), host,
                json.optInt("port", 0), json.optString("path", ""),
                json.optString("user", ""), json.optString("password", ""),
                json.optString("domain", ""));
    }
}
