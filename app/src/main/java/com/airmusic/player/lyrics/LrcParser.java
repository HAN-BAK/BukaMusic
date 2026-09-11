package com.airmusic.player.lyrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal LRC parser. Handles multiple timestamps per line, {@code [offset:]}
 * and enhanced-LRC word tags (which are stripped for now).
 */
public final class LrcParser {

    private static final Pattern TIME =
            Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern OFFSET =
            Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_TAG = Pattern.compile("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>");

    private LrcParser() {
    }

    public static Lyrics parse(String raw, long fallbackDurationMs) {
        if (raw == null) return Lyrics.EMPTY;
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        if (text.length() > 0 && text.charAt(0) == '\uFEFF') text = text.substring(1);

        long offsetMs = 0;
        Matcher offsetMatcher = OFFSET.matcher(text);
        if (offsetMatcher.find()) {
            try {
                offsetMs = Long.parseLong(offsetMatcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }

        List<LyricLine> timed = new ArrayList<>();
        List<String> plain = new ArrayList<>();
        String[] rows = text.split("\n", -1);
        for (String row : rows) {
            String line = row == null ? "" : row.trim();
            if (line.isEmpty()) continue;

            List<Long> starts = new ArrayList<>();
            Matcher m = TIME.matcher(line);
            while (m.find()) {
                long min = parseLong(m.group(1));
                long sec = parseLong(m.group(2));
                long frac = parseFraction(m.group(3));
                starts.add((min * 60_000L) + (sec * 1_000L) + frac);
            }
            String content = TIME.matcher(line).replaceAll("").trim();
            content = WORD_TAG.matcher(content).replaceAll("").trim();
            if (content.isEmpty()) continue;
            if (starts.isEmpty()) {
                plain.add(content);
            } else {
                for (long start : starts) {
                    timed.add(new LyricLine(Math.max(0, start + offsetMs),
                            Math.max(0, start + offsetMs) + 4_000L, content));
                }
            }
        }

        if (!timed.isEmpty()) {
            timed.sort(Comparator.comparingLong(l -> l.startMs));
            return new Lyrics(withEndTimes(timed, fallbackDurationMs), true);
        }

        if (plain.isEmpty()) return Lyrics.EMPTY;
        long slot = fallbackDurationMs > 0 ? Math.max(1, fallbackDurationMs / plain.size()) : 5_000L;
        List<LyricLine> estimated = new ArrayList<>();
        for (int i = 0; i < plain.size(); i++) {
            long start = i * slot;
            estimated.add(new LyricLine(start, start + slot, plain.get(i)));
        }
        return new Lyrics(withEndTimes(estimated, fallbackDurationMs), false);
    }

    private static List<LyricLine> withEndTimes(List<LyricLine> input, long durationMs) {
        List<LyricLine> out = new ArrayList<>(input.size());
        for (int i = 0; i < input.size(); i++) {
            LyricLine line = input.get(i);
            long end = line.endMs;
            if (i + 1 < input.size()) {
                end = input.get(i + 1).startMs;
            } else if (durationMs > line.startMs) {
                end = durationMs + 1;
            }
            end = Math.max(end, line.startMs + 1);
            out.add(new LyricLine(line.startMs, end, line.text));
        }
        return out;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parseFraction(String value) {
        if (value == null || value.isEmpty()) return 0;
        long v = parseLong(value);
        if (value.length() == 1) return v * 100;
        if (value.length() == 2) return v * 10;
        if (value.length() >= 3) {
            return parseLong(value.substring(0, 3));
        }
        return 0;
    }
}
