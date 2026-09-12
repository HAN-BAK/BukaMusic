package com.airmusic.player.lyrics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic "director" for the native Sonnet-style visualiser.
 *
 * <p>Lines are grouped into paragraphs the way the original does (time gaps
 * plus a line cap), each paragraph gets a lyric role, then one of the seven
 * shot templates and one of the three transitions is picked from a stable
 * seed. The same song therefore produces the same cut every time, while two
 * songs usually get a different rhythm of shots.
 */
public final class LyricProgram {

    public enum Shot {
        TYPE_IMPACT,
        EDITORIAL_COLUMN,
        FRAGMENT_COLLAGE,
        TRACKING_RIBBON,
        MASK_REVEAL,
        POSTER_BLOCKS,
        QUIET_TABLEAU
    }

    public enum Transition {
        FAST_BLUR,
        MONO_GLITCH,
        CAMERA_PULL
    }

    public enum ParagraphKind {
        BREATH,
        VERSE,
        LIFT,
        CHORUS,
        BREAK,
        OUTRO
    }

    public static final class Paragraph {
        public final int index;
        public final ParagraphKind kind;
        public final Shot shot;
        public final Transition transitionOut;
        public final long startMs;
        public final long endMs;
        public final List<LyricLine> lines;

        Paragraph(int index, ParagraphKind kind, Shot shot, Transition transitionOut,
                  long startMs, long endMs, List<LyricLine> lines) {
            this.index = index;
            this.kind = kind;
            this.shot = shot;
            this.transitionOut = transitionOut;
            this.startMs = startMs;
            this.endMs = endMs;
            this.lines = lines;
        }

        public int lineCount() {
            return lines.size();
        }
    }

    public final List<Paragraph> paragraphs;

    private LyricProgram(List<Paragraph> paragraphs) {
        this.paragraphs = paragraphs;
    }

    public boolean isEmpty() {
        return paragraphs.isEmpty();
    }

