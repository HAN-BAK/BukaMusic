package com.airmusic.player.lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Caps each line's render window the way the original's renderHints does.
 *
 * <p>Plain LRC only gives a start time per line, so our parser used the next
 * line's start as this line's end. During a long instrumental break that made
 * one line span the whole break, and its words slowly appeared across the
 * interlude. Here the line is capped to a realistic singing duration estimated
 * from the singer's own pace on the other lines; the gap afterwards becomes a
 * real paragraph break instead of stretched typography.
 */
public final class LyricTiming {

    private static final float DEFAULT_PACE_MS_PER_CHAR = 210f;
    private static final float MIN_PACE_MS_PER_CHAR = 85f;
    private static final float MAX_PACE_MS_PER_CHAR = 420f;
    private static final long MIN_LINE_DURATION_MS = 900L;
    private static final long MAX_LINE_DURATION_MS = 8_000L;
    private static final long EXIT_POLISH_MS = 350L;
    private static final long SHORT_GAP_TOLERANCE_MS = 1_200L;

    private LyricTiming() {
    }

    public static Lyrics fit(Lyrics lyrics) {
        if (lyrics == null || lyrics.lines.size() < 2) return lyrics;
        List<LyricLine> input = lyrics.lines;
        float pace = estimatePace(input);
        List<LyricLine> output = new ArrayList<>(input.size());
        boolean changed = false;
        for (int i = 0; i < input.size(); i++) {
            LyricLine line = input.get(i);
            long nextStart = i + 1 < input.size()
                    ? input.get(i + 1).startMs : Long.MAX_VALUE;
            long capDuration = Math.max(MIN_LINE_DURATION_MS,
                    Math.min(MAX_LINE_DURATION_MS, Math.round(visibleWeight(line.text) * pace)))
                    + EXIT_POLISH_MS;
            long cappedEnd = line.startMs + capDuration;
            long end;
            if (nextStart == Long.MAX_VALUE) {
                end = Math.min(line.endMs, cappedEnd);
            } else if (nextStart - line.startMs <= capDuration + SHORT_GAP_TOLERANCE_MS) {
                // Normal cadence: the next line follows closely enough that the
                // authored window is trustworthy.
                end = Math.min(nextStart, Math.max(line.endMs, line.startMs + 300L));
            } else {
                // Long instrumental gap: stop the line at its estimated end.
                end = Math.min(nextStart, cappedEnd);
            }
            end = Math.max(end, line.startMs + 250L);
            if (end != line.endMs) changed = true;
            output.add(new LyricLine(line.startMs, end, line.text, line.translation));
        }
        return changed ? new Lyrics(output, lyrics.synced) : lyrics;
    }

    private static float estimatePace(List<LyricLine> lines) {
        List<Float> paces = new ArrayList<>();
        for (LyricLine line : lines) {
            long duration = line.endMs - line.startMs;
            int weight = visibleWeight(line.text);
            if (weight < 2 || duration < 400L || duration > 15_000L) continue;
            paces.add(duration / (float) weight);
        }
        if (paces.isEmpty()) return DEFAULT_PACE_MS_PER_CHAR;
        Collections.sort(paces);
        float median = paces.get(paces.size() / 2);
        return Math.max(MIN_PACE_MS_PER_CHAR, Math.min(MAX_PACE_MS_PER_CHAR, median));
    }

    private static int visibleWeight(String text) {
        if (text == null) return 1;
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) count++;
        }
        return Math.max(1, count == 0 ? text.length() : count);
    }
}
