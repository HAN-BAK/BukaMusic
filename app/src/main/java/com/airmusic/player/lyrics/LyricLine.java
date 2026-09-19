package com.airmusic.player.lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One timed lyric line. */
public final class LyricLine {

    public final long startMs;
    public final long endMs;
    public final String text;
    /** Optional translation shown as a smaller subtitle under the main line. */
    public final String translation;
    /**
     * Real per-word timings when the source provides them (karaoke / enhanced
     * LRC / YRC). Empty when the word timing has to be estimated from the line.
     */
    public final List<Word> words;

    /** One word with its own start / end time inside the line. */
    public static final class Word {
        public final long startMs;
        public final long endMs;
        public final String text;

        public Word(long startMs, long endMs, String text) {
            this.startMs = Math.max(0L, startMs);
            this.endMs = Math.max(this.startMs + 20L, endMs);
            this.text = text == null ? "" : text;
        }
    }

    public LyricLine(long startMs, long endMs, String text) {
        this(startMs, endMs, text, null, null);
    }

    public LyricLine(long startMs, long endMs, String text, String translation) {
        this(startMs, endMs, text, translation, null);
    }

    public LyricLine(long startMs, long endMs, String text, String translation,
                     List<Word> words) {
        this.startMs = startMs;
        this.endMs = Math.max(endMs, startMs + 1);
        this.text = text == null ? "" : text;
        this.translation = translation == null || translation.trim().isEmpty()
                ? null : translation.trim();
        this.words = words == null || words.isEmpty()
                ? Collections.<Word>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(words));
    }

    /** True when this line carries real per-word timings. */
    public boolean hasWordTiming() {
        return !words.isEmpty();
    }
}
