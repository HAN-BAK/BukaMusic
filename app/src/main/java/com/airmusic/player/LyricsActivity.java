package com.airmusic.player;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.airmusic.player.library.Track;
import com.airmusic.player.lyrics.LyricRepository;
import com.airmusic.player.lyrics.Lyrics;
import com.airmusic.player.service.PlaybackService;
import com.airmusic.player.util.BlurBackground;
import com.airmusic.player.util.PlayerUiState;
import com.airmusic.player.util.StateBus;
import com.airmusic.player.view.SonnetLyricsView;

/**
 * Full-screen native lyric view opened from the bottom bar. The audio keeps
 * playing in {@link PlaybackService}; this activity only renders and follows
 * the shared playback state, so local / AirPlay / multi-room playback all use
 * the same screen.
 */
public class LyricsActivity extends BaseActivity {

    private SonnetLyricsView lyricsView;
    private TextView titleView;
    private TextView artistView;
    private TextView emptyView;

    private Track loadedTrack;
    private long loadedDurationMs = -1;
    private int loadGeneration;
    private Object loadedArt;

    private final StateBus.Listener listener = this::render;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lyrics);
        BlurBackground.apply(this, R.drawable.bg_main_gradient);

        lyricsView = findViewById(R.id.lyrics_view);
        titleView = findViewById(R.id.lyrics_title);
        artistView = findViewById(R.id.lyrics_artist);
        emptyView = findViewById(R.id.lyrics_empty);
        ImageButton back = findViewById(R.id.btn_lyrics_back);
        if (back != null) back.setOnClickListener(v -> finish());
    }

    @Override
    protected void onStart() {
        super.onStart();
        StateBus.get().addListener(listener);
        render(StateBus.get().getState());
    }

    @Override
    protected void onStop() {
        StateBus.get().removeListener(listener);
        super.onStop();
    }

    private void render(PlayerUiState state) {
        if (state == null || lyricsView == null) return;

        if (titleView != null) {
            titleView.setText(state.title == null ? "" : state.title);
        }
        if (artistView != null) {
            artistView.setText(state.artist == null ? "" : state.artist);
        }

        if (state.art != loadedArt) {
            loadedArt = state.art;
            lyricsView.setAccentColor(dominantColor(state.art));
        }
        lyricsView.setPlaybackState(state.positionMs, state.playing);

        if (state.playing) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        PlaybackService service = PlaybackService.getInstance();
        Track track = service == null ? null : service.getCurrentTrack();
        if (track == null) {
            // AirPlay / multi-room receiver streams carry no local file to read
            // tags from; keep the visual screen but show the empty hint.
            if (loadedTrack != null) {
                loadedTrack = null;
                loadedDurationMs = -1;
                loadGeneration++;
                lyricsView.setLyrics(Lyrics.EMPTY);
            }
            showEmpty(true);
            return;
        }

        if (loadedTrack != null && sameTrack(loadedTrack, track)
                && loadedDurationMs == state.durationMs) {
            return;
        }
        loadedTrack = track;
        loadedDurationMs = state.durationMs;
        final int generation = ++loadGeneration;
        final long duration = state.durationMs;
        showEmpty(false);
        LyricRepository.loadAsync(this, track, duration, lyrics -> {
            if (generation != loadGeneration || isFinishing() || isDestroyed()) return;
            lyricsView.setLyrics(lyrics);
            showEmpty(lyrics.isEmpty());
        });
    }

    private void showEmpty(boolean show) {
        if (emptyView != null) {
            emptyView.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private static boolean sameTrack(Track a, Track b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.uri != null && b.uri != null) return a.uri.equals(b.uri);
        return a.filePath != null && a.filePath.equals(b.filePath);
    }

    /** Picks a vivid but not blinding colour from the cover for the accents. */
    private static int dominantColor(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return 0;
        try {
            float bestScore = 0f;
            float bestHue = 0f;
            float bestSat = 0f;
            float[] hsv = new float[3];
            int samples = 16;
            for (int y = 0; y < samples; y++) {
                int py = Math.min(bitmap.getHeight() - 1, y * bitmap.getHeight() / samples);
                for (int x = 0; x < samples; x++) {
                    int px = Math.min(bitmap.getWidth() - 1, x * bitmap.getWidth() / samples);
                    int color = bitmap.getPixel(px, py);
                    Color.colorToHSV(color, hsv);
                    if (hsv[2] < 0.22f || hsv[1] < 0.18f) continue;
                    float score = hsv[1] * hsv[2];
                    if (score > bestScore) {
                        bestScore = score;
                        bestHue = hsv[0];
                        bestSat = hsv[1];
                    }
                }
            }
            if (bestScore <= 0f) return 0;
            hsv[0] = bestHue;
            hsv[1] = Math.min(0.68f, Math.max(0.42f, bestSat));
            hsv[2] = 0.96f;
            return Color.HSVToColor(hsv);
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
