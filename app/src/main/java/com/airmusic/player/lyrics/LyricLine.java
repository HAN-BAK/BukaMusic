package com.airmusic.player.lyrics;

/** One timed lyric line. */
public final class LyricLine {

    public final long startMs;
    public final long endMs;
    public final String text;

    public LyricLine(long startMs, long endMs, String text) {
        this.startMs = startMs;
        this.endMs = Math.max(endMs, startMs + 1);
        this.text = text == null ? "" : text;
    }
}
