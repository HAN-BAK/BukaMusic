package com.airmusic.player.sonnet;

import com.airmusic.player.lyrics.LyricLine;
import com.airmusic.player.lyrics.Lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Port of Folia's Sonnet timeline compiler.
 *
 * <p>Lyrics are split into semantic segments, grouped into paragraphs the way
 * the original does (median gap threshold, line caps, duration caps), then into
 * shots of at most four lines / six seconds. Every shot gets a deterministic
 * kind, a camera position on the scene plane and a transition kind, using the
 * same mixing rules as the original director.
 */
public final class SonnetDirector {

    public enum Kind {
        EDITORIAL_COLUMN,
        TYPE_IMPACT,
        FRAGMENT_COLLAGE,
        TRACKING_RIBBON,
        MASK_REVEAL,
        POSTER_BLOCKS,
        QUIET_TABLEAU
    }

    public enum Role {HERO, SEMI_HERO, SUPPORT, DECORATION}

    public enum TransitionKind {FAST_BLUR, MONO_GLITCH, CAMERA_PULL}

    public static final class Grapheme {
        public final String text;
        public final long startMs;
        public final long endMs;

        Grapheme(String text, long startMs, long endMs) {
            this.text = text;
            this.startMs = startMs;
            this.endMs = Math.max(endMs, startMs + 40);
        }
    }

    public static final class Segment {
        public final String text;
        public final long startMs;
        public final long endMs;
        public final List<Grapheme> graphemes;
        public final boolean wordLike;

        Segment(String text, long startMs, long endMs, List<Grapheme> graphemes) {
            this.text = text;
            this.startMs = startMs;
            this.endMs = Math.max(endMs, startMs + 80);
            this.graphemes = graphemes;
            this.wordLike = text != null && !text.trim().isEmpty();
        }
    }

    public static final class Shot {
        public int index;
        public final Kind kind;
        public final long startMs;
        public final long endMs;
        public final List<Segment> segments;
        public final float cameraX;
        public final float cameraY;
        public final float cameraZoom;
        public final float cameraRotation;
        public final int paragraphIndex;
        /** True for the virtual three-dot shot generated inside a long break. */
        public final boolean interlude;
        /** Break markers follow the orientation of the shot before the gap. */
        public final boolean interludeVertical;

        Shot(int index, Kind kind, long startMs, long endMs, List<Segment> segments,
             float cameraX, float cameraY, float cameraZoom, float cameraRotation,
             int paragraphIndex, boolean interlude, boolean interludeVertical) {
            this.index = index;
            this.kind = kind;
            this.startMs = startMs;
            this.endMs = endMs;
            this.segments = segments;
            this.cameraX = cameraX;
            this.cameraY = cameraY;
            this.cameraZoom = cameraZoom;
            this.cameraRotation = cameraRotation;
            this.paragraphIndex = paragraphIndex;
            this.interlude = interlude;
            this.interludeVertical = interludeVertical;
        }

        public long durationMs() {
            return Math.max(1, endMs - startMs);
        }
    }

    public static final class Paragraph {
        public final int index;
        public final String kind;
        public final long startMs;
        public final long endMs;
        public final List<Shot> shots;
        public final TransitionKind transitionOut;
        public final long transitionStartMs;
        public final long transitionEndMs;

        Paragraph(int index, String kind, long startMs, long endMs, List<Shot> shots,
                  TransitionKind transitionOut, long transitionStartMs, long transitionEndMs) {
            this.index = index;
            this.kind = kind;
            this.startMs = startMs;
            this.endMs = endMs;
            this.shots = shots;
            this.transitionOut = transitionOut;
            this.transitionStartMs = transitionStartMs;
            this.transitionEndMs = transitionEndMs;
        }
    }

    public final List<Paragraph> paragraphs;
    public final List<Shot> shots;
    public final float paragraphGapThresholdMs;

    private SonnetDirector(List<Paragraph> paragraphs, List<Shot> shots,
                           float paragraphGapThresholdMs) {
        this.paragraphs = paragraphs;
        this.shots = shots;
        this.paragraphGapThresholdMs = paragraphGapThresholdMs;
    }

    public boolean isEmpty() {
        return shots.isEmpty();
    }

