package com.airmusic.player.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.Nullable;

import com.airmusic.player.lyrics.LyricLine;
import com.airmusic.player.lyrics.Lyrics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A lightweight, native take on Folia's "Sonnet" lyric visualiser.
 *
 * <p>Everything is laid out in a fixed 1280x720 virtual space and scaled to
 * whatever size the 16:9 box-fit container gives us, so the TV box reference
 * layout is identical on phones and tablets. Three shot templates are rotated
 * per two lines, with per-character entrance motion and a short cross-fade /
 * camera pull between shots. No WebGL, no WebView: just hardware-accelerated
 * TextView drawing, which keeps the Amlogic S905L3A class of box smooth.
 */
public class SonnetLyricsView extends View {

    private static final float VW = 1280f;
    private static final float VH = 720f;

    private static final int SHOT_IMPACT = 0;
    private static final int SHOT_EDITORIAL = 1;
    private static final int SHOT_TABLEAU = 2;

    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SUBTLE_COLOR = 0xFFA8C3E0;

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final Map<Integer, LineLayout> layoutCache = new HashMap<>();

    private Lyrics lyrics = Lyrics.EMPTY;
    private int accentColor = 0xFF4FC3F7;

    private long statePositionMs;
    private long stateUptimeMs = SystemClock.uptimeMillis();
    private boolean playing;

    private int activeIndex = -1;
    private int previousIndex = -1;
    private long transitionStartedMs = SystemClock.uptimeMillis();
    private boolean previousWasDifferentShot;

    private final Choreographer choreographer = Choreographer.getInstance();
    private boolean frameScheduled;
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            frameScheduled = false;
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

