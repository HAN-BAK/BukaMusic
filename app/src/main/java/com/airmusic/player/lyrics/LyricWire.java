package com.airmusic.player.lyrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Serialises parsed lyrics so a multi-room master can hand the exact same
 * lines (including translations) to its receivers. Sending the parsed form
 * instead of the raw LRC text keeps both devices on identical timings and
 * avoids re-running the translation / timing fit on the receiver.
 */
public final class LyricWire {

    private static final int VERSION = 1;

    private LyricWire() {
    }

    /** Layout seed so the master and the receiver build the same shot plan. */
    public static String seedFor(String title, String artist, String album) {
        String seed = (title == null ? "" : title)
                + "|" + (artist == null ? "" : artist)
                + "|" + (album == null ? "" : album);
        return seed.isEmpty() ? "sonnet" : seed;
    }

    /** Title / artist / album tokens protected during word segmentation. */
    public static List<String> hintsFor(String title, String artist, String album) {
        List<String> hints = new ArrayList<>();
        String[] sources = {title, artist, album};
        for (String source : sources) {
            if (source == null) continue;
            String[] parts = source.split("[\\s,;、/|()（）\\[\\]【】\\-—_·]+");
            for (String part : parts) {
                String value = part.trim();
                if (value.length() < 2) continue;
                int letters = 0;
                for (int i = 0; i < value.length(); i++) {
                    if (Character.isLetterOrDigit(value.charAt(i))) letters++;
                }
                if (letters >= 2 && !hints.contains(value)) hints.add(value);
            }
        }
        return hints;
    }

    public static final class Packet {
        public final Lyrics lyrics;
        public final String seed;
        public final List<String> hints;

        Packet(Lyrics lyrics, String seed, List<String> hints) {
            this.lyrics = lyrics == null ? Lyrics.EMPTY : lyrics;
            this.seed = seed == null || seed.isEmpty() ? "sonnet" : seed;
            this.hints = hints == null ? Collections.emptyList() : hints;
        }

        public boolean isEmpty() {
            return lyrics == null || lyrics.isEmpty();
        }
    }

    public static String encode(String seed, List<String> hints, Lyrics lyrics) {
        try {
            JSONObject root = new JSONObject();
            root.put("v", VERSION);
            root.put("seed", seed == null ? "sonnet" : seed);
            if (hints != null && !hints.isEmpty()) {
                JSONArray hintArray = new JSONArray();
                for (String hint : hints) {
                    if (hint != null && !hint.isEmpty()) hintArray.put(hint);
                }
                root.put("hints", hintArray);
            }
            JSONArray lines = new JSONArray();
            if (lyrics != null) {
                for (LyricLine line : lyrics.lines) {
                    JSONObject item = new JSONObject();
                    item.put("s", line.startMs);
                    item.put("e", line.endMs);
                    item.put("t", line.text == null ? "" : line.text);
                    if (line.translation != null && !line.translation.isEmpty()) {
                        item.put("tr", line.translation);
                    }
                    lines.put(item);
                }
            }
            root.put("lines", lines);
            return root.toString();
        } catch (Throwable t) {
            return "{\"v\":1,\"lines\":[]}";
        }
    }

    public static Packet decode(String json) {
        try {
            JSONObject root = new JSONObject(json);
            List<LyricLine> lines = new ArrayList<>();
            JSONArray array = root.optJSONArray("lines");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item == null) continue;
                    long start = item.optLong("s", 0L);
                    long end = item.optLong("e", start + 1L);
                    String text = item.optString("t", "");
                    String translation = item.optString("tr", null);
                    if (text.isEmpty() && (translation == null || translation.isEmpty())) {
                        continue;
                    }
                    lines.add(new LyricLine(start, end, text, translation));
                }
            }
            List<String> hints = new ArrayList<>();
            JSONArray hintArray = root.optJSONArray("hints");
            if (hintArray != null) {
                for (int i = 0; i < hintArray.length(); i++) {
                    String hint = hintArray.optString(i, null);
                    if (hint != null && !hint.isEmpty()) hints.add(hint);
                }
            }
            boolean synced = !lines.isEmpty();
            return new Packet(new Lyrics(lines, synced),
                    root.optString("seed", "sonnet"), hints);
        } catch (Throwable t) {
            return new Packet(Lyrics.EMPTY, "sonnet", Collections.emptyList());
        }
    }
}
