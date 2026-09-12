package com.airmusic.player.view;

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

import com.airmusic.player.lyrics.LyricLine;
import com.airmusic.player.lyrics.LyricProgram;
import com.airmusic.player.lyrics.Lyrics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Native 720p interpretation of Folia's "Sonnet" lyric director.
 *
 * <p>Seven shot templates, three transitions, eight background-decoration
 * variants, giant decorative typography, translation subtitles and a light
 * print-style post stack (vignette, grain, halftone, chromatic shift) are all
 * drawn on a fixed 1280x720 virtual stage. The stage is scaled to the 16:9
 * box-fit container, so the reference TV-box layout stays identical on phones,
 * tablets and boxes while the GPU cost stays predictable.
 */
public class SonnetLyricsView extends View {

    private static final float VW = 1280f;
    private static final float VH = 720f;

    private static final int SHOT_IMPACT = 0;
    private static final int SHOT_EDITORIAL = 1;
    private static final int SHOT_FRAGMENT = 2;
    private static final int SHOT_RIBBON = 3;
    private static final int SHOT_MASK = 4;
    private static final int SHOT_POSTER = 5;
    private static final int SHOT_TABLEAU = 6;

    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SUBTLE_COLOR = 0xFFA8C3E0;

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vignettePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint effectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();

    private final Map<Integer, LineLayout> layoutCache = new HashMap<>();
    private int[] lineToParagraph = new int[0];

    private Lyrics lyrics = Lyrics.EMPTY;
    private LyricProgram program =
            LyricProgram.compile(Lyrics.EMPTY, "sonnet");
    private int accentColor = 0xFF4FC3F7;

    private long statePositionMs;
    private long stateUptimeMs = SystemClock.uptimeMillis();
    private boolean playing;

    private int activeIndex = -1;
    private int previousIndex = -1;
    private long transitionStartedMs = SystemClock.uptimeMillis();
    private LyricProgram.Transition transition = LyricProgram.Transition.CAMERA_PULL;
    private float transitionDurationMs = 640f;

    private boolean lowPower;
    private int frameIntervalMs = 16;
    private long lastFrameMs;
    private int frameCounter;

    private Bitmap noiseBitmap;
    private Bitmap halftoneBitmap;

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

    public SonnetLyricsView(Context context) {
        this(context, null);
    }

    public SonnetLyricsView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(TEXT_COLOR);

        smallPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        smallPaint.setTextAlign(Paint.Align.LEFT);
        smallPaint.setColor(SUBTLE_COLOR);

        accentPaint.setStyle(Paint.Style.FILL);
        accentPaint.setColor(accentColor);

        shapePaint.setStyle(Paint.Style.STROKE);
        shapePaint.setStrokeWidth(1.4f);
        shapePaint.setColor(accentColor);

        effectPaint.setStyle(Paint.Style.FILL);

