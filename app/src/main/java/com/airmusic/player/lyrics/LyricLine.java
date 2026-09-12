package com.airmusic.player.lyrics;

/** One timed lyric line. */
public final class LyricLine {

    public final long startMs;
    public final long endMs;
    public final String text;
    /** Optional translation shown as a smaller subtitle under the main line. */
    public final String translation;

    public LyricLine(long startMs, long endMs, String text) {
        this(startMs, endMs, text, null);
    }

    public LyricLine(long startMs, long endMs, String text, String translation) {
        this.startMs = startMs;
        this.endMs = Math.max(endMs, startMs + 1);
        this.text = text == null ? "" : text;
        this.translation = translation == null || translation.trim().isEmpty()
                ? null : translation.trim();
    }
}
