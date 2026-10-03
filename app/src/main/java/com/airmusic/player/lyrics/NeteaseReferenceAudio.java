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
        byte[] data = download(OUTER_URL + id);
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
    private static byte[] download(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android) BukaMusic");
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
