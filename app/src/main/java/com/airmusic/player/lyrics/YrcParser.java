package com.airmusic.player.lyrics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for the word-level ("YRC") lyric payload some providers return, for
 * example NetEase Cloud Music:
 *
 * <pre>
 * [3489,3134](3489,446,0)Life (4513,788,0)blooms (5301,415,0)like ...
 * </pre>
 *
 * The first pair is the line start / duration, every following tuple carries a
 * word start / duration. That is exactly the per-word timing the Sonnet stage
 * needs, so no onset estimation is required for these songs.
 */
public final class YrcParser {

    private static final Pattern LINE_HEAD = Pattern.compile("^\\[(\\d+),(\\d+)]");
    private static final Pattern WORD = Pattern.compile("\\((\\d+),(\\d+),(\\d+)\\)");
    private static final Pattern LRC_TIME =
            Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");

    private YrcParser() {
    }

    /** True when the text looks like a YRC payload. */
    public static boolean looksLikeYrc(String text) {
        return text != null && LINE_HEAD.matcher(text.trim()).find();
    }

    /**
     * @param yrc            the original (word level) payload
     * @param translation    optional translated payload, either YRC or plain LRC
     */
    public static Lyrics parse(String yrc, String translation, long fallbackDurationMs) {
        if (yrc == null || yrc.isEmpty()) return Lyrics.EMPTY;
        Map<Long, String> translations = parseTranslations(translation);
        List<LyricLine> lines = new ArrayList<>();
        for (String row : yrc.split("\n")) {
            String line = row == null ? "" : row.trim();
            if (line.isEmpty()) continue;
            Matcher head = LINE_HEAD.matcher(line);
            if (!head.find()) continue;
            long lineStart = parseLong(head.group(1));
            long lineDuration = parseLong(head.group(2));
            List<LyricLine.Word> words = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            Matcher word = WORD.matcher(line);
            long pendingStart = -1L;
            int pendingEnd = -1;
            while (word.find()) {
                long start = parseLong(word.group(1));
                long duration = parseLong(word.group(2));
                int flag = (int) parseLong(word.group(3));
                if (flag != 0) {
                    pendingEnd = word.end();
                    continue;
                }
                if (pendingStart >= 0 && pendingEnd >= 0 && word.start() > pendingEnd) {
                    String body = line.substring(pendingEnd, word.start());
                    if (!body.isEmpty()) {
                        words.add(new LyricLine.Word(pendingStart, pendingStart
                                + Math.max(60L, duration), body));
                        text.append(body);
                    }
                }
                pendingStart = start;
                pendingEnd = word.end();
            }
            if (pendingStart >= 0 && pendingEnd >= 0 && pendingEnd < line.length()) {
                String tail = line.substring(pendingEnd);
                if (!tail.isEmpty()) {
                    words.add(new LyricLine.Word(pendingStart,
                            Math.max(pendingStart + 60L, lineStart + lineDuration), tail));
                    text.append(tail);
                }
            }
            String body = text.toString().trim();
            if (body.isEmpty()) continue;
            long end = Math.max(lineStart + Math.max(250L, lineDuration), lineStart + 250L);
            lines.add(new LyricLine(lineStart, end, body, translations.get(lineStart), words));
        }
        if (lines.isEmpty()) return Lyrics.EMPTY;
        return LyricTiming.fit(LyricTranslations.resolve(new Lyrics(lines, true)));
    }

    /** Translations keyed by line start time; accepts YRC or plain LRC. */
    private static Map<Long, String> parseTranslations(String translation) {
        Map<Long, String> out = new HashMap<>();
        if (translation == null || translation.isEmpty()) return out;
        for (String row : translation.split("\n")) {
            String line = row == null ? "" : row.trim();
            if (line.isEmpty()) continue;
            long start = -1L;
            int textFrom = 0;
            Matcher head = LINE_HEAD.matcher(line);
            if (head.find()) {
                start = parseLong(head.group(1));
                textFrom = head.end();
                StringBuilder text = new StringBuilder();
                Matcher word = WORD.matcher(line);
                int cursor = head.end();
                while (word.find()) {
                    if (word.start() > cursor) text.append(line, cursor, word.start());
                    cursor = word.end();
                }
                if (cursor < line.length()) text.append(line.substring(cursor));
                String body = text.toString().trim();
                if (start >= 0 && !body.isEmpty()) out.put(start, body);
                continue;
            }
            Matcher time = LRC_TIME.matcher(line);
            if (time.find()) {
                start = parseLong(time.group(1)) * 60_000L
                        + parseLong(time.group(2)) * 1_000L
                        + parseFraction(time.group(3));
                String body = LRC_TIME.matcher(line).replaceAll("").trim();
                if (start >= 0 && !body.isEmpty()) out.put(start, body);
            }
        }
        return out;
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0L : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static long parseFraction(String value) {
        if (value == null || value.isEmpty()) return 0L;
        try {
            if (value.length() == 1) return Long.parseLong(value) * 100L;
            if (value.length() == 2) return Long.parseLong(value) * 10L;
            return Long.parseLong(value.substring(0, 3));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
