package com.airmusic.player.sonnet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.Nullable;

import com.airmusic.player.R;
import com.airmusic.player.lyrics.Lyrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.io.File;

/**
 * Native renderer for the Sonnet-style lyric PV.
 *
 * <p>Unlike a conventional lyric view, every shot is a scene on a shared 2D
 * plane at its own camera position and zoom. The camera then tracks the glyph
 * being sung with the original weighted smoothing, so lines continuously slide
 * up / down / left / right as if a camera were flying across the plane. Shots
 * are 4 lines / 6 seconds long and switch with the original three short
 * transitions.
 */
public class SonnetStageView extends View {

    private static final float VW = 1280f;
    private static final float VH = 720f;

    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SUBTLE_COLOR = 0xFFA8C3E0;
    private static final long INTERLUDE_MIN_MS = 6_000L;
    private static final float DOT_RADIUS = 9f;
    private static final float DOT_SPACING = 54f;

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint measurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vignettePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint effectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backdropPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();

    private SonnetDirector director = SonnetDirector.compile(Lyrics.EMPTY, "sonnet");
    private Lyrics lyrics = Lyrics.EMPTY;
    private String seed = "sonnet";
    private List<String> hints = new ArrayList<>();
    private List<Long> onsets = null;
    private int accentColor = 0xFF4FC3F7;
    private boolean lowPower;
    private boolean canvasPostEffects = true;
    private boolean glMode;
    private int frameIntervalMs = 16;
    private Scene noLyricsScene;
    private long noLyricsStartMs = SystemClock.uptimeMillis();

    private final Map<Integer, Scene> sceneCache = new HashMap<>();

    private long statePositionMs;
    private long stateUptimeMs = SystemClock.uptimeMillis();
    private boolean playing;
    /** Audio keeps playing through the 2.5 s fade-out; keep following it. */
    private long pauseGraceUntilMs;
    private boolean pauseAnchored = true;

    private int activeShot = -1;
    private int previousShot = -1;
    private long shotChangedAtMs = SystemClock.uptimeMillis();
    private SonnetDirector.TransitionKind transition =
            SonnetDirector.TransitionKind.CAMERA_PULL;
    private SonnetDirector.TransitionKind previousTransition =
            SonnetDirector.TransitionKind.CAMERA_PULL;
    private long transitionDurationMs = 200L;

    private long lastFrameMs;
    private long lastRenderMs;
    private long displayPositionMs;
    private boolean displayPositionInitialized;
    private int frameCounter;
    private Bitmap noiseBitmap;
    private Bitmap halftoneBitmap;
    private Bitmap backgroundArtSource;
    private Bitmap backgroundArtBlur;
    /** Smoothed scrim alpha of the backdrop (drives the per-line brightening). */
    private float backgroundMaskAlpha;
    private boolean backgroundMaskInitialized;
    private long backgroundMaskAtMs;
    /** 1 = lyrics fully visible; fades to 0 while a track swaps. */
    private float contentAlpha = 1f;
    /** Previous blurred cover, drawn under the new one during the fade. */
    private Bitmap backgroundArtPrev;
    private long backgroundArtFadeStartMs;
    private Typeface serifTypeface;
    private Typeface serifBoldTypeface;

    /**
     * Visible area in virtual units. The scene is authored against VW x VH,
     * but the renderer can stretch the vertical field of view so the lyrics
     * use the whole screen on 4:3 tablets or ultra-wide displays instead of
     * being letterboxed.
     */
    private float viewW = VW;
    private float viewH = VH;
    /** Pixel size of the scene bitmap (matches the screen aspect). */
    private int scenePixelW = (int) VW;
    private int scenePixelH = (int) VH;

