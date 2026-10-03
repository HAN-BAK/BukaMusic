package com.airmusic.player.lyrics;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Looks a song up online by its title when no local lyrics exist.
 *
 * <p>Two key-less, publicly reachable sources are used:
 * <ul>
 *   <li><b>NetEase Cloud Music</b> — reachable from mainland China, and the
 *       only one of the two that returns real <i>per-word</i> timings ("YRC")
 *       plus a translated lyric for foreign songs;</li>
 *   <li><b>LRCLIB</b> — community database, good coverage for non-Chinese
 *       music, returns synced LRC (sometimes with enhanced word tags).</li>
 * </ul>
 * Everything that is fetched is cached on disk, keyed by title / artist /
 * duration, so a song only has to be looked up once.
 */
public final class OnlineLyrics {

    private static final String TAG = "OnlineLyrics";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 9) BukaMusic/2.60";
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 6000;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "lyrics-online");
        thread.setDaemon(true);
        return thread;
    });

    /** Result of one lookup; {@code lyrics} is empty when nothing was found. */
    public static final class Result {
        public final Lyrics lyrics;
        public final String source;

        Result(Lyrics lyrics, String source) {
            this.lyrics = lyrics == null ? Lyrics.EMPTY : lyrics;
            this.source = source;
        }

        public boolean isEmpty() {
            return lyrics.isEmpty();
        }
    }

    public interface Callback {
        void onResult(Result result);
    }

    private OnlineLyrics() {
    }

    /**
     * Cache key: normalised title + artist. The reported duration is
     * deliberately left out - players and tag readers disagree about it by a
     * few seconds, which used to make identical songs miss the cache.
     */
    public static String cacheKey(String title, String artist, long durationMs) {
        return normalise(title) + "|" + normalise(artist);
    }

    /** Parses lyrics from the disk cache only (never touches the network). */
    public static Result loadCached(Context context, String key) {
        try {
            File file = cacheFile(context, key);
            if (file == null || !file.isFile() || file.length() == 0) return null;
            String json = readFile(file);
            JSONObject root = new JSONObject(json);
            String format = root.optString("format", "lrc");
            String body = root.optString("lyrics", "");
            String translation = root.optString("translation", null);
            if (body.isEmpty()) return null;
            Lyrics lyrics = "yrc".equals(format)
                    ? YrcParser.parse(body, translation, 0L)
                    : parseLrc(body, translation, root.optLong("durationMs", 0L));
            if (lyrics.isEmpty()) return null;
            return new Result(lyrics, root.optString("source", "cache"));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Looks the song up online (cache first) and reports the parsed lyrics. */
    public static void fetchAsync(final Context context, final String title,
                                  final String artist, final String album,
                                  final long durationMs, final Callback callback) {
        if (title == null || title.trim().isEmpty()) {
            if (callback != null) callback.onResult(new Result(Lyrics.EMPTY, null));
            return;
        }
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> post(callback,
                fetchBlocking(appContext, title, artist, album, durationMs)));
    }

    /**
     * Blocking variant, for callers that already run on a background thread
     * (the lyric repository loads lyrics there).
     */
    /**
     * 供「AirPlay 参考音频」使用：用和歌词完全相同的候选与打分逻辑选出最匹配的
     * 网易云歌曲 id（保证歌词和参考音频指向同一首），找不到返回 -1。
     */
    public static long bestMatchId(String title, String artist, long durationMs) {
        if (title == null || title.trim().isEmpty()) return -1L;
        try {
            String keyword = title + " " + (artist == null ? "" : artist);
            String searchUrl = "https://music.163.com/api/search/get/web?type=1&limit=10&s="
                    + java.net.URLEncoder.encode(keyword, "UTF-8");
            JSONObject search = new JSONObject(httpGet(searchUrl, true));
            JSONArray songs = search.optJSONObject("result") == null ? null
                    : search.getJSONObject("result").optJSONArray("songs");
            if (songs == null || songs.length() == 0) return -1L;
            long bestId = -1L;
            int bestScore = Integer.MIN_VALUE;
            for (int i = 0; i < songs.length(); i++) {
                JSONObject song = songs.optJSONObject(i);
                if (song == null) continue;
                int score = matchScore(title, artist, song.optString("name", ""),
                        artistOf(song), durationMs, song.optLong("duration", 0L));
                if (score > bestScore) {
                    bestScore = score;
                    bestId = song.optLong("id", -1L);
                }
            }
            Log.d(TAG, "bestMatchId \"" + title + "\" -> " + bestId + " (score " + bestScore + ")");
            return bestId;
        } catch (Throwable t) {
            Log.d(TAG, "bestMatchId failed: " + t);
            return -1L;
        }
    }

    public static Result fetchBlocking(Context context, String title, String artist,
                                       String album, long durationMs) {
        if (title == null || title.trim().isEmpty()) return new Result(Lyrics.EMPTY, null);
        if (context != null) {
            Context appContext = context.getApplicationContext();
            if (cacheDir == null) cacheDir = new File(appContext.getFilesDir(), "lyrics");
        }
        final String key = cacheKey(title, artist, durationMs);
        Result cached = loadCached(context, key);
        if (cached != null) return cached;
        Result result = null;
        try {
            result = fetchFromNetease(title, artist, durationMs);
        } catch (Throwable t) {
            Log.d(TAG, "netease lookup failed: " + t);
        }
        if (result == null || result.isEmpty()) {
            try {
                result = fetchFromLrclib(title, artist, album, durationMs);
            } catch (Throwable t) {
                Log.d(TAG, "lrclib lookup failed: " + t);
            }
        }
        return result == null ? new Result(Lyrics.EMPTY, null) : result;
    }

    private static void post(final Callback callback, final Result result) {
        if (callback == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> callback.onResult(result));
    }

    // ------------------------------------------------------------------
    // NetEase Cloud Music
    // ------------------------------------------------------------------

    private static Result fetchFromNetease(String title, String artist, long durationMs)
            throws Exception {
        String query = title + (artist == null || artist.isEmpty() ? "" : " " + artist);
        String searchUrl = "https://music.163.com/api/search/get/web?type=1&limit=10&s="
                + URLEncoder.encode(query, "UTF-8");
        JSONObject search = new JSONObject(httpGet(searchUrl, true));
        JSONArray songs = search.optJSONObject("result") == null ? null
                : search.getJSONObject("result").optJSONArray("songs");
        if (songs == null || songs.length() == 0) return null;

        long bestId = -1L;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            if (song == null) continue;
            String name = song.optString("name", "");
            long songDuration = song.optLong("duration", 0L);
            int score = matchScore(title, artist, name, artistOf(song), durationMs, songDuration);
            if (score > bestScore) {
                bestScore = score;
                bestId = song.optLong("id", -1L);
            }
        }
        if (bestId <= 0L || bestScore < 0) return null;

        String lyricUrl = "https://music.163.com/api/song/lyric?id=" + bestId
                + "&lv=-1&kv=-1&tv=-1&yv=-1&ytv=-1&rv=-1&yv=-1";
        JSONObject payload = new JSONObject(httpGet(lyricUrl, true));
        String yrc = child(payload, "yrc");
        String ytlrc = child(payload, "ytlrc");
        String lrc = child(payload, "lrc");
        String tlyric = child(payload, "tlyric");

        if (yrc != null && !yrc.isEmpty()) {
            Lyrics lyrics = YrcParser.parse(yrc,
                    ytlrc != null && !ytlrc.isEmpty() ? ytlrc : null, durationMs);
            if (!lyrics.isEmpty()) {
                store(cacheKey(title, artist, durationMs), "netease-yrc", "yrc",
                        yrc, ytlrc, durationMs);
                return new Result(lyrics, "netease");
            }
        }
        if (lrc == null || lrc.isEmpty()) return null;
        Lyrics lyrics = parseLrc(lrc, tlyric, durationMs);
        if (lyrics.isEmpty()) return null;
        store(cacheKey(title, artist, durationMs), "netease", "lrc", lrc, tlyric, durationMs);
        return new Result(lyrics, "netease");
    }

    // ------------------------------------------------------------------
    // LRCLIB
    // ------------------------------------------------------------------

    private static Result fetchFromLrclib(String title, String artist, String album,
                                          long durationMs) throws Exception {
        String body = null;
        String base = "https://lrclib.net/api/get?track_name="
                + URLEncoder.encode(title, "UTF-8");
        if (artist != null && !artist.isEmpty()) {
            base += "&artist_name=" + URLEncoder.encode(artist, "UTF-8");
        }
        if (album != null && !album.isEmpty()) {
            base += "&album_name=" + URLEncoder.encode(album, "UTF-8");
        }
        if (durationMs > 0) {
            base += "&duration=" + Math.max(1L, Math.round(durationMs / 1000.0));
        }
        try {
            JSONObject direct = new JSONObject(httpGet(base, false));
            body = pickLrclibEntry(direct);
        } catch (Throwable ignored) {
            // 404 / no exact match: fall through to the search endpoint.
        }
        if (body == null) {
            String query = title + (artist == null || artist.isEmpty() ? "" : " " + artist);
            JSONArray results = new JSONArray(httpGet("https://lrclib.net/api/search?q="
                    + URLEncoder.encode(query, "UTF-8"), false));
            JSONObject best = null;
            int bestScore = Integer.MIN_VALUE;
            for (int i = 0; i < results.length(); i++) {
                JSONObject entry = results.optJSONObject(i);
                if (entry == null) continue;
                int score = matchScore(title, artist, entry.optString("trackName", ""),
                        entry.optString("artistName", ""), durationMs,
                        Math.round(entry.optDouble("duration", 0d) * 1000d));
                if (score > bestScore) {
                    bestScore = score;
                    best = entry;
                }
            }
            if (best != null && bestScore >= 0) body = pickLrclibEntry(best);
        }
        if (body == null || body.isEmpty()) return null;
        Lyrics lyrics = parseLrc(body, null, durationMs);
        if (lyrics.isEmpty()) return null;
        store(cacheKey(title, artist, durationMs), "lrclib", "lrc", body, null, durationMs);
        return new Result(lyrics, "lrclib");
    }

    private static String pickLrclibEntry(JSONObject entry) {
        if (entry == null) return null;
        String synced = entry.optString("syncedLyrics", null);
        if (synced != null && !synced.trim().isEmpty()) return synced;
        String plain = entry.optString("plainLyrics", null);
        return plain == null || plain.trim().isEmpty() ? null : plain;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Lyrics parseLrc(String lrc, String translation, long durationMs) {
        String body = lrc;
        if (translation != null && !translation.trim().isEmpty()) {
            body = lrc + "\n" + translation;
        }
        return LyricTiming.fit(LyricTranslations.resolve(
                LrcParser.resolveTranslations(LrcParser.parse(body, durationMs))));
    }

    private static String artistOf(JSONObject song) {
        JSONArray artists = song.optJSONArray("artists");
        if (artists == null || artists.length() == 0) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) continue;
            if (out.length() > 0) out.append('/');
            out.append(artist.optString("name", ""));
        }
        return out.toString();
    }

    private static String child(JSONObject root, String key) {
        JSONObject node = root == null ? null : root.optJSONObject(key);
        if (node == null) return null;
        String value = node.optString("lyric", "");
        return value == null ? null : value;
    }

    /**
     * Ranks a candidate: the title has to match, a matching artist and a close
     * duration decide between covers / live versions.
     */
    private static int matchScore(String title, String artist, String candidateTitle,
                                  String candidateArtist, long durationMs,
                                  long candidateDurationMs) {
        String wanted = normalise(title);
        String got = normalise(candidateTitle);
        if (wanted.isEmpty() || got.isEmpty()) return -1;
        int score;
        if (wanted.equals(got)) score = 100;
        else if (got.contains(wanted) || wanted.contains(got)) score = 60;
        else return -1;
        String wantedArtist = normalise(artist);
        if (!wantedArtist.isEmpty()) {
            String gotArtist = normalise(candidateArtist);
            if (!gotArtist.isEmpty() && (gotArtist.contains(wantedArtist)
                    || wantedArtist.contains(gotArtist))) {
                score += 40;
            }
        }
        if (durationMs > 0 && candidateDurationMs > 0) {
            long delta = Math.abs(durationMs - candidateDurationMs);
            if (delta <= 3000L) score += 60;
            else if (delta <= 10_000L) score += 20;
            else score -= 40;
        }
        return score;
    }

    /** Lower case, punctuation and bracket contents removed for comparing. */
    private static String normalise(String value) {
        if (value == null) return "";
        String text = value.toLowerCase(Locale.ROOT)
                .replaceAll("[（(\\[【].*?[）)\\]】]", " ")
                .replaceAll("[^\\p{L}\\p{N}]+", "");
        return text.trim();
    }

    private static String httpGet(String url, boolean netease) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", UA);
        connection.setRequestProperty("Accept", "application/json,text/plain,*/*");
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (netease) {
            connection.setRequestProperty("Referer", "https://music.163.com/");
            connection.setRequestProperty("Cookie", "appver=2.0.2");
        }
        try {
            int code = connection.getResponseCode();
            InputStream in = code >= 400 ? connection.getErrorStream()
                    : connection.getInputStream();
            if (in == null) throw new IllegalStateException("HTTP " + code);
            return readStream(in);
        } finally {
            connection.disconnect();
        }
    }

    private static String readStream(InputStream in) throws Exception {
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
             BufferedReader buffered = new BufferedReader(reader)) {
            StringBuilder out = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = buffered.read(buffer)) > 0) out.append(buffer, 0, read);
            return out.toString();
        }
    }

    private static String readFile(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void store(String key, String source, String format, String lyrics,
                              String translation, long durationMs) {
        try {
            File file = cacheFile(null, key);
            if (file == null) return;
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
            JSONObject root = new JSONObject();
            root.put("source", source);
            root.put("format", format);
            root.put("lyrics", lyrics == null ? "" : lyrics);
            if (translation != null && !translation.isEmpty()) {
                root.put("translation", translation);
            }
            root.put("durationMs", durationMs);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Log.d(TAG, "cache write failed: " + t);
        }
    }

    private static File cacheFile(Context context, String key) {
        File dir = cacheDir;
        if (dir == null && context != null) {
            dir = new File(context.getApplicationContext().getFilesDir(), "lyrics");
            cacheDir = dir;
        }
        return dir == null ? null : new File(dir, hash(key) + ".json");
    }

    private static volatile File cacheDir;

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (Throwable t) {
            return Integer.toHexString(value.hashCode());
        }
    }

}
