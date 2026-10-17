package com.airmusic.player.library;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 一条本地音乐路径：要么是本机文件系统路径（{@link #path}），要么是 SAF 目录树
 * （{@link #uri}，部分设备的文件管理器只给 content:// 权限）。
 */
public final class MusicPath {

    /** 本机路径，可能为 null。 */
    public final String path;
    /** SAF 目录树 uri，可能为 null。 */
    public final String uri;
    /** 列表里显示的名字。 */
    public final String display;

    public MusicPath(String path, String uri, String display) {
        this.path = path;
        this.uri = uri;
        this.display = display;
    }

    /** 唯一标识，用于删除某一条。 */
    public String id() {
        return path != null && !path.isEmpty() ? "p:" + path : "u:" + uri;
    }

    /** 列表里显示的一行文字。 */
    public String label() {
        if (display != null && !display.isEmpty()) return display;
        return path != null && !path.isEmpty() ? path : String.valueOf(uri);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        if (path != null) json.put("path", path);
        if (uri != null) json.put("uri", uri);
        if (display != null) json.put("display", display);
        return json;
    }

    public static MusicPath fromJson(JSONObject json) {
        if (json == null) return null;
        String path = json.optString("path", null);
        String uri = json.optString("uri", null);
        String display = json.optString("display", null);
        if ((path == null || path.isEmpty()) && (uri == null || uri.isEmpty())) return null;
        return new MusicPath(path, uri, display);
    }
}