    public int shotIndexAt(long timeMs) {
        if (shots.isEmpty() || timeMs < shots.get(0).startMs) return -1;
        int lo = 0;
        int hi = shots.size() - 1;
        int result = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (shots.get(mid).startMs <= timeMs) {
                result = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Compilation
    // ------------------------------------------------------------------

    public static SonnetDirector compile(Lyrics lyrics, String seedText) {
        return compile(lyrics, seedText, null);
    }

    public static SonnetDirector compile(Lyrics lyrics, String seedText, List<String> hints) {
        return compile(lyrics, seedText, hints, null);
    }

    public static SonnetDirector compile(Lyrics lyrics, String seedText,
                                         List<String> hints, List<Long> onsetsMs) {
        if (lyrics == null || lyrics.lines.isEmpty()) {
            return new SonnetDirector(new ArrayList<>(), new ArrayList<>(), 0f);
        }
        List<LineDraft> compiled = new ArrayList<>();
        float wordPaceMs = estimatePace(lyrics.lines);
        for (int i = 0; i < lyrics.lines.size(); i++) {
            LyricLine line = lyrics.lines.get(i);
            long lineEnd = line.endMs;
            if (i + 1 < lyrics.lines.size()) {
                lineEnd = Math.min(lineEnd, lyrics.lines.get(i + 1).startMs);
            }
            List<Segment> segments = splitSegments(line.text, line.startMs,
                    Math.max(lineEnd, line.startMs + 250L), hints, wordPaceMs, onsetsMs);
            compiled.add(new LineDraft(line, segments));
        }

        float gapThreshold = resolveParagraphGapThreshold(compiled);
        List<List<LineDraft>> drafts = new ArrayList<>();
        List<LineDraft> current = new ArrayList<>();
        for (int i = 0; i < compiled.size(); i++) {
            LineDraft draft = compiled.get(i);
            LineDraft previous = i > 0 ? compiled.get(i - 1) : null;
            boolean boundary = false;
            if (previous != null) {
                long gap = draft.line.startMs - previous.line.endMs;
                if (gap >= gapThreshold) boundary = true;
            }
            if (boundary && !current.isEmpty()) {
                drafts.addAll(splitOversized(current));
                current = new ArrayList<>();
            }
            current.add(draft);
        }
        if (!current.isEmpty()) drafts.addAll(splitOversized(current));

        String seed = seedText == null || seedText.isEmpty() ? "sonnet" : seedText;
        List<Paragraph> paragraphs = new ArrayList<>();
        List<Shot> allShots = new ArrayList<>();
        Kind previousShot = null;
        TransitionKind previousTransition = null;
        for (int p = 0; p < drafts.size(); p++) {
            List<LineDraft> draft = drafts.get(p);
            String paragraphKind = classifyParagraph(draft, p, drafts.size());
            List<List<LineDraft>> groups = groupShotLines(draft);
            List<Shot> shots = new ArrayList<>();
            for (int s = 0; s < groups.size(); s++) {
                List<LineDraft> group = groups.get(s);
                int wordCount = 0;
                for (LineDraft line : group) {
                    for (Segment segment : line.segments) {
                        if (segment.wordLike) wordCount++;
                    }
                }
                Kind kind = chooseShotKind(seed, p, s, group, paragraphKind, previousShot, wordCount);
                int hash = hashSeed(seed + ":" + p + ":" + s + ":camera");
                float zoomBase = kind == Kind.POSTER_BLOCKS ? 1.02f
                        : kind == Kind.QUIET_TABLEAU ? 1.12f : 1.22f;
                float zoomSpan = kind == Kind.POSTER_BLOCKS ? 0.16f
                        : kind == Kind.QUIET_TABLEAU ? 0.20f : 0.26f;
                float[] camera = new float[]{
                        ((hash & 255) / 255f - 0.5f) * 0.10f,
                        (((hash >>> 8) & 255) / 255f - 0.5f) * 0.08f,
                        zoomBase + (((hash >>> 16) & 255) / 255f) * zoomSpan,
                        (((hash >>> 24) & 255) / 255f - 0.5f) * 0.08f
                };
                long start = group.get(0).line.startMs;
                long end = group.get(group.size() - 1).line.endMs;
                List<Segment> segments = new ArrayList<>();
                for (LineDraft line : group) segments.addAll(line.segments);
                List<List<Segment>> chunks = chunkSegments(segments);
                for (int c = 0; c < chunks.size(); c++) {
                    List<Segment> chunk = chunks.get(c);
                    long chunkStart = c == 0 ? start : chunk.get(0).startMs;
                    long chunkEnd = c + 1 < chunks.size()
                            ? chunks.get(c + 1).get(0).startMs
                            : end;
                    int chunkHash = hashSeed(seed + ":" + p + ":" + s + ":camera:" + c);
                    float[] chunkCamera = new float[]{
                            ((chunkHash & 255) / 255f - 0.5f) * 0.10f,
                            (((chunkHash >>> 8) & 255) / 255f - 0.5f) * 0.08f,
                            zoomBase + (((chunkHash >>> 16) & 255) / 255f) * zoomSpan,
                            (((chunkHash >>> 24) & 255) / 255f - 0.5f) * 0.08f
                    };
                    Shot shot = new Shot(allShots.size(), kind, chunkStart, chunkEnd, chunk,
                            chunkCamera[0], chunkCamera[1], chunkCamera[2], chunkCamera[3],
                            p, false, false);
                    shots.add(shot);
                    allShots.add(shot);
                    previousShot = kind;
                }
            }
            long paragraphEnd = draft.get(draft.size() - 1).line.endMs;
            TransitionKind transition = null;
            long transStart = 0;
            long transEnd = 0;
            if (p + 1 < drafts.size()) {
                List<TransitionKind> kinds = new ArrayList<>();
                Collections.addAll(kinds, TransitionKind.values());
                transition = chooseTransition(seed + ":" + p + ":transition", previousTransition);
                previousTransition = transition;
                long nextStart = drafts.get(p + 1).get(0).line.startMs;
                long gap = Math.max(0, nextStart - paragraphEnd);
                long duration = (long) (Math.min(0.3, Math.max(0.16, gap > 0
                        ? gap / 1000.0 * 0.5 : 0.2)) * 1000.0);
                transStart = Math.max(draft.get(0).line.startMs, nextStart - duration);
                transEnd = nextStart;
            }
            paragraphs.add(new Paragraph(p, paragraphKind, draft.get(0).line.startMs,
                    paragraphEnd, shots, transition, transStart, transEnd));
        }
        List<Shot> withInterludes = new ArrayList<>(allShots.size());
        for (int i = 0; i < allShots.size(); i++) {
            Shot shot = allShots.get(i);
            withInterludes.add(shot);
            if (i + 1 >= allShots.size()) continue;
            Shot next = allShots.get(i + 1);
            long gap = next.startMs - shot.endMs;
            if (gap >= INTERLUDE_MIN_MS) {
                withInterludes.add(buildInterludeShot(shot, next, gap, seed));
            }
        }
        for (int i = 0; i < withInterludes.size(); i++) {
            withInterludes.get(i).index = i;
        }
        return new SonnetDirector(paragraphs, withInterludes, gapThreshold);
    }

    private static final long INTERLUDE_MIN_MS = 6_000L;
    /** Lyrics appear this much before the detected onset, to match the voice. */
    private static final long LYRIC_LEAD_MS = 200L;

    /**
     * Long lines are split into several shots of at most seven words, so the
     * typography engine never has to shrink the whole composition to a size
     * where the lyric becomes unreadable.
     */
    private static List<List<Segment>> chunkSegments(List<Segment> segments) {
        List<List<Segment>> chunks = new ArrayList<>();
        int limit = 7;
        for (int i = 0; i < segments.size(); i += limit) {
            chunks.add(new ArrayList<>(segments.subList(i, Math.min(segments.size(), i + limit))));
        }
        if (chunks.isEmpty()) chunks.add(new ArrayList<>());
        return chunks;
    }

    /**
     * A long break is rendered by the same typography pipeline as the lyrics:
     * a virtual shot whose segments are three dots, each timed to one third of
     * the break. The camera therefore sways across the scene plane and the
     * dots enter exactly like lyric glyphs.
     */
    private static Shot buildInterludeShot(Shot previous, Shot next, long gap, String seed) {
        int hash = hashSeed(seed + ":interlude:" + previous.index);
        float zoom = 1.08f + (((hash >>> 16) & 255) / 255f) * 0.12f;
        float cameraX = ((hash & 255) / 255f - 0.5f) * 0.06f;
        float cameraY = (((hash >>> 8) & 255) / 255f - 0.5f) * 0.05f;
        boolean vertical = previous.kind == Kind.QUIET_TABLEAU
                || previous.kind == Kind.MASK_REVEAL
                || previous.kind == Kind.EDITORIAL_COLUMN;
        List<Segment> segments = new ArrayList<>(3);
        long gapStart = previous.endMs;
        for (int i = 0; i < 3; i++) {
            long start = gapStart + gap * i / 3;
            long end = Math.min(next.startMs, gapStart + gap * (i + 1) / 3);
            segments.add(buildSegment("\u00B7", start, Math.max(start + 120L, end)));
        }
        return new Shot(previous.index + 1, Kind.QUIET_TABLEAU,
                gapStart, next.startMs, segments,
                cameraX, cameraY, zoom, 0f, previous.paragraphIndex, true, vertical);
    }

    // ------------------------------------------------------------------
    // Line / segment parsing
    // ------------------------------------------------------------------

    private static final class LineDraft {
        final LyricLine line;
        final List<Segment> segments;

        LineDraft(LyricLine line, List<Segment> segments) {
            this.line = line;
            this.segments = segments;
        }
    }

    private static List<Segment> splitSegments(String text, long startMs, long endMs,
                                               List<String> hints, float wordPaceMs,
                                               List<Long> onsetsMs) {
        List<Segment> out = new ArrayList<>();
        if (text == null) return out;
        String value = text.trim();
        if (value.isEmpty()) return out;

        // Same offline fallback the original uses: the platform ICU word
        // segmenter (Intl.Segmenter equivalent) splits CJK into real words.
        List<String> chunks = com.airmusic.player.lyrics.WordSegmenter.segment(value, hints);
        if (chunks.isEmpty()) chunks.add(value);

        List<String> words = new ArrayList<>(chunks.size());
        List<Integer> weights = new ArrayList<>(chunks.size());
        for (String chunk : chunks) {
            String segmentText = chunk == null ? "" : chunk.trim();
            if (segmentText.isEmpty()) continue;
            words.add(segmentText);
            weights.add(Math.max(1, visibleWeight(segmentText)));
        }
        if (words.isEmpty()) return out;

        long duration = Math.max(250L, endMs - startMs);
        float estimatedTotal = 0f;
        for (int weight : weights) estimatedTotal += weight * wordPaceMs;
        float fit = estimatedTotal > duration * 0.92f
                ? (duration * 0.92f) / Math.max(1f, estimatedTotal) : 1f;

        List<Long> starts = new ArrayList<>(words.size());
        long cursor = startMs;
        for (int i = 0; i < words.size(); i++) {
            starts.add(cursor);
            cursor += Math.max(90L, Math.round(weights.get(i) * wordPaceMs * fit));
        }
        if (onsetsMs != null && !onsetsMs.isEmpty()) {
            starts = snapToOnsets(starts, startMs, endMs, onsetsMs);
        }
        for (int i = 0; i < words.size(); i++) {
            long wordStart = Math.max(0L, starts.get(i) - LYRIC_LEAD_MS);
            long wordEnd = i + 1 < words.size()
                    ? Math.max(wordStart + 60L, starts.get(i + 1) - LYRIC_LEAD_MS)
                    : Math.min(endMs, wordStart + Math.max(120L,
                    Math.round(weights.get(i) * wordPaceMs * fit)));
            if (wordEnd <= wordStart) wordEnd = wordStart + 60L;
            out.add(buildSegment(words.get(i), wordStart, wordEnd));
        }
        return out;
    }

    /**
     * Snaps estimated word starts to nearby vocal onsets while keeping the
     * order strictly monotonic (a word can never start before the previous one).
     */
    private static List<Long> snapToOnsets(List<Long> estimated, long lineStart,
                                           long lineEnd, List<Long> onsets) {
        List<Long> out = new ArrayList<>(estimated.size());
        long previous = lineStart - 250L;
        int searchFrom = 0;
        for (long guess : estimated) {
            long best = -1L;
            long bestDelta = Long.MAX_VALUE;
            for (int i = searchFrom; i < onsets.size(); i++) {
                long onset = onsets.get(i);
                if (onset < previous + 110L) continue;
                if (onset > lineEnd + 300L) break;
                long delta = Math.abs(onset - guess);
                if (delta < bestDelta) {
                    bestDelta = delta;
                    best = onset;
                }
                if (onset > guess + 650L) break;
            }
            if (best >= 0L && bestDelta <= 600L) {
                out.add(best);
                previous = best;
                while (searchFrom < onsets.size() && onsets.get(searchFrom) <= best) {
                    searchFrom++;
                }
            } else {
                long fallback = Math.max(guess, previous + 120L);
                out.add(fallback);
                previous = fallback;
            }
        }
        return out;
    }

    /** Median milliseconds per syllable across the whole song. */
    private static float estimatePace(List<LyricLine> lines) {
        List<Float> paces = new ArrayList<>();
        for (LyricLine line : lines) {
            long duration = line.endMs - line.startMs;
            int weight = visibleWeight(line.text);
            if (weight < 2 || duration < 500L || duration > 15_000L) continue;
            paces.add(duration / (float) weight);
        }
        if (paces.isEmpty()) return 220f;
        Collections.sort(paces);
        return Math.max(120f, Math.min(420f, paces.get(paces.size() / 2)));
    }


    private static int visibleWeight(String text) {
        if (text == null || text.isEmpty()) return 1;
        int count = 0;
        boolean previousVowel = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0xAC00 && c <= 0xD7AF)) {
                count++;
                previousVowel = false;
            } else if (c >= 0x3040 && c <= 0x30FF) {
                // Small kana fold into the preceding mora; everything else is one.
                if ("\u3041\u3043\u3045\u3047\u3049\u3083\u3085\u3087\u308E\u30A1\u30A3\u30A5\u30A7\u30A9\u30E3\u30E5\u30E7\u30EE\u30F5\u30F6\u30C3"
                        .indexOf(c) < 0) {
                    count++;
                }
                previousVowel = false;
            } else if (Character.isLetter(c)) {
                boolean vowel = "aeiouyAEIOUY".indexOf(c) >= 0;
                if (vowel && !previousVowel) count++;
                previousVowel = vowel;
            } else if (Character.isDigit(c)) {
                count++;
                previousVowel = false;
            }
        }
        return Math.max(1, count == 0 ? Math.max(1, text.length() / 3) : count);
    }

    private static Segment buildSegment(String text, long startMs, long endMs) {
        List<int[]> codePoints = new ArrayList<>();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int count = Character.charCount(cp);
            codePoints.add(new int[]{cp, count});
            i += count;
        }
        int count = Math.max(1, codePoints.size());
        long duration = Math.max(60, endMs - startMs);
        List<Grapheme> graphemes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int[] item = codePoints.get(i);
            long gStart = startMs + duration * i / count;
            long gEnd = startMs + duration * (i + 1) / count;
            graphemes.add(new Grapheme(new String(Character.toChars(item[0])), gStart, gEnd));
        }
        return new Segment(text, startMs, endMs, graphemes);
    }

    // ------------------------------------------------------------------
    // Paragraph / shot grouping (mirrors sonnetProgram.ts)
    // ------------------------------------------------------------------

    private static float resolveParagraphGapThreshold(List<LineDraft> lines) {
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            long gap = lines.get(i).line.startMs - lines.get(i - 1).line.endMs;
            if (gap > 0) gaps.add(gap);
        }
        if (gaps.isEmpty()) return 2500f;
        Collections.sort(gaps);
        int mid = gaps.size() / 2;
        float median = gaps.size() % 2 == 0
                ? (gaps.get(mid - 1) + gaps.get(mid)) / 2f : gaps.get(mid);
        return Math.max(1250f, Math.min(3500f, median * 2.5f));
    }

    private static List<List<LineDraft>> splitOversized(List<LineDraft> draft) {
        List<List<LineDraft>> output = new ArrayList<>();
        List<LineDraft> remaining = new ArrayList<>(draft);
        int guard = 0;
        while (remaining.size() > 6
                || (remaining.size() > 1
                && remaining.get(remaining.size() - 1).line.endMs - remaining.get(0).line.startMs > 18_000L)) {
            if (guard++ > 1000) break;
            int bestSplit = -1;
            long bestGap = -1;
            for (int i = 2; i <= remaining.size() - 2; i++) {
                long gap = remaining.get(i).line.startMs - remaining.get(i - 1).line.endMs;
                if (gap > bestGap) {
                    bestGap = gap;
                    bestSplit = i;
                }
            }
            if (bestSplit < 1) bestSplit = Math.min(4, remaining.size() - 1);
            output.add(new ArrayList<>(remaining.subList(0, bestSplit)));
            remaining = new ArrayList<>(remaining.subList(bestSplit, remaining.size()));
        }
        output.add(remaining);
        return output;
    }

    private static List<List<LineDraft>> groupShotLines(List<LineDraft> lines) {
        List<List<LineDraft>> groups = new ArrayList<>();
        List<LineDraft> current = new ArrayList<>();
        long groupStart = 0;
        for (LineDraft line : lines) {
            if (current.isEmpty()) {
                current.add(line);
                groupStart = line.line.startMs;
            } else if (current.size() < 4 && line.line.endMs - groupStart <= 6_000L) {
                current.add(line);
            } else {
                groups.add(current);
                current = new ArrayList<>();
                current.add(line);
                groupStart = line.line.startMs;
            }
        }
        if (!current.isEmpty()) groups.add(current);
        return groups;
    }

    private static String classifyParagraph(List<LineDraft> lines, int index, int total) {
        StringBuilder full = new StringBuilder();
        for (LineDraft line : lines) full.append(line.line.text).append(' ');
        String text = full.toString();
        if (text.toLowerCase(Locale.US).contains("chorus")
                || text.contains("\u526F\u6B4C")) return "chorus";
        if (text.toLowerCase(Locale.US).contains("interlude")
                || text.toLowerCase(Locale.US).contains("bridge")
                || text.contains("\u95F4\u594F") || text.contains("\u9593\u594F")) return "break";
        if (index == total - 1) return "outro";
        long duration = lines.get(lines.size() - 1).line.endMs - lines.get(0).line.startMs;
        int segmentCount = 0;
        int punctuation = 0;
        for (LineDraft line : lines) {
            for (Segment segment : line.segments) {
                if (segment.wordLike) segmentCount++;
            }
            for (int i = 0; i < line.line.text.length(); i++) {
                char c = line.line.text.charAt(i);
                if (c == '!' || c == '?' || c == '\uFF01' || c == '\uFF1F' || c == '\u2026') {
                    punctuation++;
                }
            }
        }
        if (duration <= 3_500L || segmentCount <= 3) return "breath";
        if (punctuation >= 2 || segmentCount / Math.max(1.0, duration / 1000.0) > 2.5) return "lift";
        return "verse";
    }

    private static Kind chooseShotKind(String seed, int paragraphIndex, int shotIndex,
                                       List<LineDraft> group, String paragraphKind,
                                       Kind previous, int wordCount) {
        StringBuilder signature = new StringBuilder();
        for (LineDraft line : group) signature.append(line.line.text).append('|');
        Kind[] kinds = Kind.values();
        int start = Math.floorMod(hashSeed(seed + ":" + paragraphIndex + ":" + shotIndex + ":" + signature),
                kinds.length);
        Kind chosen = kinds[start];
        for (int offset = 0; offset < kinds.length; offset++) {
            Kind candidate = kinds[(start + offset) % kinds.length];
            if (candidate != previous) {
                chosen = candidate;
                break;
            }
        }
        if ("breath".equals(paragraphKind) && shotIndex == 0 && wordCount <= 2) {
            chosen = Kind.QUIET_TABLEAU;
        }
        if ("chorus".equals(paragraphKind) && chosen == Kind.QUIET_TABLEAU) {
            chosen = Kind.TYPE_IMPACT;
        }
        return chosen;
    }

    private static TransitionKind chooseTransition(String seed, TransitionKind previous) {
        TransitionKind[] kinds = TransitionKind.values();
        int start = Math.floorMod(hashSeed(seed), kinds.length);
        TransitionKind chosen = kinds[start];
        for (int offset = 0; offset < kinds.length; offset++) {
            TransitionKind candidate = kinds[(start + offset) % kinds.length];
            if (candidate != previous) {
                chosen = candidate;
                break;
            }
        }
        return chosen;
    }

    /** FNV-1a over UTF-16 units, matching the deterministic spirit of sonnetRandom. */
    public static int hashSeed(String value) {
        int hash = 0x811C9DC5;
        if (value == null) return hash;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x01000193;
        }
        return hash;
    }
}
