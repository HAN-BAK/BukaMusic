package com.airmusic.player.lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Parsed lyrics for one track. */
public final class Lyrics {

    public static final Lyrics EMPTY =
            new Lyrics(Collections.unmodifiableList(new ArrayList<>()), false);

    public final List<LyricLine> lines;
    /** True when at least one line carries a real LRC timestamp. */
    public final boolean synced;

    public Lyrics(List<LyricLine> lines, boolean synced) {
        this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
        this.synced = synced;
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /** Index of the line that should be on screen at {@code positionMs}, or -1. */
    public int indexAt(long positionMs) {
        if (lines.isEmpty() || positionMs < lines.get(0).startMs) return -1;
        int lo = 0;
        int hi = lines.size() - 1;
        int result = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).startMs <= positionMs) {
                result = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return result;
    }
}
