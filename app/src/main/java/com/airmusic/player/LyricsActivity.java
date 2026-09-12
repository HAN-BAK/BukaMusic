package com.airmusic.player;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.GestureDetector;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.airmusic.player.library.Track;
import com.airmusic.player.lyrics.LyricRepository;
import com.airmusic.player.lyrics.Lyrics;
import com.airmusic.player.service.PlaybackService;
import com.airmusic.player.sonnet.LyricsGlView;
import com.airmusic.player.audio.OnsetAnalyzer;
import com.airmusic.player.util.BlurBackground;
import com.airmusic.player.util.PlayerUiState;
import com.airmusic.player.util.StateBus;

/**
 * Full-screen native lyric view opened from the bottom bar. The audio keeps
 * playing in {@link PlaybackService}; this activity only renders and follows
 * the shared playback state, so local / AirPlay / multi-room playback all use
 * the same screen.
 */
public class LyricsActivity extends BaseActivity {

    private LyricsGlView lyricsView;

    /** The lyric stage draws its own letterbox area, so it fills the window. */
    @Override
    protected boolean keepBoxAspectWrapper() {
        return false;
    }
    private TextView titleView;
    private TextView artistView;
    private View headerView;
    private boolean headerVisible = true;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideHeaderRunnable = this::hideHeader;
    private GestureDetector gestureDetector;

    private Track loadedTrack;
    private long loadedDurationMs = -1;
    private int loadGeneration;
    private Object loadedArt;
    /** Version of the multi-room lyrics currently on screen (-1 = none). */
    private long loadedRemoteVersion = -1;
    /** What the stage is showing right now; used to decide about fades. */
    private Lyrics displayedLyrics = Lyrics.EMPTY;
    private float contentAlpha = 1f;
    private int contentFadeToken;
    private ValueAnimator contentAnimator;
    private Lyrics pendingLyrics;
    private String pendingSeed;
    private boolean fadingOut;
    /** Fallback backdrop when the track carries no cover art. */
    private Bitmap placeholderArt;