        scrimPaint.setShader(new LinearGradient(0f, VH * 0.35f, 0f, VH,
                0x00000000, 0x7A000000, Shader.TileMode.CLAMP));
    }

    // ------------------------------------------------------------------
    // Inputs
    // ------------------------------------------------------------------

    public void setLyrics(Lyrics value) {
        lyrics = value == null ? Lyrics.EMPTY : value;
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

    /** Called from the activity's state listener (roughly every 500 ms). */
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
        long now = SystemClock.uptimeMillis();
        return now - transitionStartedMs < 900;
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

        canvas.drawRect(0f, 0f, VW, VH, scrimPaint);

        long now = SystemClock.uptimeMillis();
        long position = currentPositionMs();
        int index = lyrics.indexAt(position);
        if (index != activeIndex) {
            previousIndex = activeIndex;
            previousWasDifferentShot = previousIndex >= 0 && index >= 0
                    && shotFor(previousIndex) != shotFor(index);
            activeIndex = index;
            transitionStartedMs = now;
        }

        if (lyrics.isEmpty()) {
            drawEmpty(canvas, now);
        } else {
            float transitionMs = previousWasDifferentShot ? 700f : 380f;
            float progress = clamp((now - transitionStartedMs) / transitionMs);
            if (previousIndex >= 0 && previousIndex != activeIndex && progress < 1f) {
                drawLine(canvas, previousIndex, position, (1f - progress) * 0.85f,
                        1f - progress, false, previousWasDifferentShot);
            }
            if (activeIndex >= 0) {
                drawLine(canvas, activeIndex, position, 0.45f + 0.55f * progress,
                        progress, true, true);
            } else {
                drawIdle(canvas, position, now);
            }
        }

        canvas.restore();
        if (shouldAnimate()) scheduleFrame();
    }

    private void drawLine(Canvas canvas, int index, long positionMs, float alpha,
                          float cameraProgress, boolean animateIn, boolean withCamera) {
        if (index < 0 || index >= lyrics.lines.size() || alpha <= 0.01f) return;
        LyricLine line = lyrics.lines.get(index);
        LineLayout layout = layoutFor(index);
        int shot = layout.shot;

        canvas.save();
        if (withCamera) {
            float p = easeOut(clamp(cameraProgress));
            if (shot == SHOT_IMPACT) {
                float zoom = 0.97f + 0.03f * p;
                canvas.scale(zoom, zoom, VW * 0.5f, VH * 0.5f);
                canvas.translate(0f, (1f - p) * 18f);
            } else if (shot == SHOT_EDITORIAL) {
                canvas.translate((1f - p) * -34f, 0f);
            } else {
                float zoom = 1.05f - 0.05f * p;
                canvas.scale(zoom, zoom, VW * 0.5f, VH * 0.5f);
                canvas.translate(0f, (1f - p) * 22f);
            }
        }

        if (shot == SHOT_IMPACT) drawImpact(canvas, line, layout, positionMs, alpha, animateIn);
        else if (shot == SHOT_EDITORIAL) drawEditorial(canvas, index, line, layout, positionMs, alpha, animateIn);
        else drawTableau(canvas, line, layout, positionMs, alpha, animateIn);

        canvas.restore();
    }

    private void drawImpact(Canvas canvas, LyricLine line, LineLayout layout,
                            long positionMs, float alpha, boolean animateIn) {
        float blockTop = VH * 0.5f - (layout.rows.size() - 1) * 48f;
        drawGhost(canvas, layout, alpha * 0.055f);
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

        float lineWidth = (layout.maxRowWidth + 70f) * clamp(appearProgress(line, positionMs, animateIn));
        if (lineWidth > 4f) {
            rect.set((VW - lineWidth) * 0.5f, blockTop + layout.rows.size() * 96f + 6f,
                    (VW + lineWidth) * 0.5f, blockTop + layout.rows.size() * 96f + 9f);
            accentPaint.setAlpha((int) (150 * alpha));
            canvas.drawRoundRect(rect, 2f, 2f, accentPaint);
        }
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
    }

    private void drawTableau(Canvas canvas, LyricLine line, LineLayout layout,
                             long positionMs, float alpha, boolean animateIn) {
        textPaint.setTextSize(64f);
        float top = VH * 0.5f - (layout.rows.size() - 1) * 42f;

        long now = SystemClock.uptimeMillis();
        float pulse = 1f + 0.02f * (float) Math.sin(now / 900.0);
        shapePaint.setAlpha((int) (34 * alpha));
        shapePaint.setStrokeWidth(1.4f);
        canvas.drawCircle(VW * 0.5f, VH * 0.52f, 196f * pulse, shapePaint);
        shapePaint.setAlpha((int) (20 * alpha));
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
    }

    /** Draws one row character by character so each glyph can enter on its own. */
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
                // Upcoming glyphs stay faintly visible so the line has some
                // structure before the singer reaches each character.
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

    /** Time allotted to each character so a line fills its own duration. */
    private static float slotMs(LyricLine line, LineLayout layout) {
        long duration = Math.max(700L, line.endMs - line.startMs);
        float chars = Math.max(1, layout.totalChars);
        return Math.max(70f, Math.min(260f, duration * 0.55f / chars));
    }

    private void drawGhost(Canvas canvas, LineLayout layout, float alpha) {
        if (layout.rows.isEmpty()) return;
        String text = layout.rows.get(0).text;
        if (text.length() > 10) text = text.substring(0, 10);
        textPaint.setTextSize(176f);
        textPaint.setAlpha((int) (alpha * 255));
        float width = textPaint.measureText(text);
        canvas.drawText(text, (VW - width) * 0.5f, VH * 0.34f, textPaint);
    }

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

    private void drawEmpty(Canvas canvas, long now) {
        float pulse = 0.5f + 0.5f * (float) Math.sin(now / 800.0);
        shapePaint.setAlpha((int) (26 + 24 * pulse));
        shapePaint.setStrokeWidth(1.4f);
        canvas.drawCircle(VW * 0.5f, VH * 0.5f, 120f + 18f * pulse, shapePaint);
        shapePaint.setAlpha((int) (18 + 14 * pulse));
        canvas.drawCircle(VW * 0.5f, VH * 0.5f, 170f + 26f * pulse, shapePaint);
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private int shotFor(int index) {
        return Math.abs(index / 2) % 3;
    }

    private float appearProgress(LyricLine line, long positionMs, boolean animateIn) {
        if (!animateIn) return 1f;
        return easeOut(clamp((positionMs - line.startMs) / 420f));
    }

    private LineLayout layoutFor(int index) {
        LineLayout cached = layoutCache.get(index);
        if (cached != null) return cached;

        int shot = shotFor(index);
        float textSize = shot == SHOT_IMPACT ? 74f : shot == SHOT_EDITORIAL ? 58f : 64f;
        float maxWidth = shot == SHOT_EDITORIAL ? 760f : 940f;
        textPaint.setTextSize(textSize);

        String source = index >= 0 && index < lyrics.lines.size()
                ? lyrics.lines.get(index).text : "";
        List<String> rows = wrapText(source, textPaint, maxWidth, 3);
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
        if (start < value.length() && !rows.isEmpty()) {
            int last = rows.size() - 1;
            String merged = rows.get(last) + value.substring(start).trim();
            int count = paint.breakText(merged, 0, merged.length(), true, maxWidth, null);
            rows.set(last, merged.substring(0, Math.max(1, count)));
        }
        return rows;
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
