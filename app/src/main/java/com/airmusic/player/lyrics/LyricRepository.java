package com.airmusic.player.lyrics;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;

import com.airmusic.player.library.Track;
import com.airmusic.player.util.Prefs;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Finds lyrics for a track: a same-name {@code .lrc} file first, then embedded
 * tags. Results are cached so re-entering the lyric screen is instant.
 */
public final class LyricRepository {

    public interface Callback {
        void onLoaded(Lyrics lyrics);
    }

    private static final int MAX_SIDECAR_BYTES = 2 * 1024 * 1024;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Lyrics> CACHE =
            new LinkedHashMap<String, Lyrics>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Lyrics> eldest) {
                    return size() > 4;
                }
            };

    private LyricRepository() {
    }

    public static void loadAsync(final Context context, final Track track,
                                 final long durationMs, final Callback callback) {
        if (track == null) {
            if (callback != null) callback.onLoaded(Lyrics.EMPTY);
            return;
        }
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            final Lyrics lyrics = load(appContext, track, durationMs);
            MAIN.post(() -> {
                if (callback != null) callback.onLoaded(lyrics);
            });
        });
    }

    public static Lyrics load(Context context, Track track, long durationMs) {
        if (track == null) return Lyrics.EMPTY;
        String key = track.uri == null ? track.filePath : track.uri.toString();
        key = (key == null ? "?" : key) + "#" + durationMs;
        synchronized (CACHE) {
            Lyrics cached = CACHE.get(key);
            if (cached != null) return cached;
        }

        Lyrics lyrics = Lyrics.EMPTY;
        try {
            String raw = readSidecar(context, track);
            if (raw == null || raw.trim().isEmpty()) {
                raw = EmbeddedLyricReader.read(context, track);
            }
            if (raw != null && !raw.trim().isEmpty()) {
                lyrics = LrcParser.parse(raw, durationMs);
            }
        } catch (Throwable ignored) {
            lyrics = Lyrics.EMPTY;
        }

        synchronized (CACHE) {
            CACHE.put(key, lyrics);
        }
        return lyrics;
    }

    // ------------------------------------------------------------------
    // Sidecar .lrc lookup
    // ------------------------------------------------------------------

    private static String readSidecar(Context context, Track track) {
        String fromPath = readSidecarFromPath(track);
        if (fromPath != null) return fromPath;
        return readSidecarFromSaf(context, track);
    }

    private static String readSidecarFromPath(Track track) {
        if (track.filePath == null) return null;
        File audio = new File(track.filePath);
        File parent = audio.getParentFile();
        if (parent == null) return null;
        String base = stripExtension(audio.getName());
        File direct = new File(parent, base + ".lrc");
        String text = readTextFile(direct);
        if (text != null) return text;
        File upper = new File(parent, base + ".LRC");
        text = readTextFile(upper);
        if (text != null) return text;
        File[] files = parent.listFiles();
        if (files == null) return null;
        for (File file : files) {
            String name = file.getName();
            if (name.length() > 4
                    && name.regionMatches(true, name.length() - 4, ".lrc", 0, 4)
                    && stripExtension(name).equalsIgnoreCase(base)) {
                text = readTextFile(file);
                if (text != null) return text;
            }
        }
        return null;
    }

    private static String readSidecarFromSaf(Context context, Track track) {
        if (context == null || track.uri == null) return null;
        String tree = new Prefs(context).getMusicFolderUri();
        if (tree == null || tree.isEmpty()) return null;
        try {
            String docId = DocumentsContract.getDocumentId(track.uri);
            int slash = docId.lastIndexOf('/');
            if (slash < 0) return null;
            String folder = docId.substring(0, slash + 1);
            String name = docId.substring(slash + 1);
            String base = stripExtension(name);
            Uri treeUri = Uri.parse(tree);
            String[] candidates = {folder + base + ".lrc", folder + base + ".LRC"};
            for (String candidate : candidates) {
                Uri lrcUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, candidate);
                try (InputStream in = context.getContentResolver().openInputStream(lrcUri)) {
                    if (in == null) continue;
                    return readStream(in);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String readTextFile(File file) {
        if (file == null || !file.isFile() || file.length() <= 0
                || file.length() > MAX_SIDECAR_BYTES) return null;
        try (InputStream in = new FileInputStream(file)) {
            return readStream(in);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
            total += read;
            if (total > MAX_SIDECAR_BYTES) break;
            out.write(buffer, 0, read);
        }
        return decodeText(out.toByteArray());
    }

    /**
     * LRC files in the wild are UTF-8, UTF-16 (often with a BOM) or GBK /
     * GB18030. Try the unambiguous cases first, then strict UTF-8, and only
     * fall back to the Chinese charsets when the bytes cannot be UTF-8.
     */
    private static String decodeText(byte[] data) {
        if (data == null || data.length == 0) return "";
        try {
            if (data.length >= 3
                    && (data[0] & 0xFF) == 0xEF
                    && (data[1] & 0xFF) == 0xBB
                    && (data[2] & 0xFF) == 0xBF) {
                return stripNulls(new String(data, 3, data.length - 3, StandardCharsets.UTF_8));
            }
            if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
                return stripNulls(new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE));
            }
            if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
                return stripNulls(new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE));
            }

            int sample = Math.min(data.length, 400);
            int zerosEven = 0;
            int zerosOdd = 0;
            for (int i = 0; i < sample; i++) {
                if (data[i] == 0) {
                    if ((i & 1) == 0) zerosEven++;
                    else zerosOdd++;
                }
            }
            if (zerosEven + zerosOdd > sample / 8) {
                Charset charset = zerosEven > zerosOdd
                        ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_16LE;
                return stripNulls(new String(data, charset));
            }

            String utf8 = decodeStrict(data, StandardCharsets.UTF_8);
            if (utf8 != null) return stripNulls(utf8);
            String gb = decodeStrict(data, Charset.forName("GB18030"));
            if (gb != null) return stripNulls(gb);
        } catch (Throwable ignored) {
        }
        return stripNulls(new String(data, StandardCharsets.ISO_8859_1));
    }

    private static String decodeStrict(byte[] data, Charset charset) {
        try {
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static String stripNulls(String value) {
        if (value == null) return "";
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == '\u0000'
                || value.charAt(start) == '\uFEFF')) start++;
        while (end > start && (value.charAt(end - 1) == '\u0000'
                || value.charAt(end - 1) == '\uFEFF')) end--;
        return value.substring(start, end);
    }

    private static String stripExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
