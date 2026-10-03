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
import com.airmusic.player.lyrics.AirplayLyricLocator;
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

    /** True while the lyric screen is in front; the desktop console uses it. */
    public static volatile boolean visible;

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
    /** AirPlay 下最后一次识别出的位置（状态回调里的 positionMs 一直是 0，不能用）。 */
    private long airplayPositionMs;
    private String airplayPositionKey = "";
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    /** AirPlay 下每秒把识别出的播放位置推给歌词视图（识别在后台每 5 秒更新一次）。 */
    private final Runnable airplayTick = new Runnable() {
        @Override
        public void run() {
            long located = AirplayLyricLocator.positionMs();
            if (located >= 0) {
                if (airplayLyricsHidden) {
                    if (!displayedLyrics.isEmpty()) {
                        // 定位成功：把歌词重新显示出来
                        airplayLyricsHidden = false;
                        lyricsView.setLyrics(displayedLyrics);
                    } else if (lastState != null) {
                        // 位置未知时压着没加载：现在定位到了，重新触发一次加载
                        render(lastState);
                    }
                }
                lyricsView.setPlaybackState(located, true);
            } else if (!airplayLyricsHidden && !displayedLyrics.isEmpty()) {
                // 还没定位到（拖动 / 换歌 / 参考音频准备中）：先不显示歌词
                airplayLyricsHidden = true;
                lyricsView.setLyrics(com.airmusic.player.lyrics.Lyrics.EMPTY);
            }
            if (located < 0) {
                // 位置未知时：既要告诉视图「在播放」，也要让它的时间轴往前走
                // （镜头晃动是按播放位置推进的），否则「暂未找到歌词」占位画面会定格。
                boolean playing = lastState == null || lastState.playing;
                if (idleClockStart == 0L) {
                    idleClockStart = android.os.SystemClock.elapsedRealtime();
                }
                // 从 0 开始持续推进的虚拟时间（不要用系统开机时刻，数值太大会影响舞台计算）
                lyricsView.setPlaybackState(
                        android.os.SystemClock.elapsedRealtime() - idleClockStart, playing);
            }
            uiHandler.postDelayed(this, 1000L);
        }
    };
    /** AirPlay 下「位置未知时先不显示歌词」的状态。 */
    private boolean airplayLyricsHidden;
    /** 最近一次播放状态：位置未知时不加载歌词，定位成功后再用它重新触发一次。 */
    private PlayerUiState lastState;
    /** 隐藏歌词时用的虚拟时间起点。 */
    private long idleClockStart;
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
        // 左上角的返回键 / 歌名也算「顶栏」：从舞台的缩放层里摘出来贴屏幕左上角，
        // 否则在大屏上它会跟着舞台一起往里缩，看着不贴边。
        View lyricsBox = findViewById(R.id.lyrics_box);
        if (lyricsBox instanceof com.airmusic.player.view.BoxAspectFrameLayout) {
            ((com.airmusic.player.view.BoxAspectFrameLayout) lyricsBox).addBar(headerView, true);
        }
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
        visible = true;
        showHeader();
    }

    @Override
    protected void onPause() {
        visible = false;
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
        uiHandler.removeCallbacks(airplayTick);
        if (contentAnimator != null) {
            contentFadeToken++;
            contentAnimator.cancel();
            contentAnimator = null;
        }
        super.onDestroy();
    }

    private void render(PlayerUiState state) {
        if (state == null || lyricsView == null) return;
        lastState = state;

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
        // AirPlay 没有进度信息：用参考音频互相关识别出的位置来驱动歌词
        long playbackPosition = state.positionMs;
        if (state.source == PlayerUiState.Source.AIRPLAY) {
            String key = (state.title == null ? "" : state.title) + "|"
                    + (state.artist == null ? "" : state.artist);
            // 只有「真的换歌」才把位置清零；退出再进来时 key 从空变成当前歌，
            // 这时不能当成换歌，否则会先从头渲染一遍（看起来就是异常显示）。
            if (!airplayPositionKey.isEmpty() && !key.equals(airplayPositionKey)) {
                airplayPositionKey = key;          // 换歌就从头开始等识别
                airplayPositionMs = 0L;
                airplayLyricsHidden = true;
                // 上一首的歌词必须一起作废：否则「定位成功后再显示」那一步看到
                // displayedLyrics 非空，会把旧歌词重新显示出来，页面永远不刷新
                // （表现就是切歌后要退出重进才正确）。
                displayedLyrics = com.airmusic.player.lyrics.Lyrics.EMPTY;
                loadedTrack = null;
                loadedDurationMs = -1;
                loadGeneration++;
                lyricsView.setLyrics(com.airmusic.player.lyrics.Lyrics.EMPTY);
            } else if (airplayPositionKey.isEmpty()) {
                airplayPositionKey = key;
            }
            AirplayLyricLocator.start(this, state.title, state.artist, state.durationMs);
            long located = AirplayLyricLocator.positionMs();
            if (located >= 0) {
                airplayPositionMs = located;
            } else {
                // 位置还没识别出来：先不显示歌词（等定时器定位成功后再显示）
                airplayLyricsHidden = true;
                lyricsView.setLyrics(com.airmusic.player.lyrics.Lyrics.EMPTY);
                // 关键：此时不要继续走歌词加载流程——加载完成会把歌词从开头放出来
                // （重进歌词页"从头开始"、在歌词页切歌"新歌不显示歌词"都是它造成的），
                // 等定位成功后在定时器里重新触发一次 render 即可。
                return;
            }
            // 关键：不要回落到 state.positionMs（AirPlay 一直是 0），否则每秒都被拉回开头
            playbackPosition = airplayPositionMs;
            uiHandler.removeCallbacks(airplayTick);
            uiHandler.postDelayed(airplayTick, 1000L);
        } else {
            AirplayLyricLocator.stop(this);
            uiHandler.removeCallbacks(airplayTick);
        }
        lyricsView.setPlaybackState(playbackPosition, state.playing);

        if (state.playing) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        PlaybackService service = PlaybackService.getInstance();
        Track track = service == null ? null : service.getCurrentTrack();
        if (track == null && state.source == PlayerUiState.Source.AIRPLAY
                && state.title != null && !state.title.trim().isEmpty()) {
            // AirPlay 没有本地文件：用发送端给的元数据合成一个曲目，
            // 这样就能走正常流程按歌名/歌手去在线找歌词（以前这里直接显示空提示）。
            track = new Track(android.net.Uri.parse("airplay://"
                    + state.title.trim() + "|" + (state.artist == null ? "" : state.artist.trim())),
                    state.title.trim(), state.artist, state.album,
                    state.durationMs > 0 ? state.durationMs : -1L, null, null, null);
        }
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
        final Track currentTrack = track;
        loadedTrack = currentTrack;
        loadedDurationMs = state.durationMs;
        final int generation = ++loadGeneration;
        final long duration = state.durationMs;
        // Fade the previous track's lyrics out right away, so the fade lines up
        // with the track change instead of waiting for the file to be parsed.
        if (!displayedLyrics.isEmpty()) fadeOutForSwap();
        LyricRepository.loadAsync(this, currentTrack, duration, lyrics -> {
            if (generation != loadGeneration || isFinishing() || isDestroyed()) return;
            lyricsView.setHints(hintsFor(currentTrack));
            swapLyrics(lyrics, seedFor(currentTrack));
        });
        // Analyse the vocal onsets once in the background; the lyric program is
        // rebuilt with real onset times as soon as the result is ready.
        OnsetAnalyzer.analyzeAsync(this, currentTrack, onsets -> {
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