    private final StateBus.Listener listener = this::render;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lyrics);
        // Transparent window: the GL layer lives behind the window and already
        // paints the blurred album-art backdrop, while the header views draw on
        // top of it. An opaque window background here would hide the GL scene.
        getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));

        lyricsView = findViewById(R.id.lyrics_view);
        titleView = findViewById(R.id.lyrics_title);
        artistView = findViewById(R.id.lyrics_artist);
        headerView = findViewById(R.id.lyrics_header);
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                handleDoubleTap(e.getX());
                return true;
            }
        });
        ImageButton back = findViewById(R.id.btn_lyrics_back);
        if (back != null) back.setOnClickListener(v -> finish());
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null && am.isLowRamDevice() && lyricsView != null) {
                lyricsView.setLowPower(true);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        StateBus.get().addListener(listener);
        render(StateBus.get().getState());
    }

    @Override
    protected void onResume() {
        super.onResume();
        showHeader();
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(hideHeaderRunnable);
        super.onPause();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (gestureDetector != null) {
            gestureDetector.onTouchEvent(event);
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            showHeader();
        }
        return super.dispatchTouchEvent(event);
    }

    /**
     * Double tap zones: left third = previous, middle third = play/pause,
     * right third = next. Works for local, AirPlay and multi-room receiver
     * playback because it goes through the same service commands as the
     * transport buttons.
     */
    private void handleDoubleTap(float x) {
        PlaybackService service = PlaybackService.getInstance();
        if (service == null) return;
        float width = Math.max(1f, getWindow().getDecorView().getWidth());
        float third = width / 3f;
        if (x < third) {
            service.previous();
        } else if (x > width - third) {
            service.next();
        } else {
            service.togglePlay();
        }
    }

    private void showHeader() {
        if (headerView == null) return;
        if (!headerVisible) {
            headerVisible = true;
            headerView.setVisibility(View.VISIBLE);
            headerView.animate().alpha(1f).setDuration(220).start();
        }
        uiHandler.removeCallbacks(hideHeaderRunnable);
        uiHandler.postDelayed(hideHeaderRunnable, 3000L);
    }

    private void hideHeader() {
        if (headerView == null || !headerVisible) return;
        headerVisible = false;
        headerView.animate().alpha(0f).setDuration(260)
                .withEndAction(() -> {
                    if (!headerVisible) headerView.setVisibility(View.GONE);
                })
                .start();
    }

    @Override
    protected void onStop() {
        StateBus.get().removeListener(listener);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(hideHeaderRunnable);
        if (contentAnimator != null) {
            contentFadeToken++;
            contentAnimator.cancel();
            contentAnimator = null;
        }
        super.onDestroy();
    }

    private void render(PlayerUiState state) {
        if (state == null || lyricsView == null) return;

        if (titleView != null) {
            titleView.setText(state.title == null ? "" : state.title);
        }
        if (artistView != null) {
            artistView.setText(state.artist == null ? "" : state.artist);
        }

        // The lyric stage always needs a backdrop: the album art when the
        // track has one, otherwise the dedicated placeholder cover (the same
        // image the rest of the app uses), so the screen never goes black.
        Bitmap backdrop = backdropArt(state.art);
        if (backdrop != loadedArt) {
            loadedArt = backdrop;
            lyricsView.setAccentColor(dominantColor(backdrop));
            lyricsView.setBackgroundArt(backdrop);
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
            // A multi-room receiver has no local file: the master pushes the
            // parsed lyrics over the sync connection instead.
            PlaybackService.RemoteLyrics remote =
                    state.source == PlayerUiState.Source.REMOTE && service != null
                            ? service.getRemoteLyrics() : null;
            if (remote != null && remote.lyrics != null && !remote.lyrics.isEmpty()) {
                if (loadedRemoteVersion != remote.version) {
                    loadedRemoteVersion = remote.version;
                    loadedTrack = null;
                    loadedDurationMs = -1;
                    loadGeneration++;
                    lyricsView.setOnsets(null);
                    lyricsView.setHints(remote.hints);
                    swapLyrics(remote.lyrics, remote.seed);
                }
                return;
            }
            loadedRemoteVersion = -1;
            // AirPlay streams carry no file to read tags from either: keep the
            // visual screen but show the empty hint.
            if (!displayedLyrics.isEmpty()) {
                loadedTrack = null;
                loadedDurationMs = -1;
                loadGeneration++;
                lyricsView.setOnsets(null);
                swapLyrics(Lyrics.EMPTY, "sonnet");
            }
            return;
        }
        loadedRemoteVersion = -1;

        if (loadedTrack != null && sameTrack(loadedTrack, track)) {
            // The player and the tag reader report slightly different track
            // lengths and the value flickers between the two while playing.
            // Only a first real duration (unknown -> known) is worth a reload;
            // otherwise this re-triggered the whole swap on every state update.
            boolean durationBecameKnown = loadedDurationMs <= 0 && state.durationMs > 0;
            if (!durationBecameKnown) return;
        }
        loadedTrack = track;
        loadedDurationMs = state.durationMs;
        final int generation = ++loadGeneration;
        final long duration = state.durationMs;
        // Fade the previous track's lyrics out right away, so the fade lines up
        // with the track change instead of waiting for the file to be parsed.
        if (!displayedLyrics.isEmpty()) fadeOutForSwap();
        LyricRepository.loadAsync(this, track, duration, lyrics -> {
            if (generation != loadGeneration || isFinishing() || isDestroyed()) return;
            lyricsView.setHints(hintsFor(track));
            swapLyrics(lyrics, seedFor(track));
        });
        // Analyse the vocal onsets once in the background; the lyric program is
        // rebuilt with real onset times as soon as the result is ready.
        OnsetAnalyzer.analyzeAsync(this, track, onsets -> {
            if (generation != loadGeneration || isFinishing() || isDestroyed()) return;
            lyricsView.setOnsets(onsets);
        });
    }

    /** Title / artist / album tokens used to protect names during segmentation. */
    private static java.util.List<String> hintsFor(Track track) {
        if (track == null) return new java.util.ArrayList<>();
        return com.airmusic.player.lyrics.LyricWire.hintsFor(
                track.title, track.artist, track.album);
    }

    private static String seedFor(Track track) {
        if (track == null) return "sonnet";
        return com.airmusic.player.lyrics.LyricWire.seedFor(
                track.title, track.artist, track.album);
    }

    /**
     * Swaps the lyric content with a short fade so changing tracks (or the
     * master switching on a multi-room receiver) does not snap abruptly.
     */
    private void swapLyrics(final Lyrics lyrics, final String seed) {
        if (lyricsView == null || isFinishing() || isDestroyed()) return;
        final Lyrics target = lyrics == null ? Lyrics.EMPTY : lyrics;
        pendingLyrics = target;
        pendingSeed = seed == null ? "sonnet" : seed;
        if (sameLyricsContent(target, displayedLyrics)) return;
        // A fade-out is already running (started when the track changed): keep
        // it at full length, the newest lyrics are applied when it finishes.
        if (fadingOut) return;
        if (displayedLyrics.isEmpty() || contentAlpha <= 0.02f) {
            // Nothing on screen to fade out (first load / already blank).
            applyPendingLyrics();
            fadeContent(1f, 420L, null);
            return;
        }
        fadeOutForSwap();
    }

    /**
     * Starts the fade-out half of a lyric swap. Called as soon as a track
     * change is seen so the fade runs while the new lyrics are being read.
     */
    private void fadeOutForSwap() {
        if (lyricsView == null || isFinishing() || isDestroyed() || fadingOut) return;
        fadingOut = true;
        fadeContent(0f, 700L, () -> {
            fadingOut = false;
            // Only fade back in when the next lyrics actually arrived while we
            // were fading out; otherwise stay blank and wait for them.
            if (pendingLyrics != null && !sameLyricsContent(pendingLyrics, displayedLyrics)) {
                applyPendingLyrics();
                fadeContent(1f, 420L, null);
            }
        });
    }

    private void applyPendingLyrics() {
        if (lyricsView == null || isFinishing() || isDestroyed()) return;
        displayedLyrics = pendingLyrics == null ? Lyrics.EMPTY : pendingLyrics;
        lyricsView.setLyrics(displayedLyrics,
                pendingSeed == null ? "sonnet" : pendingSeed);
    }

    /** True when a new lyric set would look identical to what is on screen. */
    private static boolean sameLyricsContent(Lyrics a, Lyrics b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.lines.size() != b.lines.size()) return false;
        if (a.lines.isEmpty()) return true;
        boolean timed = a.synced && b.synced;
        for (int i = 0; i < a.lines.size(); i++) {
            com.airmusic.player.lyrics.LyricLine x = a.lines.get(i);
            com.airmusic.player.lyrics.LyricLine y = b.lines.get(i);
            if (!x.text.equals(y.text)) return false;
            // Only the start times are compared: the fitted line ends shift a
            // little when the reported track length changes and must not count
            // as "different lyrics".
            if (timed && x.startMs != y.startMs) return false;
        }
        return true;
    }

    private void fadeContent(final float target, long durationMs, final Runnable onEnd) {
        final int token = ++contentFadeToken;
        if (contentAnimator != null) {
            contentAnimator.cancel();
            contentAnimator = null;
        }
        final long fadeStartMs = android.os.SystemClock.uptimeMillis();
        android.util.Log.i("LyricFade", "fade start " + contentAlpha + " -> " + target
                + " in " + durationMs + "ms");
        ValueAnimator animator = ValueAnimator.ofFloat(contentAlpha, target);
        animator.setDuration(durationMs);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            if (token != contentFadeToken) return;
            contentAlpha = (Float) animation.getAnimatedValue();
            if (lyricsView != null) lyricsView.setContentAlpha(contentAlpha);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (token != contentFadeToken) return;
                android.util.Log.i("LyricFade", "fade end " + target + " took "
                        + (android.os.SystemClock.uptimeMillis() - fadeStartMs) + "ms");
                contentAlpha = target;
                if (lyricsView != null) lyricsView.setContentAlpha(target);
                if (onEnd != null) onEnd.run();
            }
        });
        contentAnimator = animator;
        animator.start();
    }

    private static boolean sameTrack(Track a, Track b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.uri != null && b.uri != null) return a.uri.equals(b.uri);
        return a.filePath != null && a.filePath.equals(b.filePath);
    }

    /** Picks a vivid but not blinding colour from the cover for the accents. */
    /**
     * The bitmap used as the blurred lyric backdrop. Tracks without embedded
     * cover art fall back to the app's placeholder cover, which is also what
     * every other screen uses for its blurred background.
     */
    private Bitmap backdropArt(Bitmap art) {
        if (art != null && !art.isRecycled() && art.getWidth() > 0 && art.getHeight() > 0) {
            return art;
        }
        if (placeholderArt == null || placeholderArt.isRecycled()) {
            try {
                placeholderArt = BitmapFactory.decodeResource(
                        getResources(), R.drawable.ic_airplay);
            } catch (Throwable ignored) {
                placeholderArt = null;
            }
        }
        return placeholderArt;
    }

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
