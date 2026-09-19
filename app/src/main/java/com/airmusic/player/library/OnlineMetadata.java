package com.airmusic.player.library;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import java.util.Locale;

/**
 * Fills in missing song information (title / artist / album / cover) from a
 * NetEase Cloud Music lookup.
 *
 * <p>Nothing is ever written back into the music file - the result is only
 * handed to the player UI, exactly like a manually edited tag would be shown
 * without touching the file. Results are cached on disk (metadata + a small
 * cover) so a song is only looked up once.
 */
public final class OnlineMetadata {

    private static final String TAG = "OnlineMetadata";
    private static final String UA = "Mozilla/5.0 (Linux; Android 9) BukaMusic/2.60";
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int COVER_SIZE = 500;

    /** What was found online; every field may be null when unknown. */
    public static final class Info {
        public final String title;
        public final String artist;
        public final String album;
        public final byte[] cover;

        Info(String title, String artist, String album, byte[] cover) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.cover = cover;
        }

        public boolean isEmpty() {
            return (title == null || title.isEmpty())
                    && (artist == null || artist.isEmpty())
                    && (album == null || album.isEmpty())
                    && cover == null;
        }
    }

    private static volatile File cacheDir;

    private OnlineMetadata() {
    }

    /**
     * Blocking lookup, meant for a background thread. {@code titleHint} is the
     * name shown for the file (usually the tag title, otherwise the file name).
     */
    public static Info fetchBlocking(Context context, String titleHint,
                                     String artistHint, long durationMs) {
        if (titleHint == null || titleHint.trim().isEmpty()) return null;
        Context appContext = context == null ? null : context.getApplicationContext();
        if (cacheDir == null && appContext != null) {
            cacheDir = new File(appContext.getFilesDir(), "metadata");
        }
        String key = hash(normalise(titleHint) + "|" + normalise(artistHint));
        Info cached = readCache(key);
        if (cached != null) return cached;
        try {
            Info info = search(titleHint, artistHint, durationMs);
            if (info != null) writeCache(key, info);
            return info;
        } catch (Throwable t) {
            Log.d(TAG, "lookup failed: " + t);
            return null;
        }
    }

    private static Info search(String title, String artist, long durationMs) throws Exception {
        String query = title + (artist == null || artist.isEmpty() ? "" : " " + artist);
        String url = "https://music.163.com/api/search/get/web?type=1&limit=10&s="
                + URLEncoder.encode(query, "UTF-8");
        JSONObject payload = new JSONObject(httpGet(url));
        JSONArray songs = payload.optJSONObject("result") == null ? null
                : payload.getJSONObject("result").optJSONArray("songs");
        if (songs == null || songs.length() == 0) return null;

        JSONObject best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            if (song == null) continue;
            int score = matchScore(title, artist, song.optString("name", ""),
                    artistOf(song), durationMs, song.optLong("duration", 0L));
            if (score > bestScore) {
                bestScore = score;
                best = song;
            }
        }
        if (best == null || bestScore < 0) return null;

        String foundTitle = emptyToNull(best.optString("name", ""));
        String foundArtist = emptyToNull(artistOf(best));
        JSONObject album = best.optJSONObject("album");
        String foundAlbum = album == null ? null : emptyToNull(album.optString("name", ""));
        String coverUrl = album == null ? null : emptyToNull(album.optString("picUrl", ""));
        if (coverUrl == null) {
            JSONArray artists = best.optJSONArray("artists");
            JSONObject first = artists == null || artists.length() == 0 ? null
                    : artists.optJSONObject(0);
            if (first != null) coverUrl = emptyToNull(first.optString("img1v1Url", ""));
        }
        byte[] cover = coverUrl == null ? null : downloadCover(coverUrl);
        return new Info(foundTitle, foundArtist, foundAlbum, cover);
    }

    private static byte[] downloadCover(String url) {
        try {
            String sized = url + (url.contains("?") ? "&" : "?")
                    + "param=" + COVER_SIZE + "y" + COVER_SIZE;
            byte[] data = httpGetBytes(sized);
            if (data == null || data.length == 0) return null;
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bitmap == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            bitmap.recycle();
            return out.toByteArray();
        } catch (Throwable t) {
            Log.d(TAG, "cover download failed: " + t);
            return null;
        }
    }

    private static Info readCache(String key) {
        File dir = cacheDir;
        if (dir == null) return null;
        File meta = new File(dir, key + ".json");
        if (!meta.isFile()) return null;
        try {
            String json = new String(readAll(new FileInputStream(meta)), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(json);
            byte[] cover = null;
            File art = new File(dir, key + ".jpg");
            if (art.isFile()) cover = readAll(new FileInputStream(art));
            return new Info(emptyToNull(root.optString("title", "")),
                    emptyToNull(root.optString("artist", "")),
                    emptyToNull(root.optString("album", "")), cover);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeCache(String key, Info info) {
        File dir = cacheDir;
        if (dir == null) return;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            JSONObject root = new JSONObject();
            if (info.title != null) root.put("title", info.title);
            if (info.artist != null) root.put("artist", info.artist);
            if (info.album != null) root.put("album", info.album);
            try (FileOutputStream out = new FileOutputStream(new File(dir, key + ".json"))) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (info.cover != null && info.cover.length > 0) {
                try (FileOutputStream out = new FileOutputStream(new File(dir, key + ".jpg"))) {
                    out.write(info.cover);
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "cache write failed: " + t);
        }
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

    private static String normalise(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[（(\\[【].*?[）)\\]】]", " ")
                .replaceAll("[^\\p{L}\\p{N}]+", "")
                .trim();
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static String httpGet(String url) throws Exception {
        byte[] data = httpGetBytes(url);
        return data == null ? "" : new String(data, StandardCharsets.UTF_8);
    }

    private static byte[] httpGetBytes(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", UA);
        connection.setRequestProperty("Accept", "application/json,image/*,*/*");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Referer", "https://music.163.com/");
        try {
            InputStream in = connection.getInputStream();
            if (in == null) return null;
            return readAll(in);
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

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
