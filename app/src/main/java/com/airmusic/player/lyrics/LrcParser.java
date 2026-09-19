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
            // Enhanced LRC marks each word with its own timestamp:
            //   [00:12.00]<00:12.00>Hel<00:12.30>lo
            // Keep both the plain text and the per-word timings.
            List<LyricLine.Word> words = new ArrayList<>();
            content = parseWordTags(content, words).trim();
            if (content.isEmpty()) continue;
            String[] split = splitInlineTranslation(content);
            String mainText = split[0];
            String inlineTranslation = split[1];
            if (mainText.isEmpty()) continue;
            if (starts.isEmpty()) {
                plain.add(inlineTranslation == null ? mainText
                        : mainText + "\u0000" + inlineTranslation);
            } else {
                for (long start : starts) {
                    long timeline = Math.max(0, start + offsetMs);
                    timed.add(new LyricLine(Math.max(0, start + offsetMs),
                            timeline + 4_000L,
                            mainText, inlineTranslation,
                            shiftWords(words, start + offsetMs, start)));
                }
            }
        }

        if (!timed.isEmpty()) {
            timed.sort(Comparator.comparingLong(l -> l.startMs));
            return new Lyrics(withEndTimes(mergeTranslations(timed), fallbackDurationMs), true);
        }

        if (plain.isEmpty()) return Lyrics.EMPTY;
        long slot = fallbackDurationMs > 0 ? Math.max(1, fallbackDurationMs / plain.size()) : 5_000L;
        List<LyricLine> estimated = new ArrayList<>();
        for (int i = 0; i < plain.size(); i++) {
            long start = i * slot;
            String entry = plain.get(i);
            String main = entry;
            String translation = null;
            int sep = entry.indexOf('\u0000');
            if (sep >= 0) {
                main = entry.substring(0, sep);
                translation = entry.substring(sep + 1);
            }
            estimated.add(new LyricLine(start, start + slot, main, translation));
        }
        return new Lyrics(withEndTimes(mergeTranslations(estimated), fallbackDurationMs), false);
    }

    /**
     * Extracts the per-word timings from an enhanced-LRC line, returning the
     * line text with the tags removed. Word end times are filled in from the
     * following word (or the line end) by {@link #shiftWords}.
     */
    static String parseWordTags(String content, List<LyricLine.Word> out) {
        if (content == null || content.isEmpty()) return "";
        Matcher m = WORD_TAG.matcher(content);
        if (!m.find()) return content;
        StringBuilder text = new StringBuilder();
        m.reset();
        long pendingTime = -1L;
        int pendingEnd = -1;
        while (m.find()) {
            String tag = m.group();
            long time = parseTagTime(tag);
            if (pendingTime >= 0 && pendingEnd >= 0 && m.start() > pendingEnd) {
                String body = content.substring(pendingEnd, m.start());
                if (!body.isEmpty()) {
                    out.add(new LyricLine.Word(pendingTime, time, body));
                    text.append(body);
                }
            } else if (pendingTime < 0 && m.start() > 0) {
                // Text before the first tag has no timing of its own.
                text.append(content, 0, m.start());
            }
            pendingTime = time;
            pendingEnd = m.end();
        }
        if (pendingTime >= 0 && pendingEnd >= 0 && pendingEnd < content.length()) {
            String tail = content.substring(pendingEnd);
            if (!tail.isEmpty()) {
                out.add(new LyricLine.Word(pendingTime, pendingTime, tail));
                text.append(tail);
            }
        }
        return text.toString();
    }

    /** Parses {@code <mm:ss.xx>} into milliseconds, or -1 when malformed. */
    private static long parseTagTime(String chunk) {
        int open = chunk.lastIndexOf('<');
        int close = chunk.lastIndexOf('>');
        if (open < 0 || close <= open) return -1L;
        String body = chunk.substring(open + 1, close);
        Matcher m = Pattern.compile("(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?")
                .matcher(body);
        if (!m.find()) return -1L;
        return parseLong(m.group(1)) * 60_000L + parseLong(m.group(2)) * 1_000L
                + parseFraction(m.group(3));
    }

    /**
     * Clones the word list onto the timestamp of one occurrence of the line and
     * closes each word with the start of the next one.
     */
    private static List<LyricLine.Word> shiftWords(List<LyricLine.Word> words,
                                                   long lineStart, long originalStart) {
        if (words == null || words.isEmpty()) return null;
        long delta = lineStart - originalStart;
        List<LyricLine.Word> out = new ArrayList<>(words.size());
        for (int i = 0; i < words.size(); i++) {
            LyricLine.Word word = words.get(i);
            long start = word.startMs + delta;
            long end = i + 1 < words.size()
                    ? words.get(i + 1).startMs + delta
                    : start + 400L;
            out.add(new LyricLine.Word(start, end, word.text));
        }
        return out;
    }

    /**
     * Applies the same bilingual pairing rules to lyrics that did not come from
     * the LRC parser (embedded tags can also carry separate translation lines).
     */
    public static Lyrics resolveTranslations(Lyrics lyrics) {
        if (lyrics == null || lyrics.isEmpty()) return lyrics;
        List<LyricLine> merged = mergeTranslations(lyrics.lines);
        if (merged.size() == lyrics.lines.size()) return lyrics;
        return new Lyrics(merged, lyrics.synced);
    }

    /**
     * Many bilingual LRC files put the translation on a second line with the
     * exact same timestamp. Merge those pairs so the renderer can show both.
     */
    private static List<LyricLine> mergeTranslations(List<LyricLine> input) {
        List<LyricLine> out = new ArrayList<>(input.size());
        int i = 0;
        while (i < input.size()) {
            LyricLine current = input.get(i);
            if (current.translation != null) {
                out.add(current);
                i++;
                continue;
            }
            if (i + 1 < input.size()) {
                LyricLine next = input.get(i + 1);
                long delta = Math.abs(next.startMs - current.startMs);
                boolean pairing = !next.text.equals(current.text)
                        && next.translation == null
                        && (delta <= 120
                        || (delta <= 700 && scriptsDiffer(current.text, next.text)));
                if (!pairing) {
                    out.add(current);
                    i++;
                    continue;
                }
                out.add(new LyricLine(current.startMs, Math.max(current.endMs, next.endMs),
                        current.text, next.text, current.words));
                i += 2;
            } else {
                out.add(current);
                i++;
            }
        }
        return out;
    }

    /**
     * Splits "原文 (译文)" / "原文（译文）" into main text + translation. Only
     * trailing parenthesised groups are treated as translations, so ordinary
     * parenthetical lyrics stay untouched.
     */
    private static String[] splitInlineTranslation(String text) {
        if (text == null || text.length() < 4) return new String[]{text == null ? "" : text, null};
        int end = text.length();
        char last = text.charAt(end - 1);
        char open;
        char close;
        if (last == ')' || last == '\uFF09') {
            open = last == ')' ? '(' : '\uFF08';
            close = last;
        } else {
            return new String[]{text, null};
        }
        int depth = 1;
        int index = end - 2;
        while (index >= 0) {
            char c = text.charAt(index);
            if (c == close) depth++;
            else if (c == open) {
                depth--;
                if (depth == 0) break;
            }
            index--;
        }
        if (index <= 0 || depth != 0) return new String[]{text, null};
        String inside = text.substring(index + 1, end - 1).trim();
        String outside = text.substring(0, index).trim();
        if (inside.isEmpty() || outside.isEmpty()) return new String[]{text, null};
        return new String[]{outside, inside};
    }

    private static boolean scriptsDiffer(String a, String b) {
        float ratioA = cjkRatio(a);
        float ratioB = cjkRatio(b);
        return (ratioA > 0.35f && ratioB < 0.1f) || (ratioB > 0.35f && ratioA < 0.1f);
    }

    private static float cjkRatio(String value) {
        if (value == null || value.isEmpty()) return 0f;
        int cjk = 0;
        int total = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || c == '(' || c == ')'
                    || c == '\uFF08' || c == '\uFF09') continue;
            total++;
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3040 && c <= 0x30FF)
                    || (c >= 0xAC00 && c <= 0xD7AF)) {
                cjk++;
            }
        }
        return total == 0 ? 0f : cjk / (float) total;
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
            out.add(new LyricLine(line.startMs, end, line.text, line.translation, line.words));
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
