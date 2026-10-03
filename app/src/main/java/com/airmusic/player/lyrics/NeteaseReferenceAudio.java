package com.airmusic.player.lyrics;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 从网易云取「参考音频」，供 AirPlay 歌词位置识别做互相关。
 *
 * <p>只取得到公开可播的音频时才返回文件；取不到（无版权、需要登录、返回网页等）
 * 一律返回 null —— 按约定这种情况歌词页直接显示「暂未找到歌词」，不做手动对齐。
 * 缓存上限与淘汰由 {@link ReferenceAudioCache} 负责（60MB）。
 */
public final class NeteaseReferenceAudio {

    private static final String TAG = "RefAudio";
    /** 单首参考音频上限，避免异常响应把缓存撑爆。 */
    private static final int MAX_BYTES = 30 * 1024 * 1024;
    /** 音频地址（网易云外链）。 */
    private static final String OUTER_URL = "https://music.163.com/song/media/outer/url?id=";
    /** 取真实播放地址的接口（外链经常 403，这里拿直链再下）。 */
    private static final String PLAYER_API =
            "https://music.163.com/api/song/enhance/player/url?ids=[%d]&br=320000&id=%d";

    private NeteaseReferenceAudio() {
    }

    /** 缓存键：同一首歌（歌名 + 歌手）复用同一份参考音频。 */
    public static String keyOf(String title, String artist) {
        return (title == null ? "" : title.trim())
                + "|" + (artist == null ? "" : artist.trim());
    }

    /**
     * 取参考音频（阻塞调用，请在后台线程使用）。
     *
     * @return 缓存里的文件；取不到返回 null
     */
    public static File fetch(Context context, String title, String artist, long durationMs) {
        if (context == null || title == null || title.trim().isEmpty()) return null;
        Context appContext = context.getApplicationContext();
        String key = keyOf(title, artist);
        File cached = ReferenceAudioCache.fileFor(appContext, key);
        if (cached.exists() && cached.length() > 0) return cached;

        long id = OnlineLyrics.bestMatchId(title, artist, durationMs);
        if (id < 0) {
            Log.d(TAG, "no netease match for \"" + title + "\"");
            return null;
        }
        String direct = resolveUrl(id);
        byte[] data = direct == null ? null : download(direct);
        if (data == null || data.length == 0) {
            // 退一步试外链
            data = download(OUTER_URL + id);
        }
        if (data == null || data.length == 0) {
            Log.d(TAG, "download failed for id " + id);
            return null;
        }
        File file = ReferenceAudioCache.put(appContext, key, data);
        Log.d(TAG, "ref audio ready: id=" + id + " bytes=" + data.length
                + " cache=" + ReferenceAudioCache.size(appContext));
        return file;
    }

    /** 下载字节并做基本校验（HTML/文本一律视为失败）。 */
    /** 用播放地址接口拿直链；失败返回 null。 */
    private static String resolveUrl(long id) {
        HttpURLConnection connection = null;
        try {
            String api = String.format(PLAYER_API, id, id);
            connection = (HttpURLConnection) new URL(api).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            applyHeaders(connection);
            if (connection.getResponseCode() != 200) return null;
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                org.json.JSONObject json = new org.json.JSONObject(out.toString("UTF-8"));
                org.json.JSONArray data = json.optJSONArray("data");
                if (data == null || data.length() == 0) return null;
                String url = data.optJSONObject(0) == null ? null
                        : data.optJSONObject(0).optString("url", null);
                Log.d(TAG, "resolved url for id " + id + ": "
                        + (url == null ? "null" : "ok"));
                return url == null || url.isEmpty() ? null : url;
            }
        } catch (Throwable t) {
            Log.d(TAG, "resolve url failed: " + t);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** 网易云的接口/外链都要求带 Referer，否则容易 403。 */
    private static void applyHeaders(HttpURLConnection connection) {
        connection.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13) BukaMusic/3.00");
        connection.setRequestProperty("Referer", "https://music.163.com/");
        connection.setRequestProperty("Cookie", "appver=8.7.01; os=android; channel=netease");
    }

    private static byte[] download(String url) {
        // 网易云直链常是 http://，targetSdk 34 默认禁止明文流量；先按 https 试一次
        if (url != null && url.startsWith("http://")) {
            byte[] secure = downloadOnce("https://" + url.substring("http://".length()));
            if (secure != null && secure.length > 0) return secure;
        }
        return downloadOnce(url);
    }

    private static byte[] downloadOnce(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            applyHeaders(connection);
            connection.connect();
            if (connection.getResponseCode() != 200) return null;
            String type = connection.getContentType();
            if (type != null && type.toLowerCase().contains("text")) return null;
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    if (out.size() + read > MAX_BYTES) return null;
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        } catch (Throwable t) {
            Log.d(TAG, "download error: " + t);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