    public int paragraphIndexAt(long positionMs) {
        if (paragraphs.isEmpty() || positionMs < paragraphs.get(0).startMs) return -1;
        int lo = 0;
        int hi = paragraphs.size() - 1;
        int result = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (paragraphs.get(mid).startMs <= positionMs) {
                result = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return result;
    }

    public static LyricProgram compile(Lyrics lyrics, String seedText) {
        if (lyrics == null || lyrics.lines.isEmpty()) {
            return new LyricProgram(new ArrayList<>());
        }
        List<List<LyricLine>> groups = group(lyrics.lines);

        // Repeats are a cheap way to recognise a chorus without any AI.
        Map<String, Integer> textFrequency = new HashMap<>();
        for (LyricLine line : lyrics.lines) {
            String key = normalize(line.text);
            if (key.isEmpty()) continue;
            textFrequency.put(key, textFrequency.getOrDefault(key, 0) + 1);
        }

        long seed = 1469598103934665603L;
        String source = seedText == null ? "sonnet" : seedText;
        for (int i = 0; i < source.length(); i++) {
            seed ^= source.charAt(i);
            seed *= 1099511628211L;
        }

        List<Paragraph> out = new ArrayList<>(groups.size());
        List<Long> boundaries = new ArrayList<>(groups.size());
        for (List<LyricLine> group : groups) {
            boundaries.add(group.get(0).startMs);
        }

        Shot previousShot = null;
        for (int g = 0; g < groups.size(); g++) {
            List<LyricLine> group = groups.get(g);
            long start = group.get(0).startMs;
            long end = g + 1 < groups.size()
                    ? groups.get(g + 1).get(0).startMs
                    : group.get(group.size() - 1).endMs + 2_000L;

            ParagraphKind kind = classify(g, groups.size(), group, textFrequency,
                    g == 0 ? 0 : start - groups.get(g - 1).get(groups.get(g - 1).size() - 1).endMs);
            Shot shot = pickShot(seed, g, kind, group, previousShot);
            Transition transition = pickTransition(seed, g, kind, groups.size());
            out.add(new Paragraph(g, kind, shot, transition, start, end, group));
            previousShot = shot;
        }
        return new LyricProgram(out);
    }

    // ------------------------------------------------------------------
    // Paragraph grouping / classification
    // ------------------------------------------------------------------

    private static List<List<LyricLine>> group(List<LyricLine> lines) {
        List<List<LyricLine>> groups = new ArrayList<>();
        List<LyricLine> current = new ArrayList<>();
        long paragraphStart = 0;
        for (int i = 0; i < lines.size(); i++) {
            LyricLine line = lines.get(i);
            boolean startNew = current.isEmpty();
            if (!current.isEmpty()) {
                long gap = line.startMs - current.get(current.size() - 1).endMs;
                long span = line.startMs - paragraphStart;
                if (gap > 4_200L || current.size() >= 4 || span > 16_000L) {
                    startNew = true;
                }
            }
            if (startNew) {
                if (!current.isEmpty()) groups.add(current);
                current = new ArrayList<>();
                paragraphStart = line.startMs;
            }
            current.add(line);
        }
        if (!current.isEmpty()) groups.add(current);
        return groups;
    }

    private static ParagraphKind classify(int index, int total, List<LyricLine> group,
                                          Map<String, Integer> frequency, long gapBeforeMs) {
        if (total <= 1) return ParagraphKind.VERSE;
        if (index == total - 1) {
            long duration = group.get(group.size() - 1).endMs - group.get(0).startMs;
            return duration < 8_000L ? ParagraphKind.OUTRO : ParagraphKind.CHORUS;
        }
        if (index > 0 && gapBeforeMs > 7_000L) return ParagraphKind.LIFT;
        if (group.size() == 1) {
            long duration = group.get(0).endMs - group.get(0).startMs;
            if (duration > 12_000L) return ParagraphKind.BREAK;
        }
        int repeated = 0;
        for (LyricLine line : group) {
            Integer count = frequency.get(normalize(line.text));
            if (count != null && count > 1) repeated++;
        }
        if (repeated * 2 >= group.size()) return ParagraphKind.CHORUS;
        return ParagraphKind.VERSE;
    }

    // ------------------------------------------------------------------
    // Deterministic shot / transition picking
    // ------------------------------------------------------------------

    private static Shot pickShot(long seed, int paragraphIndex, ParagraphKind kind,
                                 List<LyricLine> group, Shot previousShot) {
        long hash = mix(seed, paragraphIndex, kind.ordinal());
        Shot shot;
        switch (kind) {
            case BREAK:
                shot = (hash & 1) == 0 ? Shot.QUIET_TABLEAU : Shot.TRACKING_RIBBON;
                break;
            case OUTRO:
                shot = (hash & 1) == 0 ? Shot.QUIET_TABLEAU : Shot.MASK_REVEAL;
                break;
            case CHORUS:
                shot = choose(hash, Shot.POSTER_BLOCKS, Shot.TYPE_IMPACT, Shot.FRAGMENT_COLLAGE);
                break;
            case LIFT:
                shot = choose(hash, Shot.TYPE_IMPACT, Shot.TRACKING_RIBBON, Shot.MASK_REVEAL);
                break;
            default: {
                int longest = 0;
                for (LyricLine line : group) longest = Math.max(longest, line.text.length());
                if (longest <= 6) {
                    shot = Shot.TYPE_IMPACT;
                } else if (group.size() >= 3) {
                    shot = choose(hash, Shot.FRAGMENT_COLLAGE, Shot.EDITORIAL_COLUMN, Shot.POSTER_BLOCKS);
                } else {
                    shot = choose(hash, Shot.EDITORIAL_COLUMN, Shot.MASK_REVEAL, Shot.QUIET_TABLEAU);
                }
            }
        }
        if (shot == previousShot) {
            Shot[] order = Shot.values();
            shot = order[(shot.ordinal() + 1 + (int) (hash & 1)) % order.length];
        }
        return shot;
    }

    private static Transition pickTransition(long seed, int paragraphIndex,
                                             ParagraphKind kind, int total) {
        long hash = mix(seed, paragraphIndex + 977, kind.ordinal() * 31);
        if (kind == ParagraphKind.CHORUS && paragraphIndex > 0) {
            return Transition.MONO_GLITCH;
        }
        if (kind == ParagraphKind.BREAK || kind == ParagraphKind.OUTRO) {
            return Transition.FAST_BLUR;
        }
        return (hash & 1) == 0 ? Transition.CAMERA_PULL : Transition.FAST_BLUR;
    }

    private static Shot choose(long hash, Shot a, Shot b, Shot c) {
        int pick = (int) Math.floorMod(hash, 3);
        return pick == 0 ? a : pick == 1 ? b : c;
    }

    private static long mix(long seed, long a, long b) {
        long value = seed ^ (a * 0x9E3779B97F4A7C15L) ^ (b * 0xC2B2AE3D27D4EB4FL);
        value ^= value >>> 29;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 32;
        return value;
    }

    private static String normalize(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\s\\p{Punct}]", "").toLowerCase(Locale.US);
    }
}