    private final Choreographer choreographer = Choreographer.getInstance();
    private boolean frameScheduled;
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            frameScheduled = false;
            long now = SystemClock.uptimeMillis();
            if (now - lastFrameMs < frameIntervalMs) {
                scheduleFrame();
                return;
            }
            lastFrameMs = now;
            frameCounter++;
            invalidate();
            if (shouldAnimate()) scheduleFrame();
        }
    };

    public SonnetStageView(Context context) {
        this(context, null);
    }

    public SonnetStageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        serifTypeface = resolveSerifTypeface(getContext());
        serifBoldTypeface = Typeface.create(serifTypeface, Typeface.BOLD);
        textPaint.setTypeface(serifBoldTypeface);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(TEXT_COLOR);
        measurePaint.setTypeface(serifBoldTypeface);

        accentPaint.setStyle(Paint.Style.FILL);
        accentPaint.setColor(accentColor);
        shapePaint.setStyle(Paint.Style.STROKE);
        shapePaint.setStrokeWidth(1.4f);
        shapePaint.setColor(accentColor);
        effectPaint.setStyle(Paint.Style.FILL);

        scrimPaint.setShader(new LinearGradient(0f, VH * 0.3f, 0f, VH,
                0x00000000, 0x55000000, Shader.TileMode.CLAMP));
        vignettePaint.setShader(new RadialGradient(VW * 0.5f, VH * 0.5f, 840f,
                new int[]{0x00000000, 0x00000000, 0x6E000000},
                new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
    }

    /**
     * Sets the visible virtual area and the scene bitmap size. Called by the
     * GL layer whenever the screen shape changes: the horizontal field of view
     * stays 1280 units wide, the vertical one grows on taller screens so the
     * composition fills the display instead of leaving bars.
     */
    public void setViewport(float width, float height, int pixelWidth, int pixelHeight) {
        float w = width > 0f ? width : VW;
        float h = height > 0f ? height : VH;
        int pw = pixelWidth > 0 ? pixelWidth : (int) w;
        int ph = pixelHeight > 0 ? pixelHeight : (int) h;
        if (Math.abs(w - viewW) < 0.5f && Math.abs(h - viewH) < 0.5f
                && pw == scenePixelW && ph == scenePixelH) {
            return;
        }
        viewW = w;
        viewH = h;
        scenePixelW = pw;
        scenePixelH = ph;
        scrimPaint.setShader(new LinearGradient(0f, viewH * 0.3f, 0f, viewH,
                0x00000000, 0x55000000, Shader.TileMode.CLAMP));
        vignettePaint.setShader(new RadialGradient(viewW * 0.5f, viewH * 0.5f,
                Math.max(840f, viewH * 1.17f),
                new int[]{0x00000000, 0x00000000, 0x6E000000},
                new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        // The backdrop is built for the scene bitmap size, so rebuild it.
        Bitmap source = backgroundArtSource;
        backgroundArtSource = null;
        setBackgroundArt(source);
    }

    /**
     * Folia's serif stack resolves to Noto Serif CJK / Source Han Serif. Most
     * Android TV boxes ship NotoSerifCJK-Regular.ttc in /system/fonts, so use
     * that file directly and fall back to the generic serif family.
     */
    private static Typeface resolveSerifTypeface(android.content.Context context) {
        // Bundled subset: guarantees the Folia serif look on devices that do
        // not ship Noto Serif CJK. The system font stays as a fallback.
        try {
            Typeface bundled = Typeface.createFromAsset(
                    context.getAssets(), "NotoSerifSC.otf");
            if (bundled != null) return bundled;
        } catch (Throwable ignored) {
        }
        try {
            File file = new File("/system/fonts/NotoSerifCJK-Regular.ttc");
            if (file.isFile()) {
                Typeface typeface = Typeface.createFromFile(file);
                if (typeface != null) return typeface;
            }
        } catch (Throwable ignored) {
        }
        return Typeface.create(Typeface.SERIF, Typeface.NORMAL);
    }

    // ------------------------------------------------------------------
    // Public inputs
    // ------------------------------------------------------------------

    public void setLyrics(Lyrics lyrics) {
        setLyrics(lyrics, "sonnet");
    }

    public void setLyrics(Lyrics lyrics, String seedText) {
        seed = seedText == null || seedText.isEmpty() ? "sonnet" : seedText;
        this.lyrics = lyrics == null ? Lyrics.EMPTY : lyrics;
        director = SonnetDirector.compile(lyrics, seed, hints, onsets);
        sceneCache.clear();
        noLyricsScene = null;
        noLyricsStartMs = SystemClock.uptimeMillis();
        activeShot = -1;
        previousShot = -1;
        // The backdrop dimming is deliberately *not* reset here: its smoothing
        // carries the value over from the previous track instead of snapping.
        invalidate();
        scheduleFrame();
    }

    /**
     * Song / artist names from the track, used as protected words by the local
     * segmenter so names like 黄龄 or HOYO-MiX are not split into single chars.
     */
    public void setHints(List<String> values) {
        hints = values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    /**
     * Vocal-onset times for the current track. When they arrive after the
     * lyrics, the shot program is rebuilt so word starts snap to the singing.
     */
    public void setOnsets(List<Long> values) {
        onsets = values == null || values.isEmpty() ? null : new ArrayList<>(values);
        if (lyrics == null || lyrics.isEmpty()) return;
        director = SonnetDirector.compile(lyrics, seed, hints, onsets);
        sceneCache.clear();
        noLyricsScene = null;
        activeShot = -1;
        previousShot = -1;
        invalidate();
        scheduleFrame();
    }

    public void setAccentColor(int color) {
        if (color == 0) color = 0xFF4FC3F7;
        accentColor = color;
        accentPaint.setColor(color);
        shapePaint.setColor(color);
        invalidate();
    }

    public void setLowPower(boolean value) {
        lowPower = value;
        frameIntervalMs = value ? 33 : 16;
        invalidate();
    }

    /** The GL layer owns the print stack, so skip the Canvas overlays. */
    public void setCanvasPostEffectsEnabled(boolean value) {
        canvasPostEffects = value;
    }

    /** GL mode trims per-frame Canvas decorations because the shader adds more. */
    public void setGlMode(boolean value) {
        glMode = value;
    }

    /** Blurred album-art backdrop, drawn inside the GL scene. */
    public void setBackgroundArt(Bitmap art) {
        if (art == backgroundArtSource) return;
        backgroundArtSource = art;
        // The new backdrop is fully built *before* it replaces the old one:
        // clearing first left one frame without any backdrop, which showed up
        // as a black flash on every track change.
        Bitmap built = null;
        if (art != null && !art.isRecycled() && art.getWidth() > 0 && art.getHeight() > 0) {
            try {
                // Exactly the pipeline used by BlurBackground on the playback
                // screen: the source is crushed to screenWidth / 384 pixels
                // wide and then stretched back up, which is what produces the
                // very soft look. Deriving the tiny size from the physical
                // screen width (instead of the virtual 1280 frame) keeps the
                // perceived blur identical to the playback screen on every
                // device, TV box or tablet alike.
                float screenWidth = getResources().getDisplayMetrics().widthPixels;
                if (screenWidth <= 0f) screenWidth = 1920f;
                int tinyWidth = Math.max(2, Math.round(screenWidth / 384f));
                float aspect = art.getWidth() / (float) Math.max(1, art.getHeight());
                int tinyHeight = Math.max(2, Math.round(tinyWidth / Math.max(0.1f, aspect)));
                Bitmap tiny = Bitmap.createScaledBitmap(art, tinyWidth, tinyHeight, true);
                int midWidth = Math.max(6, tinyWidth * 4);
                int midHeight = Math.max(6, tinyHeight * 4);
                Bitmap mid = Bitmap.createScaledBitmap(tiny, midWidth, midHeight, true);
                if (tiny != mid) tiny.recycle();
                // Built in *virtual* units: the backdrop is drawn inside the
                // scaled scene canvas, so sizing it in pixels left the right /
                // bottom tenth of the frame uncovered.
                int targetW = Math.max(2, Math.round(viewW));
                int targetH = Math.max(2, Math.round(viewH));
                float cover = Math.max(targetW / (float) midWidth,
                        targetH / (float) midHeight);
                float drawWidth = midWidth * cover;
                float drawHeight = midHeight * cover;
                float left = (targetW - drawWidth) * 0.5f;
                float top = (targetH - drawHeight) * 0.5f;
                Bitmap result = Bitmap.createBitmap(targetW, targetH,
                        Bitmap.Config.ARGB_8888);
                Canvas resultCanvas = new Canvas(result);
                Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
                rect.set(left, top, left + drawWidth, top + drawHeight);
                resultCanvas.drawBitmap(mid, null, rect, filter);
                mid.recycle();
                built = result;
            } catch (Throwable ignored) {
                built = null;
            }
        }
        Bitmap previous = backgroundArtBlur;
        backgroundArtBlur = built;
        // Keep the previous backdrop around and fade the new one in, so a
        // track change does not snap between two covers.
        if (previous != null && previous != backgroundArtBlur && !previous.isRecycled()) {
            recycleBackdrop();
            backgroundArtPrev = previous;
            backgroundArtFadeStartMs = SystemClock.uptimeMillis();
        } else if (previous != null && previous != backgroundArtBlur) {
            previous.recycle();
        }
        invalidate();
        scheduleFrame();
    }

    private void recycleBackdrop() {
        if (backgroundArtPrev != null) {
            if (!backgroundArtPrev.isRecycled()) backgroundArtPrev.recycle();
            backgroundArtPrev = null;
        }
    }

    /**
     * Fades the lyric content (glyphs, subtitle, interlude marks) without
     * touching the backdrop. Used to crossfade into the next track's lyrics.
     */
    public void setContentAlpha(float value) {
        float clamped = SonnetMotion.clamp01(value);
        if (Math.abs(clamped - contentAlpha) < 0.004f) return;
        contentAlpha = clamped;
        invalidate();
        scheduleFrame();
    }

    public void setPlaybackState(long positionMs, boolean isPlaying) {
        long now = SystemClock.uptimeMillis();
        if (playing && !isPlaying) {
            // The service publishes "paused" immediately, then fades the audio
            // out for ~2.5 s before the decoder actually stops.
            pauseGraceUntilMs = now + 2_800L;
            pauseAnchored = false;
            statePositionMs = Math.max(0, positionMs);
            stateUptimeMs = now;
        } else if (isPlaying) {
            pauseGraceUntilMs = 0L;
            pauseAnchored = false;
            long reported = Math.max(0L, positionMs);
            if (displayPositionInitialized && reported < displayPositionMs - 3_500L) {
                // A large backwards jump is a real seek / track change.
                statePositionMs = reported;
            } else if (displayPositionInitialized && reported < displayPositionMs) {
                // The service publishes the pre-pause position when resuming;
                // the fade already advanced the rendered clock, so keep it.
                statePositionMs = displayPositionMs;
            } else {
                statePositionMs = reported;
            }
            stateUptimeMs = now;
        } else {
            // Already paused: never pull the rendered position backwards; the
            // decoder may report the fade-out start for a while.
            if (positionMs > statePositionMs) {
                statePositionMs = positionMs;
                stateUptimeMs = now;
            }
        }
        playing = isPlaying;
        invalidate();
        if (isPlaying) scheduleFrame();
    }

    public void setPaused() {
        statePositionMs = currentPositionMs();
        stateUptimeMs = SystemClock.uptimeMillis();
        playing = false;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        scheduleFrame();
    }

    @Override
    protected void onDetachedFromWindow() {
        frameScheduled = false;
        choreographer.removeFrameCallback(frameCallback);
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------
    // Frame loop
    // ------------------------------------------------------------------

    private void scheduleFrame() {
        if (frameScheduled || !isAttachedToWindow()) return;
        frameScheduled = true;
        choreographer.postFrameCallback(frameCallback);
    }

    private boolean shouldAnimate() {
        if (playing || SystemClock.uptimeMillis() < pauseGraceUntilMs) return true;
        // Keep drawing while the backdrop is still crossfading.
        if (backgroundArtPrev != null) return true;
        return SystemClock.uptimeMillis() - shotChangedAtMs < transitionDurationMs + 150L;
    }

    private long currentPositionMs() {
        long now = SystemClock.uptimeMillis();
        if (!playing && now >= pauseGraceUntilMs) return statePositionMs;
        return statePositionMs + Math.max(0, SystemClock.uptimeMillis() - stateUptimeMs);
    }

    // ------------------------------------------------------------------
    // Draw
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        float scale = Math.min(width / VW, height / VH);
        float offsetX = (width - VW * scale) * 0.5f;
        float offsetY = (height - VH * scale) * 0.5f;

        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);

        long now = SystemClock.uptimeMillis();
        anchorPausedPositionIfNeeded(now);
        long rawPosition = currentPositionMs();
        long position = smoothPosition(rawPosition, now);
        drawSceneFrame(canvas, position, now);
        canvas.restore();
        if (shouldAnimate()) scheduleFrame();
    }

    /** Draws one full 1280x720 frame; used by the view and the GL post layer. */
    public void drawSceneFrame(Canvas canvas, long positionMs, long now) {
        canvas.drawColor(0xFF0A1428);
        if (backgroundArtBlur != null) {
            float fade = 1f;
            if (backgroundArtPrev != null) {
                long elapsed = now - backgroundArtFadeStartMs;
                fade = SonnetMotion.easeInOut(
                        SonnetMotion.clamp01(elapsed / (float) BACKDROP_FADE_MS));
                if (fade >= 1f) {
                    recycleBackdrop();
                }
            }
            if (backgroundArtPrev != null) {
                backdropPaint.setAlpha((int) ((1f - fade) * 255f));
                canvas.drawBitmap(backgroundArtPrev, 0f, 0f, backdropPaint);
            }
            backdropPaint.setAlpha((int) (fade * 255f));
            canvas.drawBitmap(backgroundArtBlur, 0f, 0f, backdropPaint);
            float mask = backgroundMaskAlpha(positionMs, now);
            canvas.drawColor(android.graphics.Color.argb(
                    (int) (mask * 255f + 0.5f), 0, 0, 0));
        }
        canvas.drawRect(0f, 0f, viewW, viewH, scrimPaint);
        drawStage(canvas, positionMs, now);
        drawTranslationSubtitle(canvas, positionMs);
        drawPostEffects(canvas);
    }

    /** Scrim alpha at the start of a lyric line. */
    private static final float BACKDROP_MASK_LINE_START = 0.46f;
    /** Scrim alpha once the line has been sung out. */
    private static final float BACKDROP_MASK_LINE_END = 0.10f;
    /** Scrim alpha while no lyric line is on screen (intro / instrumental). */
    private static final float BACKDROP_MASK_IDLE = 0.30f;
    /** Time constant of the smoothing that hides line-boundary steps. */
    private static final float BACKDROP_MASK_SMOOTH_MS = 320f;
    /** Crossfade length when the blurred cover changes. */
    private static final long BACKDROP_FADE_MS = 650L;

    /**
     * The lyric backdrop keeps brightening while a line is being sung: it
     * starts out dimmed and ends up nearly clear, then the next line starts
     * from the dim value again. The value is smoothed so the reset at a line
     * boundary reads as a slow darkening instead of a flicker.
     */
    private float backgroundMaskAlpha(long positionMs, long now) {
        float target = BACKDROP_MASK_IDLE;
        if (lyrics != null && !lyrics.isEmpty()) {
            int index = lyrics.indexAt(positionMs);
            if (index >= 0) {
                com.airmusic.player.lyrics.LyricLine line = lyrics.lines.get(index);
                long duration = Math.max(1L, line.endMs - line.startMs);
                float progress = SonnetMotion.clamp01(
                        (positionMs - line.startMs) / (float) duration);
                target = BACKDROP_MASK_LINE_START
                        + (BACKDROP_MASK_LINE_END - BACKDROP_MASK_LINE_START)
                        * SonnetMotion.easeInOut(progress);
            }
        }
        long deltaMs = backgroundMaskAtMs == 0L
                ? 16L : Math.min(120L, Math.max(1L, now - backgroundMaskAtMs));
        backgroundMaskAtMs = now;
        if (!backgroundMaskInitialized) {
            backgroundMaskInitialized = true;
            backgroundMaskAlpha = target;
        } else {
            float k = Math.min(1f, deltaMs / BACKDROP_MASK_SMOOTH_MS);
            backgroundMaskAlpha += (target - backgroundMaskAlpha) * k;
        }
        return SonnetMotion.clamp01(backgroundMaskAlpha);
    }

    /** Smoothed playback clock shared with the GL renderer. */
    public long displayPositionMs() {
        long now = SystemClock.uptimeMillis();
        anchorPausedPositionIfNeeded(now);
        return smoothPosition(currentPositionMs(), now);
    }

    public boolean wantsAnimationFrames() {
        return shouldAnimate();
    }

    /** Real blur radius (in virtual pixels) for the current transition. */
    public float currentBlurStrength() {
        if (transition != SonnetDirector.TransitionKind.FAST_BLUR) return 0f;
        if (previousShot < 0 || previousShot == activeShot) return 0f;
        long now = SystemClock.uptimeMillis();
        float progress = SonnetMotion.clamp01((now - shotChangedAtMs) / (float) transitionDurationMs);
        return progress >= 1f ? 0f : outAmount(progress) * 14f;
    }

    /**
     * The service reports the audio clock every ~500 ms and its correction can
     * be a few dozen milliseconds. Applying that directly to the camera makes
     * the whole plane twitch, so ease the displayed clock towards the report.
     * Large differences are real seeks and snap immediately.
     */
    private long smoothPosition(long target, long now) {
        // Clamp the step: after a long pause the wall-clock gap can be tens of
        // seconds, which would make the easing coefficient 1 and teleport the
        // lyrics on the first resumed frame.
        long deltaTime = lastRenderMs == 0
                ? 16L : Math.min(50L, Math.max(1L, now - lastRenderMs));
        lastRenderMs = now;
        if (!displayPositionInitialized || Math.abs(target - displayPositionMs) > 3_000L) {
            displayPositionMs = target;
            displayPositionInitialized = true;
            return displayPositionMs;
        }
        float k = 1f - (float) Math.exp(-deltaTime / 120.0);
        displayPositionMs += Math.round((target - displayPositionMs) * k);
        if (Math.abs(target - displayPositionMs) <= 4L) {
            displayPositionMs = target;
        }
        return displayPositionMs;
    }

    /** Freeze exactly where the fade-out actually ended, before reading the target. */
    private void anchorPausedPositionIfNeeded(long now) {
        if (!playing && !pauseAnchored && pauseGraceUntilMs > 0
                && now >= pauseGraceUntilMs && displayPositionInitialized) {
            statePositionMs = displayPositionMs;
            stateUptimeMs = now;
            pauseAnchored = true;
        }
    }

    private void drawStage(Canvas canvas, long positionMs, long now) {
        if (director.isEmpty()) {
            drawNoLyricsScene(canvas, now);
            drawFrameMarks(canvas);
            return;
        }
        int shotIndex = director.shotIndexAt(positionMs);
        boolean allowSwitch = activeShot < 0
                || shotIndex > activeShot
                || (shotIndex < activeShot
                && positionMs < director.shots.get(Math.max(0, activeShot)).startMs - 400L);
        if (shotIndex != activeShot && allowSwitch) {
            previousShot = activeShot;
            activeShot = shotIndex;
            shotChangedAtMs = now;
            transition = previousShot < 0 ? SonnetDirector.TransitionKind.CAMERA_PULL
                    : resolveBoundaryTransition(previousShot);
            if (transition == previousTransition && previousShot >= 0) {
                transition = nextTransition(transition);
            }
            previousTransition = transition;
            long duration = 200L;
            if (previousShot >= 0 && activeShot >= 0) {
                long delta = director.shots.get(activeShot).startMs
                        - director.shots.get(previousShot).startMs;
                duration = (long) (Math.min(0.34f, Math.max(0.22f, delta / 1000f * 0.22f)) * 1000f);
            }
            transitionDurationMs = duration;
        }

        Scene current = ensureScene(activeShot);
        if (current == null) {
            drawInstrumental(canvas, now);
            return;
        }
        float progress = SonnetMotion.clamp01((now - shotChangedAtMs) / (float) transitionDurationMs);
        boolean transitioning = previousShot >= 0 && previousShot != activeShot && progress < 1f;
        if (transitioning) {
            Scene outgoing = ensureScene(previousShot);
            CameraFrame incomingCamera = resolveCamera(current, positionMs);
            CameraFrame outgoingCamera = outgoing == null
                    ? incomingCamera : resolveCamera(outgoing, positionMs);
            float eased = SonnetMotion.easeInOut(progress);
            CameraFrame blended = lerpCamera(outgoingCamera, incomingCamera, eased);
            if (outgoing != null) {
                CameraFrame outgoingBlended = lerpCamera(outgoingCamera, incomingCamera,
                        eased);
                drawTransitionOutgoing(canvas, outgoing, positionMs, progress, outgoingBlended);
            }
            drawScene(canvas, current, positionMs,
                    (1f - inAmount(progress) * 0.72f) * contentAlpha,
                    transition == SonnetDirector.TransitionKind.FAST_BLUR ? outAmount(progress) : 0f,
                    transition == SonnetDirector.TransitionKind.MONO_GLITCH ? outAmount(progress) : 0f,
                    progress, 0f, blended);
        } else {
            drawScene(canvas, current, positionMs, contentAlpha, 0f, 0f, 1f);
        }
        drawFrameMarks(canvas);
    }

    // ------------------------------------------------------------------
    // Instrumental interlude
    // ------------------------------------------------------------------

    private static final class Interlude {
        boolean active;
        float progress;
        boolean vertical;
    }

    private Interlude interludeAt(long positionMs) {
        Interlude info = new Interlude();
        if (lyrics == null || lyrics.lines.size() < 2) return info;
        int index = lyrics.indexAt(positionMs);
        if (index < 0 || index + 1 >= lyrics.lines.size()) return info;
        com.airmusic.player.lyrics.LyricLine current = lyrics.lines.get(index);
        com.airmusic.player.lyrics.LyricLine next = lyrics.lines.get(index + 1);
        long gap = next.startMs - current.endMs;
        if (gap < INTERLUDE_MIN_MS || positionMs <= current.endMs || positionMs >= next.startMs) {
            return info;
        }
        info.active = true;
        info.progress = SonnetMotion.clamp01(
                (positionMs - current.endMs) / (float) Math.max(1L, gap));
        int sceneIndex = director.shotIndexAt(positionMs);
        Scene scene = sceneCache.get(sceneIndex);
        info.vertical = scene != null && scene.verticalComposition;
        return info;
    }

    /** 1 at both ends of the gap, dimmed through the middle so the dots read. */
    private float interludeSceneAlpha(Interlude interlude) {
        float elapsed = interlude.progress;
        float remaining = 1f - interlude.progress;
        float fadeOut = SonnetMotion.clamp01(elapsed / 0.16f);
        float fadeIn = SonnetMotion.clamp01(remaining / 0.16f);
        float dim = Math.min(fadeOut, fadeIn);
        return 1f - 0.82f * dim;
    }

    /**
     * The break marker is lyric typography, not a widget: the same bold white
     * face as the PV, with the three dots appearing one after another and the
     * last one breathing near the end of the interlude.
     */
    private void drawInterludeDots(Canvas canvas, Interlude interlude, long now) {
        float progress = interlude.progress;
        float pulse = 0.5f + 0.5f * (float) Math.sin(now / 420.0);
        textPaint.setTypeface(serifBoldTypeface);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(TEXT_COLOR);
        float size = interlude.vertical ? 66f : 76f;
        textPaint.setTextSize(size);
        float step = interlude.vertical ? size * 1.25f : size * 0.62f;
        for (int i = 0; i < 3; i++) {
            float appear = SonnetMotion.clamp01((progress * 3f - i) / 0.45f);
            if (appear <= 0.01f) continue;
            float alpha = 235f * appear;
            if (i == 2 && progress > 0.66f) {
                alpha = (150f + 105f * pulse) * appear;
            }
            float x = viewW * 0.5f + (interlude.vertical ? 0f : (i - 1) * step);
            float y = interlude.vertical
                    ? viewH * 0.42f + i * step : viewH * 0.55f;
            textPaint.setAlpha((int) alpha);
            float scale = 0.6f + 0.4f * appear;
            canvas.save();
            canvas.scale(scale, scale, x, y);
            canvas.drawText("\u00B7", x, y, textPaint);
            canvas.restore();
        }
    }

    /**
     * Translation stays out of the PV plane, exactly like the original's
     * bottom subtitle overlay: original lyrics drive the typography, the
     * translation fades in below with its own soft backdrop.
     */
    private void drawTranslationSubtitle(Canvas canvas, long positionMs) {
        if (lyrics == null || lyrics.isEmpty()) return;
        int index = lyrics.indexAt(positionMs);
        if (index < 0 || index >= lyrics.lines.size()) return;
        com.airmusic.player.lyrics.LyricLine line = lyrics.lines.get(index);
        if (line.translation == null || line.translation.trim().isEmpty()) return;
        float appear = SonnetMotion.clamp01((positionMs - line.startMs) / 300f);
        float exit = SonnetMotion.clamp01((line.endMs - positionMs) / 300f);
        float alpha = Math.min(appear, exit) * 0.85f * contentAlpha;
        if (alpha <= 0.02f) return;

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(serifTypeface);
        textPaint.setColor(SUBTLE_COLOR);
        textPaint.setTextSize(40f);
        String text = line.translation.trim();
        float maxWidth = viewW * 0.78f;
        float width = textPaint.measureText(text);
        if (width > maxWidth && width > 0f) {
            textPaint.setTextSize(Math.max(28f, 40f * maxWidth / width));
            width = textPaint.measureText(text);
        }
        float centerX = viewW * 0.5f;
        float baselineY = viewH - 58f;
        // No box or outline: the original subtitle is plain text over the
        // scene, kept readable with a soft shadow instead of a panel.
        textPaint.setColor(TEXT_COLOR);
        textPaint.setShadowLayer(6f, 0f, 2f, 0xB0000000);
        textPaint.setAlpha((int) (alpha * 255));
        canvas.drawText(text, centerX, baselineY, textPaint);
        textPaint.clearShadowLayer();
    }

    private float inAmount(float progress) {
        return 1f - SonnetMotion.easeInOut(SonnetMotion.clamp01(progress));
    }

    private float outAmount(float progress) {
        return SonnetMotion.easeInOut(SonnetMotion.clamp01(progress));
    }

    private SonnetDirector.TransitionKind resolveBoundaryTransition(int boundaryIndex) {
        SonnetDirector.TransitionKind[] kinds = SonnetDirector.TransitionKind.values();
        int mixed = SonnetDirector.hashSeed(seed + ":" + (boundaryIndex + 1));
        return kinds[Math.floorMod(mixed, kinds.length)];
    }

    private SonnetDirector.TransitionKind nextTransition(SonnetDirector.TransitionKind current) {
        SonnetDirector.TransitionKind[] kinds = SonnetDirector.TransitionKind.values();
        return kinds[(current.ordinal() + 1) % kinds.length];
    }

    private void drawTransitionOutgoing(Canvas canvas, Scene scene, long positionMs,
                                        float progress, CameraFrame cameraOverride) {
        float eased = SonnetMotion.easeInOut(SonnetMotion.clamp01(progress));
        float amount = eased;
        if (transition == SonnetDirector.TransitionKind.FAST_BLUR) {
            int copies = lowPower ? 2 : 3;
            for (int i = 0; i < copies; i++) {
                float offset = (i - (copies - 1) * 0.5f) * (6f + 12f * amount);
                drawScene(canvas, scene, positionMs, (1f - amount) / copies,
                        amount * 14f, 0f, 0f, offset, cameraOverride);
            }
        } else if (transition == SonnetDirector.TransitionKind.MONO_GLITCH) {
            drawGlitchScene(canvas, scene, positionMs, 1f - (progress > 0.86f
                    ? (progress - 0.86f) / 0.14f : 0f), amount, cameraOverride);
        } else {
            drawScene(canvas, scene, positionMs, 1f - amount, 0f, 0f, 0f, 0f,
                    cameraOverride);
        }
    }

    private void drawGlitchScene(Canvas canvas, Scene scene, long positionMs,
                                 float alpha, float amount, CameraFrame cameraOverride) {
        int bands = lowPower ? 5 : 8;
        Random random = new Random((long) (frameCounter / 2) * 31 + activeShot * 7L);
        for (int band = 0; band < bands; band++) {
            float top = band * viewH / bands;
            float bottom = (band + 1) * viewH / bands;
            float offset = (random.nextFloat() - 0.5f) * 54f * (0.35f + amount);
            canvas.save();
            canvas.clipRect(0f, top, viewW, bottom);
            drawScene(canvas, scene, positionMs, alpha * 0.6f, 0f, 0f, 0f,
                    offset, cameraOverride);
            canvas.restore();
        }
        effectPaint.setColor(accentColor);
        effectPaint.setAlpha((int) Math.min(200, 46 + 120 * amount));
        for (int i = 0; i < 3; i++) {
            float y = random.nextFloat() * viewH;
            canvas.drawRect(0f, y, viewW, y + 1.6f, effectPaint);
        }
    }

    private void drawScene(Canvas canvas, Scene scene, long positionMs, float alpha,
                           float blur, float glitch, float progress) {
        drawScene(canvas, scene, positionMs, alpha, blur, glitch, progress, 0f);
    }

    private void drawScene(Canvas canvas, Scene scene, long positionMs, float alpha,
                           float blur, float glitch, float progress, float offsetX) {
        drawScene(canvas, scene, positionMs, alpha, blur, glitch, progress, offsetX, null);
    }

    private void drawScene(Canvas canvas, Scene scene, long positionMs, float alpha,
                           float blur, float glitch, float progress, float offsetX,
                           CameraFrame cameraOverride) {
        if (alpha <= 0.01f) return;
        CameraFrame camera = cameraOverride != null
                ? cameraOverride : resolveCamera(scene, positionMs);
        canvas.save();
        canvas.translate(camera.baseX + camera.motionX * viewW + offsetX,
                camera.baseY + camera.motionY * viewH);
        canvas.scale(camera.scale, camera.scale);
        canvas.rotate((float) Math.toDegrees(camera.rotation));
        canvas.translate(-camera.pivotX, -camera.pivotY);

        drawSceneMg(canvas, scene, positionMs, alpha);
        drawShotKindFx(canvas, scene, positionMs, alpha);
        drawSceneBackFx(canvas, scene, positionMs, alpha, camera);
        for (Glyph glyph : scene.glyphs) {
            drawGlyph(canvas, scene, glyph, positionMs, alpha, camera);
        }
        drawSceneFrontFx(canvas, scene, positionMs, alpha, camera);
        canvas.restore();
    }

    private CameraFrame lerpCamera(CameraFrame from, CameraFrame to, float t) {
        float p = SonnetMotion.clamp01(t);
        CameraFrame frame = new CameraFrame();
        frame.baseX = from.baseX + (to.baseX - from.baseX) * p;
        frame.baseY = from.baseY + (to.baseY - from.baseY) * p;
        frame.pivotX = from.pivotX + (to.pivotX - from.pivotX) * p;
        frame.pivotY = from.pivotY + (to.pivotY - from.pivotY) * p;
        frame.scale = from.scale + (to.scale - from.scale) * p;
        frame.rotation = from.rotation + (to.rotation - from.rotation) * p;
        frame.motionX = from.motionX + (to.motionX - from.motionX) * p;
        frame.motionY = from.motionY + (to.motionY - from.motionY) * p;
        return frame;
    }

    private void drawGlyph(Canvas canvas, Scene scene, Glyph glyph, long positionMs,
                           float alpha, CameraFrame camera) {
        textPaint.setTypeface(serifBoldTypeface);
        if (positionMs < glyph.startMs) return;
        float p = SonnetMotion.segmentProgress(glyph.startMs, glyph.settleMs, positionMs);
        float offset = 1f - p;
        float coreAlpha = 0.16f + 0.84f * p;
        if (glyph.role == SonnetDirector.Role.DECORATION) coreAlpha *= 0.07f;
        float scale = glyph.emphasized && scene.shot.kind == SonnetDirector.Kind.TYPE_IMPACT
                ? 0.52f + 0.48f * p
                : glyph.emphasized ? 0.78f + 0.22f * p : 0.68f + 0.32f * p;
        float depthScale = 1f + glyph.zDepth * 0.45f;
        float parallaxX = camera.motionX * VW * glyph.zDepth * 2.5f;
        float parallaxY = camera.motionY * VH * glyph.zDepth * 2.5f;
        float x = glyph.baseX + glyph.enterX * offset + parallaxX;
        float y = glyph.baseY + glyph.enterY * offset + parallaxY;
        float rotation = glyph.rotation + glyph.entryRotation * offset;

        canvas.save();
        canvas.translate(x, y);
        canvas.rotate((float) Math.toDegrees(rotation));
        canvas.scale(scale * depthScale, scale * depthScale);
        textPaint.setTextSize(glyph.fontSize);
        textPaint.setColor(TEXT_COLOR);
        textPaint.setAlpha((int) (alpha * coreAlpha * 255));
        float baseline = glyph.fontSize * 0.35f;

        if (!glMode && !lowPower && glyph.emphasized && p < 0.92f) {
            // Halo + chromatic split during the hit, like the original's glyph layers.
            textPaint.setAlpha((int) (alpha * coreAlpha * 70));
            canvas.save();
            canvas.scale(1.12f, 1.12f);
            canvas.drawText(glyph.text, 0f, baseline, textPaint);
            canvas.restore();
            float ca = glyph.fontSize * 0.06f * (1f - p);
            textPaint.setColor(0xFFFF3B5C);
            textPaint.setAlpha((int) (alpha * coreAlpha * 150));
            canvas.drawText(glyph.text, -ca, baseline, textPaint);
            textPaint.setColor(0xFF35F2E5);
            canvas.drawText(glyph.text, ca, baseline + ca * 0.5f, textPaint);
            textPaint.setColor(TEXT_COLOR);
            textPaint.setAlpha((int) (alpha * coreAlpha * 255));
        }
        if (!glMode && !lowPower && glyph.emphasized) {
            // Offset echo behind the hero glyph, a light print-style duplicate.
            textPaint.setColor(TEXT_COLOR);
            textPaint.setAlpha((int) (alpha * coreAlpha * 42));
            canvas.drawText(glyph.text, 8f, baseline + 6f, textPaint);
        }
        textPaint.setColor(TEXT_COLOR);
        textPaint.setAlpha((int) (alpha * coreAlpha * 255));
        canvas.drawText(glyph.text, 0f, baseline, textPaint);
        canvas.restore();
    }

    // ------------------------------------------------------------------
    // Scene building
    // ------------------------------------------------------------------

    private Scene ensureScene(int index) {
        if (index < 0 || index >= director.shots.size()) return null;
        Scene cached = sceneCache.get(index);
        if (cached != null) return cached;
        Scene scene = buildScene(director.shots.get(index));
        sceneCache.put(index, scene);
        pruneScenes(index);
        return scene;
    }

    private void pruneScenes(int activeIndex) {
        Iterator<Map.Entry<Integer, Scene>> iterator = sceneCache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Scene> entry = iterator.next();
            if (Math.abs(entry.getKey() - activeIndex) > 1) iterator.remove();
        }
    }

    private Scene buildScene(SonnetDirector.Shot shot) {
        List<SonnetDirector.Segment> wordSegments = new ArrayList<>();
        for (SonnetDirector.Segment segment : shot.segments) {
            if (segment.wordLike) wordSegments.add(segment);
        }
        Scene scene = new Scene();
        scene.shot = shot;
        scene.mgVariant = Math.floorMod(shot.paragraphIndex, 8);
        Random fxRandom = new Random(SonnetDirector.hashSeed(seed + ":fx:" + shot.index));
        int particleCount = glMode ? 22 : (lowPower ? 14 : 34);
        scene.particles = new float[particleCount][5];
        for (int i = 0; i < particleCount; i++) {
            scene.particles[i][0] = (fxRandom.nextFloat() * 2f - 1f) * VW * 0.62f;
            scene.particles[i][1] = (fxRandom.nextFloat() * 2f - 1f) * VH * 0.56f;
            scene.particles[i][2] = 1.4f + fxRandom.nextFloat() * 3.4f;
            scene.particles[i][3] = fxRandom.nextFloat() * 2f - 1f;
            scene.particles[i][4] = fxRandom.nextFloat() * (float) Math.PI * 2f;
        }
        int sparkleCount = glMode ? 7 : (lowPower ? 5 : 11);
        scene.sparkles = new float[sparkleCount][4];
        for (int i = 0; i < sparkleCount; i++) {
            scene.sparkles[i][0] = (fxRandom.nextFloat() * 2f - 1f) * VW * 0.52f;
            scene.sparkles[i][1] = (fxRandom.nextFloat() * 2f - 1f) * VH * 0.46f;
            scene.sparkles[i][2] = fxRandom.nextFloat() * (float) Math.PI * 2f;
            scene.sparkles[i][3] = 5f + fxRandom.nextFloat() * 7f;
        }
        if (wordSegments.isEmpty()) {
            scene.tracking = new ArrayList<>();
            return scene;
        }
        int wordCount = wordSegments.size();
        float baseFontSize = VW / Math.max(9f, wordCount * 2.6f);
        baseFontSize = Math.max(26f, Math.min(88f, baseFontSize));
        List<SonnetLayout.Placement> placements =
                SonnetLayout.layout(shot, VW, VH, baseFontSize, measurePaint);
        scene.contentScale = SonnetLayout.lastContentScale;
        long motionDuration = Math.min(
                (long) Math.max(650f, Math.min(1800f, shot.durationMs() * 0.42f)),
                (long) (shot.durationMs() * 0.72f));
        motionDuration = Math.max(120L, motionDuration);

        List<Glyph> glyphs = new ArrayList<>();
        for (SonnetLayout.Placement placement : placements) {
            if (placement.segmentIndex < 0 || placement.segmentIndex >= wordSegments.size()) continue;
            SonnetDirector.Segment segment = wordSegments.get(placement.segmentIndex);
            float totalAdvance = 0f;
            int count = segment.graphemes.size();
            float[] advances = new float[count];
            for (int i = 0; i < count; i++) {
                String ch = segment.graphemes.get(i).text;
                measurePaint.setTextSize(placement.fontSize);
                float advance;
                if (placement.vertical) {
                    Paint.FontMetrics metrics = measurePaint.getFontMetrics();
                    advance = Math.max(placement.fontSize * 1.30f,
                            (metrics.descent - metrics.ascent) * 0.95f) + 2f;
                } else {
                    advance = Math.max(placement.fontSize * 0.2f, measurePaint.measureText(ch));
                }
                advances[i] = advance;
                totalAdvance += advance;
            }
            float cursor = -totalAdvance * 0.5f;
            for (int i = 0; i < count; i++) {
                SonnetDirector.Grapheme grapheme = segment.graphemes.get(i);
                float localX = placement.vertical ? 0f : cursor + advances[i] * 0.5f;
                float localY = placement.vertical ? cursor + advances[i] * 0.5f : 0f;
                float cosine = (float) Math.cos(placement.rotation);
                float sine = (float) Math.sin(placement.rotation);
                Glyph glyph = new Glyph();
                glyph.text = grapheme.text;
                glyph.baseX = placement.x + localX * cosine - localY * sine;
                glyph.baseY = placement.y + localX * sine + localY * cosine;
                int stagger = i % 2 == 0 ? -1 : 1;
                glyph.enterX = placement.enterX + (placement.vertical
                        ? stagger * placement.fontSize * 0.10f : 0f);
                glyph.enterY = placement.enterY + (placement.vertical
                        ? 0f : stagger * Math.min(42f, placement.fontSize * 0.18f));
                glyph.entryRotation = stagger * (placement.role == SonnetDirector.Role.SUPPORT ? 0.035f : 0.055f);
                glyph.rotation = placement.rotation;
                glyph.fontSize = placement.fontSize;
                glyph.startMs = grapheme.startMs;
                glyph.endMs = segment.endMs;
                glyph.settleMs = Math.max(grapheme.startMs, grapheme.startMs + motionDuration);
                glyph.role = placement.role;
                glyph.emphasized = placement.role == SonnetDirector.Role.HERO
                        || placement.role == SonnetDirector.Role.SEMI_HERO;
                glyph.segmentIndex = placement.segmentIndex;
                glyph.zDepth = 0f;
                cursor += advances[i];
                glyphs.add(glyph);
            }
        }

        // Giant decorative echo behind the composition, like the original's
        // oversized background typography.
        SonnetDirector.Segment hero = wordSegments.get(0);
        float heroFont = 0f;
        for (SonnetLayout.Placement placement : placements) {
            if (placement.role == SonnetDirector.Role.HERO) {
                heroFont = placement.fontSize;
                hero = wordSegments.get(Math.min(placement.segmentIndex, wordSegments.size() - 1));
                break;
            }
        }
        boolean roomForDecoration = placements.size() <= 4
                && (shot.kind == SonnetDirector.Kind.TYPE_IMPACT
                || shot.kind == SonnetDirector.Kind.POSTER_BLOCKS
                || shot.kind == SonnetDirector.Kind.QUIET_TABLEAU);
        if (heroFont > 0f && roomForDecoration) {
            Glyph deco = new Glyph();
            deco.text = hero.text.length() > 8 ? hero.text.substring(0, 8) : hero.text;
            deco.baseX = 0f;
            deco.baseY = -VH * 0.30f;
            deco.fontSize = Math.min(240f, heroFont * 1.15f);
            deco.rotation = 0f;
            deco.entryRotation = 0f;
            deco.enterX = 0f;
            deco.enterY = 0f;
            deco.startMs = shot.startMs;
            deco.settleMs = shot.startMs + motionDuration;
            deco.role = SonnetDirector.Role.DECORATION;
            deco.emphasized = false;
            deco.segmentIndex = 0;
            deco.zDepth = -0.8f;
            glyphs.add(0, deco);
        }
        scene.glyphs = glyphs;
        for (SonnetLayout.Placement placement : placements) {
            if (placement.vertical || Math.abs(Math.sin(placement.rotation)) > 0.7f) {
                scene.verticalComposition = true;
                break;
            }
        }
        scene.tracking = new ArrayList<>();
        for (Glyph glyph : glyphs) {
            if (glyph.role != SonnetDirector.Role.DECORATION) scene.tracking.add(glyph);
        }
        scene.tracking.sort(Comparator.comparingLong(g -> g.startMs));
        scene.landmarks = new ArrayList<>();
        for (int s = 0; s < wordSegments.size(); s++) {
            float sumX = 0f;
            float sumY = 0f;
            int count = 0;
            for (Glyph glyph : glyphs) {
                if (glyph.role != SonnetDirector.Role.DECORATION && glyph.segmentIndex == s) {
                    sumX += glyph.baseX;
                    sumY += glyph.baseY;
                    count++;
                }
            }
            if (count == 0) continue;
            SonnetDirector.Segment segment = wordSegments.get(s);
            scene.landmarks.add(new Landmark(segment.startMs, segment.endMs,
                    sumX / count, sumY / count));
        }
        scene.basePivotX = 0f;
        scene.basePivotY = 0f;
        for (SonnetLayout.Placement placement : placements) {
            scene.basePivotX += placement.x;
            scene.basePivotY += placement.y;
        }
        scene.basePivotX /= Math.max(1, placements.size());
        scene.basePivotY /= Math.max(1, placements.size());
        return scene;
    }

    // ------------------------------------------------------------------
    // Camera
    // ------------------------------------------------------------------

    private static final class CameraFrame {
        float baseX;
        float baseY;
        float pivotX;
        float pivotY;
        float scale;
        float rotation;
        float motionX;
        float motionY;
    }

    private CameraFrame resolveCamera(Scene scene, long positionMs) {
        SonnetDirector.Shot shot = scene.shot;
        float progress = SonnetMotion.clamp01(
                (positionMs - shot.startMs) / (float) Math.max(1L, shot.endMs - shot.startMs));
        float[] motion = SonnetMotion.shotMotionFrame(shot.kind, progress);

        long gapTime = Math.max(0L, positionMs - shot.endMs);
        if (gapTime > 0) {
            float[] tail = SonnetMotion.shotMotionFrame(shot.kind, 0.8f);
            float drift = (float) ((1.0 - Math.exp(-gapTime / 1000.0 * 0.4)) * 0.45);
            motion[0] += (motion[0] - tail[0]) * drift;
            motion[1] += (motion[1] - tail[1]) * drift;
            motion[2] += (motion[2] - tail[2]) * drift;
            motion[3] += (motion[3] - tail[3]) * drift;
            // Long instrumental hold: a slow, organic camera sway so the frame
            // never freezes while the three interlude dots count down.
            double swaySeconds = gapTime / 1000.0;
            float swayRamp = SonnetMotion.clamp01((float) swaySeconds / 1.5f);
            motion[0] += (float) Math.sin(swaySeconds * 0.35) * 0.108f * swayRamp;
            motion[1] += (float) Math.sin(swaySeconds * 0.27 + 1.2) * 0.084f * swayRamp;
            motion[3] += (float) Math.sin(swaySeconds * 0.21 + 0.7) * 0.030f * swayRamp;
        }
        if (shot.interlude) {
            // The break is a normal typographic shot now; this is the slow
            // hand-held sway that keeps the three dots drifting through it.
            double swaySeconds = (positionMs - shot.startMs) / 1000.0;
            float swayRamp = SonnetMotion.clamp01((float) swaySeconds / 1.5f);
            motion[0] += (float) Math.sin(swaySeconds * 0.35) * 0.108f * swayRamp;
            motion[1] += (float) Math.sin(swaySeconds * 0.27 + 1.2) * 0.084f * swayRamp;
            motion[3] += (float) Math.sin(swaySeconds * 0.21 + 0.7) * 0.030f * swayRamp;
        }

        long revealDone = shot.endMs;
        if (!scene.tracking.isEmpty()) {
            revealDone = scene.tracking.get(scene.tracking.size() - 1).settleMs;
        }
        float breathWeight = SonnetMotion.breathWeight(positionMs, revealDone, 1200L);
        if (breathWeight > 0f) {
            float phase = (SonnetDirector.hashSeed(seed + ":" + shot.index) & 1023) / 1023f
                    * (float) Math.PI * 2f;
            float[] breath = SonnetMotion.cameraBreath(positionMs, phase);
            motion[0] += breath[0] * breathWeight;
            motion[1] += breath[1] * breathWeight;
            motion[2] += breath[2] * breathWeight;
            motion[3] += breath[3] * breathWeight;
        }

        float focusX = scene.basePivotX;
        float focusY = scene.basePivotY;
        if (!scene.landmarks.isEmpty()) {
            float[] focus = stableFocus(scene, positionMs);
            focusX = scene.basePivotX + (focus[0] - scene.basePivotX) * 0.32f;
            focusY = scene.basePivotY + (focus[1] - scene.basePivotY) * 0.32f;
        }

        CameraFrame frame = new CameraFrame();
        frame.baseX = viewW * (0.5f + shot.cameraX);
        frame.baseY = viewH * (0.48f + shot.cameraY);
        frame.pivotX = scene.basePivotX + (focusX - scene.basePivotX);
        frame.pivotY = scene.basePivotY + (focusY - scene.basePivotY);
        // If the layout had to shrink to fit, move the camera closer by the
        // same ratio so long lines stay readable instead of turning tiny.
        // Long lines are split into several shots instead of being zoomed in;
        // keep the camera at its authored framing so the type size stays the
        // one the rest of the song uses.
        frame.scale = shot.cameraZoom * motion[2];
        frame.rotation = shot.cameraRotation + motion[3];
        frame.motionX = motion[0] * 0.72f;
        frame.motionY = motion[1] * 0.72f;
        return frame;
    }

    /**
     * Continuous landmark interpolation with a critically damped follower.
     * Landmarks are segment centres in timeline order; because the flow layout
     * keeps consecutive segments close, the target never jumps and the camera
     * simply glides from one word to the next.
     */
    private float[] stableFocus(Scene scene, long positionMs) {
        List<Landmark> landmarks = scene.landmarks;
        float targetX;
        float targetY;
        if (positionMs <= landmarks.get(0).startMs) {
            targetX = landmarks.get(0).x;
            targetY = landmarks.get(0).y;
        } else if (positionMs >= landmarks.get(landmarks.size() - 1).startMs) {
            Landmark last = landmarks.get(landmarks.size() - 1);
            targetX = last.x;
            targetY = last.y;
        } else {
            targetX = landmarks.get(0).x;
            targetY = landmarks.get(0).y;
            for (int i = 0; i < landmarks.size() - 1; i++) {
                Landmark current = landmarks.get(i);
                Landmark next = landmarks.get(i + 1);
                if (positionMs < current.startMs || positionMs > next.startMs) continue;
                float progress = (positionMs - current.startMs)
                        / (float) Math.max(1L, next.startMs - current.startMs);
                float eased = progress * progress * (3f - 2f * progress);
                targetX = current.x + (next.x - current.x) * eased;
                targetY = current.y + (next.y - current.y) * eased;
                break;
            }
        }
        long deltaTime = positionMs - scene.lastFocusTimeMs;
        if (!scene.focusInitialized || Math.abs(deltaTime) > 600L) {
            scene.focusX = targetX;
            scene.focusY = targetY;
            scene.focusInitialized = true;
        } else {
            float dt = Math.min(0.12f, Math.max(0.001f, deltaTime / 1000f));
            float k = 1f - (float) Math.exp(-dt / 0.42f);
            scene.focusX += (targetX - scene.focusX) * k;
            scene.focusY += (targetY - scene.focusY) * k;
        }
        scene.lastFocusTimeMs = positionMs;
        return new float[]{scene.focusX, scene.focusY};
    }

    /** Weighted ±120 ms temporal smoothing, edge-preserving like the original. */
    private float[] smoothedFocus(Scene scene, long timeMs) {
        float[] weights = {1f, 3f, 5f, 3f, 1f};
        long[] offsets = {-220L, -110L, 0L, 110L, 220L};
        float centerX = 0f;
        float centerY = 0f;
        float[] point = new float[2];
        for (int i = 0; i < 5; i++) {
            long sampleTime = Math.max(scene.shot.startMs,
                    Math.min(scene.shot.endMs, timeMs + offsets[i]));
            focusRaw(scene, sampleTime, point);
            if (i == 2) {
                centerX = point[0];
                centerY = point[1];
            }
        }
        float x = 0f;
        float y = 0f;
        float total = 0f;
        for (int i = 0; i < 5; i++) {
            long sampleTime = Math.max(scene.shot.startMs,
                    Math.min(scene.shot.endMs, timeMs + offsets[i]));
            focusRaw(scene, sampleTime, point);
            float dx = point[0] - centerX;
            float dy = point[1] - centerY;
            if (dx * dx + dy * dy > 140f * 140f) continue;
            x += point[0] * weights[i];
            y += point[1] * weights[i];
            total += weights[i];
        }
        if (total <= 0f) return new float[]{centerX, centerY};
        return new float[]{x / total, y / total};
    }

    private void focusRaw(Scene scene, long timeMs, float[] out) {
        List<Glyph> glyphs = scene.tracking;
        if (glyphs.isEmpty()) {
            out[0] = scene.basePivotX;
            out[1] = scene.basePivotY;
            return;
        }
        Glyph first = glyphs.get(0);
        Glyph last = glyphs.get(glyphs.size() - 1);
        float x;
        float y;
        int segmentIndex;
        if (timeMs <= first.startMs) {
            x = first.baseX;
            y = first.baseY;
            segmentIndex = first.segmentIndex;
        } else if (timeMs >= last.startMs) {
            x = last.baseX;
            y = last.baseY;
            segmentIndex = last.segmentIndex;
        } else {
            x = first.baseX;
            y = first.baseY;
            segmentIndex = first.segmentIndex;
            for (int i = 0; i < glyphs.size() - 1; i++) {
                Glyph current = glyphs.get(i);
                Glyph next = glyphs.get(i + 1);
                if (timeMs < current.startMs || timeMs > next.startMs) continue;
                float progress = (timeMs - current.startMs)
                        / (float) Math.max(1L, next.startMs - current.startMs);
                x = current.baseX + (next.baseX - current.baseX) * progress;
                y = current.baseY + (next.baseY - current.baseY) * progress;
                segmentIndex = current.segmentIndex;
                break;
            }
        }
        // The original only follows half of the distance from the segment center.
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        boolean found = false;
        for (Glyph glyph : glyphs) {
            if (glyph.segmentIndex != segmentIndex) continue;
            minX = Math.min(minX, glyph.baseX);
            maxX = Math.max(maxX, glyph.baseX);
            minY = Math.min(minY, glyph.baseY);
            maxY = Math.max(maxY, glyph.baseY);
            found = true;
        }
        if (found) {
            float centerX = (minX + maxX) * 0.5f;
            float centerY = (minY + maxY) * 0.5f;
            x = centerX + (x - centerX) * 0.32f;
            y = centerY + (y - centerY) * 0.32f;
        }
        out[0] = x;
        out[1] = y;
    }

    // ------------------------------------------------------------------
    // Scene MG / decorations
    // ------------------------------------------------------------------

    private void drawSceneMg(Canvas canvas, Scene scene, long positionMs, float alpha) {
        long shotTime = positionMs - scene.shot.startMs;
        switch (scene.mgVariant) {
            case 0:
                drawBotanical(canvas, shotTime, alpha);
                break;
            case 1:
                drawCelestial(canvas, shotTime, alpha);
                break;
            case 2:
                drawCraft(canvas, alpha);
                break;
            case 3:
                drawFlora(canvas, shotTime, alpha);
                break;
            case 4:
                drawKinetic(canvas, shotTime, alpha);
                break;
            case 5:
                drawLandscape(canvas, alpha);
                break;
            case 6:
                drawMarine(canvas, shotTime, alpha);
                break;
            default:
                drawMusicStaff(canvas, shotTime, alpha);
                break;
        }
    }

    private void drawBotanical(Canvas canvas, long shotTime, float alpha) {
        shapePaint.setStrokeWidth(2.4f);
        shapePaint.setAlpha((int) (52 * alpha));
        float sway = (float) Math.sin(shotTime / 2600.0) * 14f;
        canvas.save();
        canvas.translate(-VW * 0.36f, VH * 0.16f);
        path.reset();
        path.moveTo(96f, VH * 0.42f);
        path.quadTo(86f + sway, 20f, 150f + sway, -VH * 0.34f);
        canvas.drawPath(path, shapePaint);
        canvas.restore();
        shapePaint.setAlpha((int) (38 * alpha));
        canvas.save();
        canvas.translate(VW * 0.36f, VH * 0.16f);
        path.reset();
        path.moveTo(-96f, VH * 0.42f);
        path.quadTo(-86f - sway, 20f, -150f - sway, -VH * 0.34f);
        canvas.drawPath(path, shapePaint);
        canvas.restore();
    }

    private void drawCelestial(Canvas canvas, long shotTime, float alpha) {
        float angle = shotTime / 5200f;
        shapePaint.setStrokeWidth(1.6f);
        shapePaint.setAlpha((int) (40 * alpha));
        canvas.drawCircle(0f, 0f, 300f, shapePaint);
        canvas.drawCircle(0f, 0f, 220f, shapePaint);
        accentPaint.setAlpha((int) (70 * alpha));
        for (int i = 0; i < 5; i++) {
            double a = angle + i * Math.PI * 2 / 5;
            canvas.drawCircle((float) Math.cos(a) * 300f, (float) Math.sin(a) * 300f, 5f, accentPaint);
        }
    }

    private void drawCraft(Canvas canvas, float alpha) {
        shapePaint.setStrokeWidth(1.2f);
        shapePaint.setAlpha((int) (30 * alpha));
        for (int x = -640; x <= 640; x += 80) canvas.drawLine(x, -360f, x, 360f, shapePaint);
        for (int y = -360; y <= 360; y += 80) canvas.drawLine(-640f, y, 640f, y, shapePaint);
        accentPaint.setAlpha((int) (40 * alpha));
        rect.set(-420f, -210f, -280f, -70f);
        canvas.drawRect(rect, accentPaint);
        rect.set(300f, 120f, 430f, 250f);
        canvas.drawRect(rect, accentPaint);
    }

    private void drawFlora(Canvas canvas, long shotTime, float alpha) {
        accentPaint.setAlpha((int) (42 * alpha));
        float spin = shotTime / 6400f;
        for (int i = 0; i < 10; i++) {
            float x = -560f + (i * 137) % 1120;
            float y = -280f + (i * 89) % 560;
            canvas.save();
            canvas.rotate(spin * 40f + i * 21f, x, y);
            rect.set(x - 34f, y - 8f, x + 34f, y + 8f);
            canvas.drawOval(rect, accentPaint);
            canvas.restore();
        }
    }

    private void drawKinetic(Canvas canvas, long shotTime, float alpha) {
        accentPaint.setAlpha((int) (36 * alpha));
        float shift = (shotTime / 6f) % 260f;
        for (int i = 0; i < 10; i++) {
            float y = -300f + i * 68f;
            float width = 180f + (i * 53 % 420);
            float x = -600f + (i * 97 % 1100) + shift;
            canvas.drawRect(x, y, x + width, y + 3f, accentPaint);
        }
    }

    private void drawLandscape(Canvas canvas, float alpha) {
        shapePaint.setStrokeWidth(2.4f);
        shapePaint.setAlpha((int) (44 * alpha));
        path.reset();
        path.moveTo(-640f, 300f);
        path.quadTo(-260f, 40f, 80f, 290f);
        path.quadTo(420f, 60f, 640f, 300f);
        canvas.drawPath(path, shapePaint);
    }

    private void drawMarine(Canvas canvas, long shotTime, float alpha) {
        shapePaint.setStrokeWidth(2f);
        for (int row = 0; row < 4; row++) {
            shapePaint.setAlpha((int) ((40 - row * 7) * alpha));
            path.reset();
            float baseY = 120f + row * 36f;
            float phase = shotTime / 900f + row * 1.4f;
            path.moveTo(-640f, baseY);
            for (int x = -640; x <= 640; x += 40) {
                path.lineTo(x, baseY + (float) Math.sin(x / 130.0 + phase) * (10f + row * 2f));
            }
            canvas.drawPath(path, shapePaint);
        }
    }

    private void drawMusicStaff(Canvas canvas, long shotTime, float alpha) {
        shapePaint.setStrokeWidth(1.4f);
        shapePaint.setAlpha((int) (34 * alpha));
        float top = 150f;
        for (int i = 0; i < 5; i++) {
            canvas.drawLine(-460f, top + i * 16f, 460f, top + i * 16f, shapePaint);
        }
        accentPaint.setAlpha((int) (80 * alpha));
        for (int i = 0; i < 7; i++) {
            float phase = (shotTime / 1400f + i * 0.7f) % 1f;
            float x = -440f + phase * 880f;
            float y = top + ((i * 2) % 5) * 16f;
            canvas.drawOval(x - 8f, y - 6f, x + 8f, y + 6f, accentPaint);
            canvas.drawRect(x + 7f, y - 36f, x + 9.6f, y, accentPaint);
        }
    }

    // ------------------------------------------------------------------
    // Extra graphic layers (scene space, so they parallax with the camera)
    // ------------------------------------------------------------------

    /**
     * Shot-specific motion graphics, following the original's per-kind MG
     * families: bursts for type-impact, rules for editorial, torn paper for
     * collage, ribbons/ticks, reveal scans, poster blocks and quiet orbits.
     */
    private void drawShotKindFx(Canvas canvas, Scene scene, long positionMs, float alpha) {
        double t = positionMs / 1000.0;
        int seed = SonnetDirector.hashSeed("fx" + scene.shot.index);
        Random random = new Random(seed);
        switch (scene.shot.kind) {
            case TYPE_IMPACT: {
                shapePaint.setColor(0xFFEAF6FF);
                shapePaint.setStrokeWidth(1.8f);
                for (int i = 0; i < 12; i++) {
                    double angle = i * Math.PI / 6.0 + t * 0.18;
                    float inner = 120f + (float) Math.sin(t * 0.7 + i) * 12f;
                    shapePaint.setAlpha((int) (alpha * 26f));
                    canvas.drawLine(
                            (float) Math.cos(angle) * inner,
                            (float) Math.sin(angle) * inner,
                            (float) Math.cos(angle) * (inner + 150f),
                            (float) Math.sin(angle) * (inner + 150f), shapePaint);
                }
                effectPaint.setColor(accentColor);
                for (int ring = 0; ring < 3; ring++) {
                    float progress = (float) ((t * 0.5 + ring / 3.0) % 1.0);
                    effectPaint.setAlpha((int) (alpha * (1f - progress) * 28f));
                    canvas.drawCircle(0f, 0f, 60f + progress * 360f, effectPaint);
                }
                break;
            }
            case EDITORIAL_COLUMN: {
                shapePaint.setColor(0xFFEAF6FF);
                shapePaint.setStrokeWidth(1f);
                for (int i = 0; i < 9; i++) {
                    float y = -240f + i * 60f;
                    shapePaint.setAlpha((int) (alpha * 20f));
                    canvas.drawLine(-420f, y, 420f, y, shapePaint);
                }
                effectPaint.setColor(accentColor);
                effectPaint.setAlpha((int) (alpha * 30f));
                rect.set(300f, -300f, 470f, -150f);
                canvas.drawRect(rect, effectPaint);
                effectPaint.setAlpha((int) (alpha * 18f));
                rect.set(-470f, 180f, -320f, 300f);
                canvas.drawRect(rect, effectPaint);
                break;
            }
            case FRAGMENT_COLLAGE: {
                for (int i = 0; i < 5; i++) {
                    float x = -520f + random.nextFloat() * 1040f;
                    float y = -300f + random.nextFloat() * 600f;
                    float width = 90f + random.nextFloat() * 180f;
                    float height = 50f + random.nextFloat() * 120f;
                    float rotation = (random.nextFloat() - 0.5f) * 24f;
                    canvas.save();
                    canvas.rotate(rotation, x, y);
                    shapePaint.setColor(0xFFEAF6FF);
                    shapePaint.setStrokeWidth(1.2f);
                    shapePaint.setAlpha((int) (alpha * 26f));
                    rect.set(x - width * 0.5f, y - height * 0.5f,
                            x + width * 0.5f, y + height * 0.5f);
                    canvas.drawRect(rect, shapePaint);
                    effectPaint.setColor(accentColor);
                    effectPaint.setAlpha((int) (alpha * 22f));
                    rect.set(x - width * 0.5f - 18f, y - 8f,
                            x + width * 0.5f + 18f, y + 8f);
                    canvas.drawRect(rect, effectPaint);
                    canvas.restore();
                }
                break;
            }
            case TRACKING_RIBBON: {
                for (int band = 0; band < 3; band++) {
                    float y = -180f + band * 180f;
                    effectPaint.setColor(band == 1 ? accentColor : 0xFFEAF6FF);
                    effectPaint.setAlpha((int) (alpha * (band == 1 ? 34f : 18f)));
                    canvas.drawRect(-560f, y - 2.5f, 560f, y + 2.5f, effectPaint);
                    shapePaint.setColor(0xFFEAF6FF);
                    shapePaint.setStrokeWidth(1.2f);
                    for (int tick = -8; tick <= 8; tick++) {
                        shapePaint.setAlpha((int) (alpha * 22f));
                        float x = tick * 70f;
                        canvas.drawLine(x, y - 14f, x, y - 4f, shapePaint);
                    }
                }
                break;
            }
            case MASK_REVEAL: {
                for (int i = 0; i < 6; i++) {
                    float y = -300f + i * 120f + (float) ((t * 40.0) % 120.0);
                    effectPaint.setColor(0xFFEAF6FF);
                    effectPaint.setAlpha((int) (alpha * 12f));
                    canvas.drawRect(-520f, y - 9f, 520f, y + 9f, effectPaint);
                }
                shapePaint.setColor(accentColor);
                shapePaint.setStrokeWidth(2.4f);
                float edge = -260f + (float) ((t * 120.0) % 520.0);
                shapePaint.setAlpha((int) (alpha * 55f));
                canvas.drawLine(edge, -320f, edge, 320f, shapePaint);
                break;
            }
            case POSTER_BLOCKS: {
                effectPaint.setColor(accentColor);
                for (int i = 0; i < 4; i++) {
                    float x = (i % 2 == 0 ? -1f : 1f) * (180f + (i / 2) * 210f);
                    float y = (i / 2 == 0 ? -1f : 1f) * 170f;
                    effectPaint.setAlpha((int) (alpha * 20f));
                    rect.set(x - 110f, y - 80f, x + 110f, y + 80f);
                    canvas.drawRoundRect(rect, 10f, 10f, effectPaint);
                    shapePaint.setColor(0xFFEAF6FF);
                    shapePaint.setStrokeWidth(1.4f);
                    shapePaint.setAlpha((int) (alpha * 40f));
                    canvas.drawRoundRect(rect, 10f, 10f, shapePaint);
                }
                accentPaint.setColor(accentColor);
                accentPaint.setAlpha((int) (alpha * 34f));
                canvas.drawCircle(-330f, 230f, 42f, accentPaint);
                canvas.drawCircle(360f, -240f, 28f, accentPaint);
                break;
            }
            case QUIET_TABLEAU:
            default: {
                shapePaint.setColor(accentColor);
                shapePaint.setStrokeWidth(1.4f);
                for (int i = 0; i < 3; i++) {
                    shapePaint.setAlpha((int) (alpha * (32 - i * 6)));
                    canvas.drawCircle(0f, 0f, 180f + i * 70f, shapePaint);
                }
                accentPaint.setColor(0xFFFFFFFF);
                for (int i = 0; i < 6; i++) {
                    double angle = t * 0.25 + i * Math.PI / 3.0;
                    accentPaint.setAlpha((int) (alpha * 45f));
                    canvas.drawCircle(
                            (float) Math.cos(angle) * 250f,
                            (float) Math.sin(angle) * 180f, 3.5f, accentPaint);
                }
                break;
            }
        }
    }

    private void drawSceneBackFx(Canvas canvas, Scene scene, long positionMs,
                                 float alpha, CameraFrame camera) {
        if (scene.particles == null) return;
        double t = positionMs / 1000.0;
        for (int i = 0; i < scene.particles.length; i++) {
            float[] particle = scene.particles[i];
            float depth = particle[3];
            float x = particle[0] + (float) Math.sin(t * 0.3 + particle[4]) * 24f
                    + camera.motionX * VW * depth * 1.6f;
            float y = particle[1] + (float) Math.cos(t * 0.24 + particle[4]) * 18f
                    + camera.motionY * VH * depth * 1.6f;
            float twinkle = 0.35f + 0.65f
                    * (0.5f + 0.5f * (float) Math.sin(t * 0.8 + particle[4] * 1.7));
            effectPaint.setColor(i % 4 == 0 ? accentColor : 0xFFEAF6FF);
            effectPaint.setAlpha((int) (alpha * 26f * twinkle));
            canvas.drawCircle(x, y, particle[2], effectPaint);
        }

        // Concentric pulse rings.
        shapePaint.setColor(accentColor);
        shapePaint.setStrokeWidth(2f);
        for (int i = 0; i < 3; i++) {
            float progress = (float) ((t * 0.22 + i / 3.0) % 1.0);
            float radius = 70f + progress * 430f;
            shapePaint.setAlpha((int) (alpha * (1f - progress) * 30f));
            canvas.drawCircle(0f, 0f, radius, shapePaint);
        }

        // Slowly rotating wireframe polygon.
        float polygonRadius = 336f + (float) Math.sin(t * 0.4) * 12f;
        float polygonRotation = (float) t * 0.07f;
        path.reset();
        for (int i = 0; i < 6; i++) {
            double angle = polygonRotation + i * Math.PI / 3.0;
            float x = (float) Math.cos(angle) * polygonRadius;
            float y = (float) Math.sin(angle) * polygonRadius * 0.72f;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.close();
        shapePaint.setAlpha((int) (alpha * 24f));
        shapePaint.setStrokeWidth(1.6f);
        canvas.drawPath(path, shapePaint);

        SonnetDirector.Kind kind = scene.shot.kind;
        if (kind == SonnetDirector.Kind.TYPE_IMPACT
                || kind == SonnetDirector.Kind.TRACKING_RIBBON) {
            shapePaint.setColor(0xFFEAF6FF);
            shapePaint.setStrokeWidth(1.4f);
            for (int i = 0; i < 9; i++) {
                float offset = (float) ((i * 137 + t * 110) % 1400.0) - 700f;
                shapePaint.setAlpha((int) (alpha * 22f));
                canvas.drawLine(offset - 90f, -VH * 0.44f, offset + 90f, VH * 0.44f, shapePaint);
            }
        }
        if (kind == SonnetDirector.Kind.EDITORIAL_COLUMN
                || kind == SonnetDirector.Kind.MASK_REVEAL) {
            shapePaint.setColor(accentColor);
            shapePaint.setStrokeWidth(1f);
            float spacing = 54f;
            float drift = (float) ((t * 18.0) % spacing);
            for (int i = 0; i < 16; i++) {
                float y = -VH * 0.5f + i * spacing + drift;
                shapePaint.setAlpha((int) (alpha * 15f));
                canvas.drawLine(-VW * 0.46f, y, VW * 0.46f, y, shapePaint);
            }
        }
    }

    private void drawSceneFrontFx(Canvas canvas, Scene scene, long positionMs,
                                  float alpha, CameraFrame camera) {
        double t = positionMs / 1000.0;
        SonnetDirector.Kind kind = scene.shot.kind;

        // Impact shockwave during the first 700 ms of the shot.
        long sinceStart = positionMs - scene.shot.startMs;
        if (kind == SonnetDirector.Kind.TYPE_IMPACT && sinceStart >= 0 && sinceStart < 700L) {
            float progress = sinceStart / 700f;
            shapePaint.setColor(accentColor);
            shapePaint.setStrokeWidth(3f);
            shapePaint.setAlpha((int) (alpha * (1f - progress) * 85f));
            canvas.drawCircle(0f, 0f, 46f + progress * 540f, shapePaint);
        }

        // Twinkling plus-shaped glints.
        if (scene.sparkles != null) {
            shapePaint.setColor(0xFFFFFFFF);
            shapePaint.setStrokeWidth(1.6f);
            for (float[] sparkle : scene.sparkles) {
                float twinkle = 0.5f + 0.5f * (float) Math.sin(t * 1.1 + sparkle[2]);
                float size = sparkle[3] * twinkle;
                shapePaint.setAlpha((int) (alpha * 90f * twinkle));
                canvas.drawLine(sparkle[0] - size, sparkle[1], sparkle[0] + size, sparkle[1], shapePaint);
                canvas.drawLine(sparkle[0], sparkle[1] - size, sparkle[0], sparkle[1] + size, shapePaint);
            }
        }

        // Accent sweep behind the current composition.
        float sweep = (float) ((t * 0.32) % 1.0);
        float sweepX = -VW * 0.62f + sweep * VW * 1.24f;
        effectPaint.setColor(accentColor);
        effectPaint.setAlpha((int) (alpha * 20f));
        canvas.drawRect(sweepX, scene.basePivotY - 150f, sweepX + 160f,
                scene.basePivotY + 150f, effectPaint);

        // Technical HUD corner ticks.
        shapePaint.setColor(accentColor);
        shapePaint.setStrokeWidth(1.4f);
        shapePaint.setAlpha((int) (alpha * 34f));
        float cx = VW * 0.44f;
        float cy = VH * 0.40f;
        canvas.drawLine(-cx, -cy, -cx + 26f, -cy, shapePaint);
        canvas.drawLine(-cx, -cy, -cx, -cy + 26f, shapePaint);
        canvas.drawLine(cx, cy, cx - 26f, cy, shapePaint);
        canvas.drawLine(cx, cy, cx, cy - 26f, shapePaint);
    }

    // ------------------------------------------------------------------
    // Instrumental / empty state
    // ------------------------------------------------------------------

    /**
     * "暂未找到歌词" is rendered by the same typography pipeline as real lyrics:
     * a virtual shot of one segment per character, entering one after another
     * and drifting with a gentle camera sway.
     */
    private Scene ensureNoLyricsScene() {
        if (noLyricsScene != null) return noLyricsScene;
        String text = getResources().getString(R.string.lyrics_not_found);
        Scene scene = new Scene();
        scene.shot = new SonnetDirector.Shot(-1, SonnetDirector.Kind.QUIET_TABLEAU,
                0L, 600_000L, new ArrayList<SonnetDirector.Segment>(),
                0f, 0f, 1f, 0f, 0, false, false);
        scene.mgVariant = 1;
        List<Glyph> glyphs = new ArrayList<>();
        float fontSize = 86f;
        measurePaint.setTextSize(fontSize);
        float totalWidth = 0f;
        float[] widths = new float[text.length()];
        for (int i = 0; i < text.length(); i++) {
            widths[i] = measurePaint.measureText(text, i, i + 1);
            totalWidth += widths[i];
        }
        float cursor = -totalWidth * 0.5f;
        for (int i = 0; i < text.length(); i++) {
            Glyph glyph = new Glyph();
            glyph.text = text.substring(i, i + 1);
            glyph.fontSize = fontSize;
            glyph.baseX = cursor + widths[i] * 0.5f;
            glyph.baseY = 0f;
            glyph.rotation = 0f;
            glyph.entryRotation = 0f;
            glyph.enterX = 0f;
            glyph.enterY = 28f;
            glyph.startMs = i * 220L;
            glyph.settleMs = glyph.startMs + 900L;
            glyph.role = SonnetDirector.Role.SUPPORT;
            glyph.emphasized = false;
            glyph.segmentIndex = i;
            glyph.zDepth = 0f;
            cursor += widths[i];
            glyphs.add(glyph);
        }
        scene.glyphs = glyphs;
        scene.tracking = new ArrayList<>(glyphs);
        scene.landmarks = new ArrayList<>();
        scene.basePivotX = 0f;
        scene.basePivotY = 0f;
        noLyricsScene = scene;
        return scene;
    }

    private void drawNoLyricsScene(Canvas canvas, long now) {
        Scene scene = ensureNoLyricsScene();
        if (scene == null) return;
        long time = playing ? Math.max(0L, now - noLyricsStartMs) : 600_000L;
        CameraFrame camera = new CameraFrame();
        camera.baseX = viewW * 0.5f;
        camera.baseY = viewH * 0.5f;
        camera.pivotX = 0f;
        camera.pivotY = 0f;
        camera.scale = 1f;
        double sway = time / 1000.0;
        float ramp = SonnetMotion.clamp01((float) sway / 1.5f);
        camera.motionX = (float) Math.sin(sway * 0.35) * 0.054f * ramp;
        camera.motionY = (float) Math.sin(sway * 0.27 + 1.2) * 0.042f * ramp;
        camera.rotation = (float) Math.sin(sway * 0.21 + 0.7) * 0.015f * ramp;
        drawScene(canvas, scene, time, contentAlpha, 0f, 0f, 1f, 0f, camera);
    }

    private void drawInstrumental(Canvas canvas, long now) {
        if (playing) {
            canvas.save();
            canvas.translate(viewW * 0.5f, viewH * 0.22f);
            drawMusicStaff(canvas, now, contentAlpha);
            canvas.restore();
            drawFrameMarks(canvas);
            return;
        }
        float pulse = 0.5f + 0.5f * (float) Math.sin(now / 800.0);
        shapePaint.setAlpha((int) ((26 + 24 * pulse) * contentAlpha));
        shapePaint.setStrokeWidth(1.4f);
        canvas.drawCircle(viewW * 0.5f, viewH * 0.5f, 120f + 18f * pulse, shapePaint);
        shapePaint.setAlpha((int) ((18 + 14 * pulse) * contentAlpha));
        canvas.drawCircle(viewW * 0.5f, viewH * 0.5f, 170f + 26f * pulse, shapePaint);
        drawFrameMarks(canvas);
    }

    // ------------------------------------------------------------------
    // Post effects
    // ------------------------------------------------------------------

    private void drawPostEffects(Canvas canvas) {
        if (!canvasPostEffects) return;
        if (!lowPower && activeShot >= 0) {
            Scene scene = sceneCache.get(activeShot);
            if (scene != null && (scene.shot.kind == SonnetDirector.Kind.POSTER_BLOCKS
                    || scene.shot.kind == SonnetDirector.Kind.FRAGMENT_COLLAGE)) {
                drawHalftone(canvas);
            }
            drawGrain(canvas);
        }
        canvas.drawRect(0f, 0f, viewW, viewH, vignettePaint);
    }

    private void drawGrain(Canvas canvas) {
        if (noiseBitmap == null) noiseBitmap = buildNoiseBitmap();
        if (noiseBitmap == null) return;
        effectPaint.setAlpha(12);
        int offsetX = (frameCounter * 7) % noiseBitmap.getWidth();
        int offsetY = (frameCounter * 11) % noiseBitmap.getHeight();
        for (int y = -offsetY; y < viewH; y += noiseBitmap.getHeight()) {
            for (int x = -offsetX; x < viewW; x += noiseBitmap.getWidth()) {
                canvas.drawBitmap(noiseBitmap, x, y, effectPaint);
            }
        }
    }

    private void drawHalftone(Canvas canvas) {
        if (halftoneBitmap == null) halftoneBitmap = buildHalftoneBitmap();
        if (halftoneBitmap == null) return;
        effectPaint.setAlpha(9);
        for (int y = 0; y < viewH; y += halftoneBitmap.getHeight()) {
            for (int x = 0; x < viewW; x += halftoneBitmap.getWidth()) {
                canvas.drawBitmap(halftoneBitmap, x, y, effectPaint);
            }
        }
    }

    private void drawFrameMarks(Canvas canvas) {
        shapePaint.setAlpha(30);
        shapePaint.setStrokeWidth(1.2f);
        float inset = 22f;
        float len = 34f;
        canvas.drawLine(inset, inset, inset + len, inset, shapePaint);
        canvas.drawLine(inset, inset, inset, inset + len, shapePaint);
        canvas.drawLine(viewW - inset, inset, viewW - inset - len, inset, shapePaint);
        canvas.drawLine(viewW - inset, inset, viewW - inset, inset + len, shapePaint);
        canvas.drawLine(inset, viewH - inset, inset + len, viewH - inset, shapePaint);
        canvas.drawLine(inset, viewH - inset, inset, viewH - inset - len, shapePaint);
        canvas.drawLine(viewW - inset, viewH - inset, viewW - inset - len, viewH - inset, shapePaint);
        canvas.drawLine(viewW - inset, viewH - inset, viewW - inset, viewH - inset - len, shapePaint);
    }

    private Bitmap buildNoiseBitmap() {
        try {
            int w = 160;
            int h = 90;
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
            Random random = new Random(0xC0FFEE);
            int[] pixels = new int[w * h];
            for (int i = 0; i < pixels.length; i++) pixels[i] = random.nextInt(256) << 24;
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
            return bitmap;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Bitmap buildHalftoneBitmap() {
        try {
            int w = 8;
            int h = 8;
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    pixels[y * w + x] = (x == 3 && y == 3) ? 0xFF000000 : 0;
                }
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
            return bitmap;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Data classes
    // ------------------------------------------------------------------

    private static final class Glyph {
        String text;
        float baseX;
        float baseY;
        float enterX;
        float enterY;
        float entryRotation;
        float rotation;
        float fontSize;
        long startMs;
        long endMs;
        long settleMs;
        SonnetDirector.Role role;
        boolean emphasized;
        int segmentIndex;
        float zDepth;
    }

    private static final class Scene {
        SonnetDirector.Shot shot;
        List<Glyph> glyphs = new ArrayList<>();
        List<Glyph> tracking = new ArrayList<>();
        List<Landmark> landmarks = new ArrayList<>();
        float basePivotX;
        float basePivotY;
        int mgVariant;
        boolean verticalComposition;
        float contentScale = 1f;
        float[][] particles;
        float[][] sparkles;
        float focusX;
        float focusY;
        boolean focusInitialized;
        long lastFocusTimeMs = Long.MIN_VALUE / 4;
    }

    private static final class Landmark {
        final long startMs;
        final long endMs;
        final float x;
        final float y;

        Landmark(long startMs, long endMs, float x, float y) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.x = x;
            this.y = y;
        }
    }
}