        scrimPaint.setShader(new LinearGradient(0f, VH * 0.32f, 0f, VH,
                0x00000000, 0x7A000000, Shader.TileMode.CLAMP));
        vignettePaint.setShader(new RadialGradient(VW * 0.5f, VH * 0.5f, 820f,
                new int[]{0x00000000, 0x00000000, 0x70000000},
                new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
    }

    // ------------------------------------------------------------------
    // Inputs
    // ------------------------------------------------------------------

    public void setLyrics(Lyrics value) {
        setLyrics(value, "sonnet");
    }

    public void setLyrics(Lyrics value, String seed) {
        lyrics = value == null ? Lyrics.EMPTY : value;
        program = LyricProgram.compile(lyrics, seed);
        lineToParagraph = new int[lyrics.lines.size()];
        for (int i = 0; i < lineToParagraph.length; i++) lineToParagraph[i] = -1;
        for (int p = 0; p < program.paragraphs.size(); p++) {
            for (LyricLine line : program.paragraphs.get(p).lines) {
                for (int i = 0; i < lyrics.lines.size(); i++) {
                    if (lyrics.lines.get(i) == line) {
                        lineToParagraph[i] = p;
                        break;
                    }
                }
            }
        }
        layoutCache.clear();
        activeIndex = -1;
        previousIndex = -1;
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

    /** Low-power preset used on 1-2 GB TV boxes: 30 fps, no grain/halftone. */
    public void setLowPower(boolean value) {
        lowPower = value;
        frameIntervalMs = value ? 33 : 16;
        invalidate();
    }

    public void setPlaybackState(long positionMs, boolean isPlaying) {
        statePositionMs = Math.max(0, positionMs);
        stateUptimeMs = SystemClock.uptimeMillis();
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
        if (playing) return true;
        return SystemClock.uptimeMillis() - transitionStartedMs < (long) transitionDurationMs + 120L;
    }

    private long currentPositionMs() {
        if (!playing) return statePositionMs;
        return statePositionMs + Math.max(0, SystemClock.uptimeMillis() - stateUptimeMs);
    }

    // ------------------------------------------------------------------
    // Drawing
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
        long position = currentPositionMs();
        int index = lyrics.indexAt(position);
        if (index != activeIndex) {
            previousIndex = activeIndex;
            activeIndex = index;
            setTransition(previousIndex, activeIndex);
            transitionStartedMs = now;
        }

        canvas.drawRect(0f, 0f, VW, VH, scrimPaint);
        drawBackgroundDecoration(canvas, now, position);

        float progress = clamp((now - transitionStartedMs) / transitionDurationMs);
        if (previousIndex >= 0 && previousIndex != activeIndex && progress < 1f) {
            drawTransitionOutgoing(canvas, previousIndex, position, progress);
        }
        if (activeIndex >= 0) {
            drawLine(canvas, activeIndex, position, 0.42f + 0.58f * progress,
                    progress, true, true);
        } else if (lyrics.isEmpty()) {
            drawInstrumental(canvas, now);
        } else {
            drawIdle(canvas, position, now);
        }

        drawPostEffects(canvas);
        canvas.restore();
        if (shouldAnimate()) scheduleFrame();
    }

    private void setTransition(int fromIndex, int toIndex) {
        if (fromIndex < 0 || toIndex < 0) {
            transition = LyricProgram.Transition.CAMERA_PULL;
            transitionDurationMs = 520f;
            return;
        }
        int fromParagraph = paragraphOf(fromIndex);
        int toParagraph = paragraphOf(toIndex);
        if (fromParagraph >= 0 && toParagraph >= 0 && fromParagraph != toParagraph
                && fromParagraph < program.paragraphs.size()) {
            transition = program.paragraphs.get(fromParagraph).transitionOut;
            transitionDurationMs = transition == LyricProgram.Transition.MONO_GLITCH
                    ? 460f : transition == LyricProgram.Transition.FAST_BLUR ? 560f : 680f;
        } else {
            transition = LyricProgram.Transition.CAMERA_PULL;
            transitionDurationMs = 360f;
        }
    }

    private void drawTransitionOutgoing(Canvas canvas, int index, long position, float progress) {
        float alpha = (1f - progress) * 0.85f;
        if (transition == LyricProgram.Transition.FAST_BLUR) {
            // Cheap directional blur: three offset copies of the outgoing shot.
            for (int i = 0; i < 3; i++) {
                float offset = (i - 1) * (5f + 9f * progress);
                canvas.save();
                canvas.translate(offset, 0f);
                canvas.scale(1f + i * 0.006f, 1f + i * 0.006f, VW * 0.5f, VH * 0.5f);
                drawLine(canvas, index, position, alpha / 3f, 1f - progress, false, true);
                canvas.restore();
            }
        } else if (transition == LyricProgram.Transition.MONO_GLITCH) {
            drawGlitch(canvas, index, position, alpha, progress);
        } else {
            drawLine(canvas, index, position, alpha, 1f - progress, false, true);
        }
    }

    /** Horizontal slice offsets plus a couple of accent bars, no shaders needed. */
    private void drawGlitch(Canvas canvas, int index, long position, float alpha, float progress) {
        int bands = lowPower ? 5 : 7;
        Random random = new Random((long) (frameCounter / 2) * 31 + activeIndex * 7L);
        for (int band = 0; band < bands; band++) {
            float top = band * VH / bands;
            float bottom = (band + 1) * VH / bands;
            float offset = (random.nextFloat() - 0.5f) * 46f * (0.4f + progress);
            canvas.save();
            canvas.clipRect(0f, top, VW, bottom);
            canvas.translate(offset, 0f);
            drawLine(canvas, index, position, alpha * 0.55f, 1f - progress, false, true);
            canvas.restore();
        }
        effectPaint.setColor(accentColor);
        effectPaint.setAlpha((int) (46 * alpha * 8f));
        for (int i = 0; i < 3; i++) {
            float y = random.nextFloat() * VH;
            canvas.drawRect(0f, y, VW, y + 1.6f, effectPaint);
        }
    }

    private void drawLine(Canvas canvas, int index, long positionMs, float alpha,
                          float cameraProgress, boolean animateIn, boolean withCamera) {
        if (index < 0 || index >= lyrics.lines.size() || alpha <= 0.01f) return;
        LyricLine line = lyrics.lines.get(index);
        int shot = shotFor(index);
        LineLayout layout = layoutFor(index);

        canvas.save();
        applyCamera(canvas, shot, cameraProgress, withCamera);
        drawGhost(canvas, line, shot, alpha);
        switch (shot) {
            case SHOT_FRAGMENT:
                drawFragment(canvas, line, layout, positionMs, alpha, animateIn);
                break;
            case SHOT_RIBBON:
                drawRibbon(canvas, line, layout, positionMs, alpha, animateIn);
                break;
            case SHOT_MASK:
                drawMask(canvas, line, layout, positionMs, alpha, animateIn);
                break;
            case SHOT_POSTER:
                drawPoster(canvas, line, layout, positionMs, alpha, animateIn);
                break;
            case SHOT_EDITORIAL:
                drawEditorial(canvas, index, line, layout, positionMs, alpha, animateIn);
                break;
            case SHOT_TABLEAU:
                drawTableau(canvas, line, layout, positionMs, alpha, animateIn);
                break;
            default:
                drawImpact(canvas, line, layout, positionMs, alpha, animateIn);
                break;
        }
        canvas.restore();
    }

    private void applyCamera(Canvas canvas, int shot, float progress, boolean withCamera) {
        if (!withCamera) return;
        float p = easeOut(clamp(progress));
        switch (shot) {
            case SHOT_IMPACT:
            case SHOT_POSTER:
                float zoom = 0.965f + 0.035f * p;
                canvas.scale(zoom, zoom, VW * 0.5f, VH * 0.5f);
                canvas.translate(0f, (1f - p) * 16f);
                break;
            case SHOT_EDITORIAL:
            case SHOT_FRAGMENT:
                canvas.translate((1f - p) * -36f, 0f);
                break;
            case SHOT_RIBBON:
                canvas.translate((1f - p) * 70f, 0f);
                break;
            default:
                float out = 1.05f - 0.05f * p;
                canvas.scale(out, out, VW * 0.5f, VH * 0.5f);
                canvas.translate(0f, (1f - p) * 20f);
                break;
        }
    }

    // ------------------------------------------------------------------
    // Shot templates
    // ------------------------------------------------------------------

    private void drawImpact(Canvas canvas, LyricLine line, LineLayout layout,
                            long positionMs, float alpha, boolean animateIn) {
        float blockTop = VH * 0.5f - (layout.rows.size() - 1) * 48f;
        textPaint.setTextSize(74f);
        float slot = slotMs(line, layout);
        int charOffset = 0;
        for (int r = 0; r < layout.rows.size(); r++) {
            RowLayout row = layout.rows.get(r);
            float x = (VW - row.width) * 0.5f;
            float y = blockTop + r * 96f;
            drawRowChars(canvas, line, row, charOffset, x, y, positionMs, alpha,
                    animateIn, slot, 24f, 0.06f);
            charOffset += row.text.length();
        }
        float lineWidth = (layout.maxRowWidth + 70f) * appearProgress(line, positionMs, animateIn);
        if (lineWidth > 4f) {
            rect.set((VW - lineWidth) * 0.5f, blockTop + layout.rows.size() * 96f + 6f,
                    (VW + lineWidth) * 0.5f, blockTop + layout.rows.size() * 96f + 9f);
            accentPaint.setAlpha((int) (150 * alpha));
            canvas.drawRoundRect(rect, 2f, 2f, accentPaint);
        }
        drawTranslation(canvas, line, layout, VW * 0.5f, blockTop + layout.rows.size() * 96f + 62f,
                positionMs, alpha, animateIn, true);
    }

    private void drawEditorial(Canvas canvas, int index, LyricLine line, LineLayout layout,
                               long positionMs, float alpha, boolean animateIn) {
        textPaint.setTextSize(58f);
        float left = 178f;
        float top = VH * 0.5f - (layout.rows.size() - 1) * 34f;

        accentPaint.setAlpha((int) (170 * alpha));
        canvas.drawRect(142f, top - 62f, 146f, top + (layout.rows.size() - 1) * 76f + 16f, accentPaint);
        smallPaint.setTextSize(22f);
        smallPaint.setAlpha((int) (170 * alpha));
        canvas.drawText(String.format(Locale.US, "%02d", Math.max(1, index + 1)),
                142f, top - 74f, smallPaint);

        float slot = slotMs(line, layout);
        int charOffset = 0;
        for (int r = 0; r < layout.rows.size(); r++) {
            RowLayout row = layout.rows.get(r);
            drawRowChars(canvas, line, row, charOffset, left, top + r * 76f,
                    positionMs, alpha, animateIn, slot, 16f, 0.02f);
            charOffset += row.text.length();
        }
        drawTranslation(canvas, line, layout, left, top + layout.rows.size() * 76f + 26f,
                positionMs, alpha, animateIn, false);
    }

    private void drawFragment(Canvas canvas, LyricLine line, LineLayout layout,
                              long positionMs, float alpha, boolean animateIn) {
        textPaint.setTextSize(78f);
        float slot = slotMs(line, layout);
        float top = VH * 0.5f - (layout.rows.size() - 1) * 46f;
        RowLayout first = layout.rows.get(0);
        float left = 150f;

        canvas.save();
        canvas.rotate(-3.5f, left + first.width * 0.5f, top);
        int charOffset = 0;
        for (int r = 0; r < layout.rows.size(); r++) {
            RowLayout row = layout.rows.get(r);
            drawRowChars(canvas, line, row, charOffset, left, top + r * 92f,
                    positionMs, alpha, animateIn, slot, 26f, 0.05f);
            charOffset += row.text.length();
        }
        canvas.restore();

        // Two smaller fragments of the same line, like torn paper pieces.
        String fragment = fragmentText(line.text, 0.35f);
        drawRotatedFragment(canvas, fragment, 706f, 214f, 44f, 6.5f, alpha * 0.55f);
        drawRotatedFragment(canvas, fragmentText(line.text, 0.62f), 780f, 468f, 38f, -5f, alpha * 0.42f);
        drawRotatedFragment(canvas, "///", 700f, 356f, 34f, 0f, alpha * 0.30f);
        drawTranslation(canvas, line, layout, left, top + layout.rows.size() * 92f + 30f,
                positionMs, alpha, animateIn, false);
    }

    private void drawRibbon(Canvas canvas, LyricLine line, LineLayout layout,
                            long positionMs, float alpha, boolean animateIn) {
        textPaint.setTextSize(76f);
        float progress = appearProgress(line, positionMs, animateIn);
        float ribbonY = VH * 0.42f;
        accentPaint.setAlpha((int) (52 * alpha));
        canvas.drawRect(-140f + progress * 1560f, ribbonY - 3f,
                160f + progress * 1560f, ribbonY + 3f, accentPaint);
        shapePaint.setAlpha((int) (44 * alpha));
        shapePaint.setStrokeWidth(1.2f);
        canvas.drawLine(-100f + progress * 1560f, ribbonY - 34f,
                260f + progress * 1560f, ribbonY - 34f, shapePaint);
        canvas.drawLine(-100f + progress * 1560f, ribbonY + 34f,
                260f + progress * 1560f, ribbonY + 34f, shapePaint);

        float top = VH * 0.56f - (layout.rows.size() - 1) * 40f;
        float slot = slotMs(line, layout);
        int charOffset = 0;
        for (int r = 0; r < layout.rows.size(); r++) {
            RowLayout row = layout.rows.get(r);
            float x = (VW - row.width) * 0.5f + (1f - progress) * 60f;
            drawRowChars(canvas, line, row, charOffset, x, top + r * 88f,
                    positionMs, alpha, animateIn, slot, 28f, 0.05f);
            charOffset += row.text.length();
        }
        drawTranslation(canvas, line, layout, VW * 0.5f, top + layout.rows.size() * 88f + 18f,
                positionMs, alpha, animateIn, true);
    }

    private void drawMask(Canvas canvas, LyricLine line, LineLayout layout,
                          long positionMs, float alpha, boolean animateIn) {
        textPaint.setTextSize(66f);
        float reveal = appearProgress(line, positionMs, animateIn);
        float top = VH * 0.5f - (layout.rows.size() - 1) * 44f;
        float half = VW * 0.5f;
        float band = half * reveal;
        canvas.save();
        canvas.clipRect(half - band, 0f, half + band, VH);
        float slot = slotMs(line, layout);
        int charOffset = 0;
        for (int r = 0; r < layout.rows.size(); r++) {
            RowLayout row = layout.rows.get(r);
            float x = (VW - row.width) * 0.5f;
            drawRowChars(canvas, line, row, charOffset, x, top + r * 88f,
                    positionMs, alpha, animateIn, slot, 20f, 0.03f);
            charOffset += row.text.length();
        }
        canvas.restore();

        accentPaint.setAlpha((int) (90 * alpha));
        canvas.drawRect(half + band - 2f, top - 60f, half + band + 1f,
                top + layout.rows.size() * 88f + 6f, accentPaint);
        drawTranslation(canvas, line, layout, VW * 0.5f, top + layout.rows.size() * 88f + 22f,
                positionMs, alpha, animateIn, true);
    }

    private void drawPoster(Canvas canvas, LyricLine line, LineLayout layout,
                            long positionMs, float alpha, boolean animateIn) {
        float width = Math.max(920f, layout.maxRowWidth + 220f);
        float height = 176f + (layout.rows.size() - 1) * 70f;
        float left = (VW - width) * 0.5f;
        float top = VH * 0.5f - height * 0.5f - 12f;

        rect.set(left, top, left + width, top + height);
        accentPaint.setAlpha((int) (30 * alpha));
        canvas.drawRoundRect(rect, 10f, 10f, accentPaint);
        shapePaint.setAlpha((int) (110 * alpha));
        shapePaint.setStrokeWidth(1.6f);
        canvas.drawRoundRect(rect, 10f, 10f, shapePaint);

        textPaint.setTextSize(62f);
        float startX = left + 44f;
        float startY = top + 96f;
        float slot = slotMs(line, layout);
        int charOffset = 0;
        for (int r = 0; r < layout.rows.size(); r++) {
            RowLayout row = layout.rows.get(r);
            drawRowChars(canvas, line, row, charOffset, startX, startY + r * 70f,
                    positionMs, alpha, animateIn, slot, 18f, 0.04f);
            charOffset += row.text.length();
        }
        smallPaint.setTextSize(20f);
        smallPaint.setAlpha((int) (130 * alpha));
        String label = String.format(Locale.US, "SONNET / %02d", Math.max(1, activeIndex + 1));
        canvas.drawText(label, left + 44f, top + 38f, smallPaint);
        drawTranslation(canvas, line, layout, left + 44f, top + height - 26f,
                positionMs, alpha, animateIn, false);
    }

    private void drawTableau(Canvas canvas, LyricLine line, LineLayout layout,
                             long positionMs, float alpha, boolean animateIn) {
        textPaint.setTextSize(60f);
        float top = VH * 0.5f - (layout.rows.size() - 1) * 40f;

        long now = SystemClock.uptimeMillis();
        float pulse = 1f + 0.02f * (float) Math.sin(now / 900.0);
        shapePaint.setAlpha((int) (42 * alpha));
        shapePaint.setStrokeWidth(1.4f);
        canvas.drawCircle(VW * 0.5f, VH * 0.52f, 196f * pulse, shapePaint);
        shapePaint.setAlpha((int) (24 * alpha));
        canvas.drawCircle(VW * 0.5f, VH * 0.52f, 240f * pulse, shapePaint);

        float slot = slotMs(line, layout);
        int charOffset = 0;
        for (int r = 0; r < layout.rows.size(); r++) {
            RowLayout row = layout.rows.get(r);
            float x = (VW - row.width) * 0.5f;
            drawRowChars(canvas, line, row, charOffset, x, top + r * 84f,
                    positionMs, alpha, animateIn, slot, 26f, 0.04f);
            charOffset += row.text.length();
        }
        drawTranslation(canvas, line, layout, VW * 0.5f, top + layout.rows.size() * 84f + 18f,
                positionMs, alpha, animateIn, true);
    }

    // ------------------------------------------------------------------
    // Text / glyph drawing
    // ------------------------------------------------------------------

    private void drawRowChars(Canvas canvas, LyricLine line, RowLayout row, int charOffset,
                              float baseX, float baselineY, long positionMs, float alpha,
                              boolean animateIn, float slotMs, float risePx,
                              float scaleAmount) {
        textPaint.setColor(TEXT_COLOR);
        long elapsed = positionMs - line.startMs;
        float appear = appearProgress(line, positionMs, animateIn);
        for (int i = 0; i < row.text.length(); i++) {
            int index = charOffset + i;
            float p = animateIn ? clamp((elapsed - index * slotMs) / 380f) : 1f;
            float x = baseX + row.xs[i];
            if (p <= 0f) {
                float dim = alpha * appear * 0.18f;
                if (dim <= 0.01f) continue;
                textPaint.setAlpha((int) (dim * 255));
                canvas.drawText(row.text, i, i + 1, x, baselineY, textPaint);
                continue;
            }
            float glyphAlpha = alpha * appear * (0.18f + 0.82f * p);
            if (glyphAlpha <= 0.01f) continue;
            textPaint.setAlpha((int) (glyphAlpha * 255));
            float y = baselineY + (1f - p) * risePx;
            if (scaleAmount > 0f) {
                float s = 1f + scaleAmount * (1f - easeOut(p));
                canvas.save();
                canvas.scale(s, s, x, baselineY);
                canvas.drawText(row.text, i, i + 1, x, y, textPaint);
                canvas.restore();
            } else {
                canvas.drawText(row.text, i, i + 1, x, y, textPaint);
            }
        }
    }

    private void drawTranslation(Canvas canvas, LyricLine line, LineLayout layout,
                                 float x, float y, long positionMs, float alpha,
                                 boolean animateIn, boolean centered) {
        if (line.translation == null) return;
        float appear = appearProgress(line, positionMs, animateIn);
        if (appear < 0.35f) return;
        smallPaint.setTextSize(30f);
        smallPaint.setColor(SUBTLE_COLOR);
        smallPaint.setAlpha((int) (alpha * appear * 190));
        String text = line.translation;
        float width = smallPaint.measureText(text);
        float drawX = centered ? x - width * 0.5f : x;
        canvas.drawText(text, drawX, y, smallPaint);

        smallPaint.setTextSize(12f);
        smallPaint.setAlpha((int) (alpha * appear * 90));
        float lineWidth = centered ? Math.min(width, 520f) : Math.min(width, 620f);
        float lineX = centered ? x - lineWidth * 0.5f : x;
        canvas.drawRect(lineX, y + 8f, lineX + lineWidth, y + 9f, smallPaint);
    }

    private void drawGhost(Canvas canvas, LyricLine line, int shot, float alpha) {
        String text = line.text;
        if (text == null || text.isEmpty()) return;
        if (text.length() > 12) text = text.substring(0, 12);
        float baseAlpha = shot == SHOT_POSTER ? 0.10f : shot == SHOT_TABLEAU ? 0.05f : 0.07f;
        float size = shot == SHOT_FRAGMENT ? 210f : shot == SHOT_POSTER ? 150f : 178f;
        float y = shot == SHOT_EDITORIAL ? VH * 0.28f : VH * 0.34f;
        textPaint.setTextSize(size);
        float width = textPaint.measureText(text);
        float x = shot == SHOT_EDITORIAL ? 150f : (VW - width) * 0.5f;

        // Light chromatic split on the giant text (cheap RGB-shift analogue).
        if (!lowPower) {
            textPaint.setAlpha((int) (baseAlpha * alpha * 130));
            textPaint.setColor(0xFFFF3B5C);
            canvas.drawText(text, x - 2.4f, y, textPaint);
            textPaint.setColor(0xFF35F2E5);
            canvas.drawText(text, x + 2.4f, y, textPaint);
        }
        textPaint.setColor(TEXT_COLOR);
        textPaint.setAlpha((int) (baseAlpha * alpha * 255));
        canvas.drawText(text, x, y, textPaint);
    }

    private void drawRotatedFragment(Canvas canvas, String text, float x, float y,
                                     float size, float rotation, float alpha) {
        if (text == null || text.isEmpty()) return;
        textPaint.setTextSize(size);
        textPaint.setColor(TEXT_COLOR);
        textPaint.setAlpha((int) (clamp(alpha) * 255));
        canvas.save();
        canvas.rotate(rotation, x, y);
        canvas.drawText(text, x, y, textPaint);
        canvas.restore();
    }

    // ------------------------------------------------------------------
    // Background decoration / post effects
    // ------------------------------------------------------------------

    private void drawBackgroundDecoration(Canvas canvas, long now, long positionMs) {
        int paragraph = paragraphOf(Math.max(0, activeIndex));
        int variant = paragraph < 0 ? 0 : Math.floorMod(paragraph, 8);
        float alpha = 0.55f;
        switch (variant) {
            case 0:
                drawBotanical(canvas, now, alpha);
                break;
            case 1:
                drawCelestial(canvas, now, alpha);
                break;
            case 2:
                drawCraft(canvas, alpha);
                break;
            case 3:
                drawFlora(canvas, now, alpha);
                break;
            case 4:
                drawKinetic(canvas, now, positionMs, alpha);
                break;
            case 5:
                drawLandscape(canvas, alpha);
                break;
            case 6:
                drawMarine(canvas, now, alpha);
                break;
            default:
                drawMusicStaff(canvas, now, alpha);
                break;
        }
    }

    private void drawBotanical(Canvas canvas, long now, float alpha) {
        shapePaint.setStrokeWidth(2.2f);
        shapePaint.setAlpha((int) (46 * alpha));
        float sway = (float) Math.sin(now / 2600.0) * 12f;
        path.reset();
        path.moveTo(96f, VH + 20f);
        path.quadTo(86f + sway, VH * 0.62f, 150f + sway, VH * 0.30f);
        canvas.drawPath(path, shapePaint);
        for (int i = 0; i < 4; i++) {
            float t = 0.25f + i * 0.18f;
            float cx = 96f + (150f - 96f) * t + sway * t;
            float cy = VH + 20f - (VH * 0.70f) * t;
            canvas.save();
            canvas.rotate(-36f + i * 22f, cx, cy);
            rect.set(cx - 4f, cy - 40f, cx + 44f, cy - 6f);
            shapePaint.setAlpha((int) (40 * alpha));
            canvas.drawOval(rect, shapePaint);
            canvas.restore();
        }
        shapePaint.setAlpha((int) (34 * alpha));
        path.reset();
        path.moveTo(VW - 110f, VH + 20f);
        path.quadTo(VW - 150f - sway, VH * 0.55f, VW - 210f - sway, VH * 0.26f);
        canvas.drawPath(path, shapePaint);
    }

    private void drawCelestial(Canvas canvas, long now, float alpha) {
        float angle = now / 5200f;
        shapePaint.setStrokeWidth(1.3f);
        shapePaint.setAlpha((int) (34 * alpha));
        canvas.drawCircle(VW * 0.5f, VH * 0.5f, 268f, shapePaint);
        canvas.drawCircle(VW * 0.5f, VH * 0.5f, 196f, shapePaint);
        accentPaint.setAlpha((int) (54 * alpha));
        for (int i = 0; i < 5; i++) {
            double a = angle + i * Math.PI * 2 / 5;
            float x = VW * 0.5f + (float) Math.cos(a) * 268f;
            float y = VH * 0.5f + (float) Math.sin(a) * 268f;
            canvas.drawCircle(x, y, 4.2f, accentPaint);
        }
        shapePaint.setAlpha((int) (26 * alpha));
        for (int i = 0; i < 14; i++) {
            float x = (float) ((i * 97) % 1280);
            float y = (float) ((i * 53) % 720);
            canvas.drawCircle(x, y, 1.6f, shapePaint);
        }
    }

    private void drawCraft(Canvas canvas, float alpha) {
        shapePaint.setStrokeWidth(1f);
        shapePaint.setAlpha((int) (26 * alpha));
        for (int x = 0; x <= VW; x += 64) canvas.drawLine(x, 0f, x, VH, shapePaint);
        for (int y = 0; y <= VH; y += 64) canvas.drawLine(0f, y, VW, y, shapePaint);
        accentPaint.setAlpha((int) (34 * alpha));
        rect.set(112f, 120f, 240f, 248f);
        canvas.drawRect(rect, accentPaint);
        rect.set(1040f, 470f, 1160f, 590f);
        canvas.drawRect(rect, accentPaint);
        shapePaint.setAlpha((int) (60 * alpha));
        canvas.drawRect(rect, shapePaint);
    }

    private void drawFlora(Canvas canvas, long now, float alpha) {
        accentPaint.setAlpha((int) (34 * alpha));
        float spin = now / 6400f;
        for (int i = 0; i < 9; i++) {
            float x = 90f + (i * 137) % 1120;
            float y = 80f + (i * 89) % 580;
            canvas.save();
            canvas.rotate(spin * 40f + i * 21f, x, y);
            rect.set(x - 26f, y - 7f, x + 26f, y + 7f);
            canvas.drawOval(rect, accentPaint);
            canvas.restore();
        }
    }

    private void drawKinetic(Canvas canvas, long now, long positionMs, float alpha) {
        accentPaint.setAlpha((int) (30 * alpha));
        float shift = (now / 6f) % 220f;
        for (int i = 0; i < 9; i++) {
            float y = 70f + i * 74f;
            float width = 160f + (i * 53 % 420);
            float x = (VW - width) * (((i * 37) % 100) / 100f) - shift;
            canvas.drawRect(x, y, x + width, y + 3f, accentPaint);
        }
        shapePaint.setAlpha((int) (40 * alpha));
        shapePaint.setStrokeWidth(2f);
        canvas.drawLine(0f, VH * 0.5f, VW, VH * 0.5f, shapePaint);
    }

    private void drawLandscape(Canvas canvas, float alpha) {
        shapePaint.setStrokeWidth(2f);
        shapePaint.setAlpha((int) (38 * alpha));
        path.reset();
        path.moveTo(0f, VH - 60f);
        path.quadTo(VW * 0.25f, VH - 230f, VW * 0.52f, VH - 70f);
        path.quadTo(VW * 0.76f, VH - 210f, VW, VH - 60f);
        canvas.drawPath(path, shapePaint);
        shapePaint.setAlpha((int) (24 * alpha));
        path.reset();
        path.moveTo(0f, VH - 10f);
        path.quadTo(VW * 0.36f, VH - 150f, VW * 0.68f, VH - 20f);
        path.quadTo(VW * 0.85f, VH - 90f, VW, VH - 16f);
        canvas.drawPath(path, shapePaint);
    }

    private void drawMarine(Canvas canvas, long now, float alpha) {
        shapePaint.setStrokeWidth(1.8f);
        for (int row = 0; row < 4; row++) {
            shapePaint.setAlpha((int) ((36 - row * 6) * alpha));
            path.reset();
            float baseY = VH * 0.58f + row * 34f;
            float phase = now / 900f + row * 1.4f;
            path.moveTo(0f, baseY);
            for (int x = 0; x <= VW; x += 40) {
                float y = baseY + (float) Math.sin(x / 130.0 + phase) * (9f + row * 2f);
                path.lineTo(x, y);
            }
            canvas.drawPath(path, shapePaint);
        }
    }

    private void drawMusicStaff(Canvas canvas, long now, float alpha) {
        shapePaint.setStrokeWidth(1.2f);
        shapePaint.setAlpha((int) (30 * alpha));
        float top = VH * 0.62f;
        for (int i = 0; i < 5; i++) {
            canvas.drawLine(120f, top + i * 15f, VW - 120f, top + i * 15f, shapePaint);
        }
        accentPaint.setAlpha((int) (70 * alpha));
        int notes = 6;
        for (int i = 0; i < notes; i++) {
            float phase = (now / 1400f + i * 0.7f) % 1f;
            float x = 140f + phase * (VW - 280f);
            float y = top + ((i * 2) % 5) * 15f;
            canvas.drawOval(x - 7f, y - 5f, x + 7f, y + 5f, accentPaint);
            canvas.drawRect(x + 6f, y - 34f, x + 8.4f, y, accentPaint);
        }
    }

    private void drawPostEffects(Canvas canvas) {
        if (!lowPower) {
            int shot = shotFor(activeIndex);
            if (shot == SHOT_POSTER || shot == SHOT_FRAGMENT) {
                drawHalftone(canvas, 1f);
            }
            drawGrain(canvas);
        }
        canvas.drawRect(0f, 0f, VW, VH, vignettePaint);
        drawFrameMarks(canvas);
    }

    private void drawGrain(Canvas canvas) {
        if (noiseBitmap == null) noiseBitmap = buildNoiseBitmap();
        if (noiseBitmap == null) return;
        effectPaint.setAlpha(13);
        int offsetX = (frameCounter * 7) % noiseBitmap.getWidth();
        int offsetY = (frameCounter * 11) % noiseBitmap.getHeight();
        for (int y = -offsetY; y < VH; y += noiseBitmap.getHeight()) {
            for (int x = -offsetX; x < VW; x += noiseBitmap.getWidth()) {
                canvas.drawBitmap(noiseBitmap, x, y, effectPaint);
            }
        }
    }

    private void drawHalftone(Canvas canvas, float alpha) {
        if (halftoneBitmap == null) halftoneBitmap = buildHalftoneBitmap();
        if (halftoneBitmap == null) return;
        effectPaint.setAlpha((int) (10 * alpha));
        for (int y = 0; y < VH; y += halftoneBitmap.getHeight()) {
            for (int x = 0; x < VW; x += halftoneBitmap.getWidth()) {
                canvas.drawBitmap(halftoneBitmap, x, y, effectPaint);
            }
        }
    }

    private void drawFrameMarks(Canvas canvas) {
        shapePaint.setAlpha(34);
        shapePaint.setStrokeWidth(1.2f);
        float inset = 22f;
        float len = 34f;
        canvas.drawLine(inset, inset, inset + len, inset, shapePaint);
        canvas.drawLine(inset, inset, inset, inset + len, shapePaint);
        canvas.drawLine(VW - inset, inset, VW - inset - len, inset, shapePaint);
        canvas.drawLine(VW - inset, inset, VW - inset, inset + len, shapePaint);
        canvas.drawLine(inset, VH - inset, inset + len, VH - inset, shapePaint);
        canvas.drawLine(inset, VH - inset, inset, VH - inset - len, shapePaint);
        canvas.drawLine(VW - inset, VH - inset, VW - inset - len, VH - inset, shapePaint);
        canvas.drawLine(VW - inset, VH - inset, VW - inset, VH - inset - len, shapePaint);
    }

    private Bitmap buildNoiseBitmap() {
        try {
            int w = 160;
            int h = 90;
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
            Random random = new Random(0xC0FFEE);
            int[] pixels = new int[w * h];
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = random.nextInt(256) << 24;
            }
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
    // Idle / instrumental
    // ------------------------------------------------------------------

    private void drawIdle(Canvas canvas, long positionMs, long now) {
        if (lyrics.lines.isEmpty()) return;
        LyricLine next = lyrics.lines.get(0);
        float pulse = 0.5f + 0.5f * (float) Math.sin(now / 700.0);
        accentPaint.setAlpha((int) (40 + 50 * pulse));
        canvas.drawCircle(VW * 0.5f, VH * 0.52f, 10f + 4f * pulse, accentPaint);
        textPaint.setTextSize(44f);
        textPaint.setColor(SUBTLE_COLOR);
        textPaint.setAlpha(120);
        float width = textPaint.measureText(next.text);
        canvas.drawText(next.text, (VW - width) * 0.5f, VH * 0.62f, textPaint);
    }

    private void drawInstrumental(Canvas canvas, long now) {
        if (!playing) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(now / 800.0);
            shapePaint.setAlpha((int) (26 + 24 * pulse));
            shapePaint.setStrokeWidth(1.4f);
            canvas.drawCircle(VW * 0.5f, VH * 0.5f, 120f + 18f * pulse, shapePaint);
            shapePaint.setAlpha((int) (18 + 14 * pulse));
            canvas.drawCircle(VW * 0.5f, VH * 0.5f, 170f + 26f * pulse, shapePaint);
            return;
        }
        drawMusicStaff(canvas, now, 1f);
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private int paragraphOf(int lineIndex) {
        if (lineIndex < 0 || lineIndex >= lineToParagraph.length) return -1;
        return lineToParagraph[lineIndex];
    }

    private int shotFor(int lineIndex) {
        int paragraph = paragraphOf(lineIndex);
        if (paragraph >= 0 && paragraph < program.paragraphs.size()) {
            switch (program.paragraphs.get(paragraph).shot) {
                case EDITORIAL_COLUMN:
                    return SHOT_EDITORIAL;
                case FRAGMENT_COLLAGE:
                    return SHOT_FRAGMENT;
                case TRACKING_RIBBON:
                    return SHOT_RIBBON;
                case MASK_REVEAL:
                    return SHOT_MASK;
                case POSTER_BLOCKS:
                    return SHOT_POSTER;
                case QUIET_TABLEAU:
                    return SHOT_TABLEAU;
                default:
                    return SHOT_IMPACT;
            }
        }
        return Math.abs(lineIndex / 2) % 3;
    }

    private float appearProgress(LyricLine line, long positionMs, boolean animateIn) {
        if (!animateIn) return 1f;
        return easeOut(clamp((positionMs - line.startMs) / 420f));
    }

    /** Time allotted to each character so a line fills its own duration. */
    private static float slotMs(LyricLine line, LineLayout layout) {
        long duration = Math.max(700L, line.endMs - line.startMs);
        float chars = Math.max(1, layout.totalChars);
        return Math.max(70f, Math.min(260f, duration * 0.55f / chars));
    }

    private LineLayout layoutFor(int index) {
        LineLayout cached = layoutCache.get(index);
        if (cached != null) return cached;

        int shot = shotFor(index);
        float textSize = shot == SHOT_IMPACT ? 74f
                : shot == SHOT_EDITORIAL ? 58f
                : shot == SHOT_FRAGMENT ? 78f
                : shot == SHOT_RIBBON ? 76f
                : shot == SHOT_MASK ? 66f
                : shot == SHOT_POSTER ? 62f : 60f;
        float maxWidth = shot == SHOT_EDITORIAL ? 720f
                : shot == SHOT_POSTER ? 860f
                : shot == SHOT_FRAGMENT ? 620f : 980f;
        textPaint.setTextSize(textSize);

        String source = index >= 0 && index < lyrics.lines.size()
                ? lyrics.lines.get(index).text : "";
        List<String> rows = wrapText(source, textPaint, maxWidth, shot == SHOT_POSTER ? 2 : 3);
        List<RowLayout> rowLayouts = new ArrayList<>(rows.size());
        float maxRowWidth = 0f;
        for (String row : rows) {
            float[] xs = new float[row.length()];
            float cursor = 0f;
            for (int i = 0; i < row.length(); i++) {
                xs[i] = cursor;
                cursor += textPaint.measureText(row, i, i + 1) + 1.5f;
            }
            float width = Math.max(0f, cursor - 1.5f);
            maxRowWidth = Math.max(maxRowWidth, width);
            rowLayouts.add(new RowLayout(row, xs, width));
        }
        int totalChars = 0;
        for (RowLayout row : rowLayouts) totalChars += row.text.length();
        LineLayout layout = new LineLayout(shot, rowLayouts, maxRowWidth, totalChars);
        layoutCache.put(index, layout);
        return layout;
    }

    private static List<String> wrapText(String text, Paint paint, float maxWidth, int maxRows) {
        List<String> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            rows.add("");
            return rows;
        }
        String value = text.trim();
        int start = 0;
        while (start < value.length() && rows.size() < maxRows) {
            int count = paint.breakText(value, start, value.length(), true, maxWidth, null);
            if (count <= 0) count = 1;
            int end = Math.min(value.length(), start + count);
            if (end < value.length()) {
                int floor = start + Math.max(1, count / 2);
                for (int k = end; k > floor; k--) {
                    char c = value.charAt(k - 1);
                    if (c == ' ' || c == ',' || c == '.' || c == ';'
                            || c == '\u3001' || c == '\u3002' || c == '\uFF0C') {
                        end = k;
                        break;
                    }
                }
            }
            rows.add(value.substring(start, end).trim());
            start = end;
            while (start < value.length() && value.charAt(start) == ' ') start++;
        }
        return rows;
    }

    private static String fragmentText(String text, float fraction) {
        if (text == null || text.isEmpty()) return "";
        int start = Math.min(text.length() - 1, Math.max(0, (int) (text.length() * fraction)));
        int end = Math.min(text.length(), start + 7);
        if (end - start < 2) start = Math.max(0, end - 2);
        return text.substring(start, end);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float easeOut(float value) {
        float inverse = 1f - value;
        return 1f - inverse * inverse * inverse;
    }

    private static final class RowLayout {
        final String text;
        final float[] xs;
        final float width;

        RowLayout(String text, float[] xs, float width) {
            this.text = text;
            this.xs = xs;
            this.width = width;
        }
    }

    private static final class LineLayout {
        final int shot;
        final List<RowLayout> rows;
        final float maxRowWidth;
        final int totalChars;

        LineLayout(int shot, List<RowLayout> rows, float maxRowWidth, int totalChars) {
            this.shot = shot;
            this.rows = rows;
            this.maxRowWidth = maxRowWidth;
            this.totalChars = totalChars;
        }
    }
}
