package com.airmusic.player.sonnet;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.Nullable;

import com.airmusic.player.lyrics.Lyrics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * OpenGL post-processing layer for the lyric stage.
 *
 * <p>The existing Canvas stage is rendered into an offscreen bitmap at 960x540
 * (low-power boxes) or 1280x720, uploaded as a texture, blurred with a
 * separable Gaussian during transitions, then composited through a fragment
 * shader that reproduces Folia's print stack: radial lens distortion,
 * chromatic dispersion, vignette, film grain and contrast.
 */
public class LyricsGlView extends GLSurfaceView {

    private final SonnetStageView scene;
    private Bitmap sceneBitmap;
    private Canvas sceneCanvas;
    private final Renderer renderer;
    private final Choreographer choreographer = Choreographer.getInstance();
    private boolean frameScheduled;
    private long lastFrameMs;
    private int frameIntervalMs;
    private boolean lowPower;

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
            requestRender();
            if (scene.wantsAnimationFrames()) scheduleFrame();
        }
    };

    public LyricsGlView(Context context) {
        this(context, null);
    }

    public LyricsGlView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        // The GL layer must stay *behind* the window, otherwise the surface is
        // composited over the window and the header (back button / title) can
        // never be seen. LyricsActivity therefore keeps a transparent window
        // background and lets the GL scene supply the backdrop.
        setZOrderOnTop(false);
        setPreserveEGLContextOnPause(true);

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        String hardware = (Build.HARDWARE + " " + Build.MODEL + " " + Build.BOARD)
                .toLowerCase(Locale.US);
        lowPower = (am != null && am.isLowRamDevice())
                || hardware.contains("s905")
                || hardware.contains("amlogic")
                || hardware.contains("cm311");
        frameIntervalMs = lowPower ? 20 : 16;
        int width = lowPower ? 800 : 1280;
        int height = lowPower ? 450 : 720;
        sceneBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        sceneCanvas = new Canvas(sceneBitmap);
        scene = new SonnetStageView(context);
        scene.setLowPower(lowPower);
        scene.setCanvasPostEffectsEnabled(false);
        scene.setGlMode(true);
        renderer = new Renderer(width, height);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        scene.setViewport(1280f, 1280f * height / (float) width, width, height);
    }

    // ------------------------------------------------------------------
    // API forwarded to the Canvas scene
    // ------------------------------------------------------------------

    public void setLyrics(Lyrics lyrics, String seed) {
        post(() -> {
            scene.setLyrics(lyrics, seed);
            scheduleFrame();
        });
    }

    public void setLyrics(Lyrics lyrics) {
        setLyrics(lyrics, "sonnet");
    }

    public void setHints(List<String> hints) {
        post(() -> scene.setHints(hints));
    }

    public void setOnsets(List<Long> onsets) {
        post(() -> {
            scene.setOnsets(onsets);
            scheduleFrame();
        });
    }

    public void setAccentColor(int color) {
        post(() -> scene.setAccentColor(color));
    }

    public void setBackgroundArt(Bitmap art) {
        post(() -> scene.setBackgroundArt(art));
    }

    /** Fades the lyric content (not the backdrop) for track-change transitions. */
    public void setContentAlpha(float alpha) {
        post(() -> {
            scene.setContentAlpha(alpha);
            // Make sure a frame lands even when the scene is otherwise idle.
            scheduleFrame();
        });
    }

    public void setPlaybackState(long positionMs, boolean playing) {
        post(() -> {
            scene.setPlaybackState(positionMs, playing);
            scheduleFrame();
        });
    }

    public void setLowPower(boolean value) {
        lowPower = value;
        frameIntervalMs = value ? 33 : 16;
        post(() -> scene.setLowPower(value));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        scheduleFrame();
    }

    /**
     * Caps the surface buffer at 1280x720 and scales the layer up to fill the
     * screen. The compositor scale is cheap, while swapping an 8 MB 1080p
     * buffer every frame is what limits this box to ~20 fps.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = Math.max(1, MeasureSpec.getSize(widthMeasureSpec));
        int height = Math.max(1, MeasureSpec.getSize(heightMeasureSpec));
        float scale = Math.min(1f, Math.min(1280f / width, 720f / height));
        int measuredWidth = Math.max(1, Math.round(width * scale));
        int measuredHeight = Math.max(1, Math.round(height * scale));
        setMeasuredDimension(measuredWidth, measuredHeight);
        setPivotX(0f);
        setPivotY(0f);
        setScaleX(width / (float) measuredWidth);
        setScaleY(height / (float) measuredHeight);
        // The scene is rendered at the same shape as the screen, so the lyric
        // composition adapts to 4:3 / ultra-wide panels instead of being
        // letterboxed inside a fixed 16:9 frame.
        resizeScene(measuredWidth, measuredHeight);
    }

    /**
     * Rebuilds the offscreen scene at a new pixel size. Runs on a background
     * ticket because the texture objects belong to the GL thread.
     */
    private void resizeScene(int pixelWidth, int pixelHeight) {
        if (pixelWidth <= 0 || pixelHeight <= 0) return;
        if (pixelWidth == sceneBitmap.getWidth() && pixelHeight == sceneBitmap.getHeight()) {
            return;
        }
        final int width = pixelWidth;
        final int height = pixelHeight;
        queueEvent(() -> {
            try {
                sceneBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                sceneCanvas = new Canvas(sceneBitmap);
                scene.setViewport(1280f, 1280f * height / (float) width, width, height);
                renderer.setSceneSize(width, height);
                requestRender();
            } catch (Throwable ignored) {
            }
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        frameScheduled = false;
        choreographer.removeFrameCallback(frameCallback);
        super.onDetachedFromWindow();
    }

    private void scheduleFrame() {
        if (frameScheduled) return;
        frameScheduled = true;
        choreographer.postFrameCallback(frameCallback);
    }

    // ------------------------------------------------------------------
    // GL renderer
    // ------------------------------------------------------------------

    private final class Renderer implements GLSurfaceView.Renderer {

        private int sceneWidth;
        private int sceneHeight;
        private int sceneTexture;
        private int blurTextureA;
        private int blurTextureB;
        private int blurFboA;
        private int blurFboB;
        private int blurProgram;
        private int finalProgram;
        private int quadBuffer;
        private int viewWidth;
        private int viewHeight;
        /**
         * How far the 16:9 scene has to be stretched in UV space so that it
         * keeps its proportions inside a differently shaped screen. Values
         * above 1 push the sampled texel past the scene edges; the texture is
         * clamped, so the picture's edges smear outward instead of leaving
         * black bars.
         */
        private float uvScaleX = 1f;
        private float uvScaleY = 1f;

        Renderer(int sceneWidth, int sceneHeight) {
            this.sceneWidth = sceneWidth;
            this.sceneHeight = sceneHeight;
        }

        /** Reallocates the scene / blur targets after a screen-shape change. */
        void setSceneSize(int width, int height) {
            if (width == sceneWidth && height == sceneHeight) return;
            int previousWidth = sceneWidth;
            int previousHeight = sceneHeight;
            sceneWidth = width;
            sceneHeight = height;
            if (sceneTexture == 0) return; // surface not created yet
            try {
                releaseTargets();
                sceneTexture = createTexture(width, height);
                blurTextureA = createTexture(width, height);
                blurTextureB = createTexture(width, height);
                blurFboA = createFbo(blurTextureA);
                blurFboB = createFbo(blurTextureB);
                updateUvScale();
            } catch (Throwable ignored) {
                sceneWidth = previousWidth;
                sceneHeight = previousHeight;
            }
        }

        private void releaseTargets() {
            try {
                if (blurFboA != 0) GLES20.glDeleteFramebuffers(1, new int[]{blurFboA}, 0);
                if (blurFboB != 0) GLES20.glDeleteFramebuffers(1, new int[]{blurFboB}, 0);
                int[] textures = new int[]{sceneTexture, blurTextureA, blurTextureB};
                GLES20.glDeleteTextures(3, textures, 0);
            } catch (Throwable ignored) {
            }
            blurFboA = 0;
            blurFboB = 0;
            sceneTexture = 0;
            blurTextureA = 0;
            blurTextureB = 0;
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            blurProgram = buildProgram(BLUR_VERTEX, BLUR_FRAGMENT);
            finalProgram = buildProgram(BLUR_VERTEX, FINAL_FRAGMENT);
            sceneTexture = createTexture(sceneWidth, sceneHeight);
            blurTextureA = createTexture(sceneWidth, sceneHeight);
            blurTextureB = createTexture(sceneWidth, sceneHeight);
            blurFboA = createFbo(blurTextureA);
            blurFboB = createFbo(blurTextureB);
            quadBuffer = createQuad();
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            viewWidth = width;
            viewHeight = height;
            updateUvScale();
        }

        /**
         * The scene is normally rendered at the same shape as the surface, so
         * this stays at 1 and no bars exist. The small safety margin only
         * matters for the frames between a display change and the scene being
         * rebuilt.
         */
        private void updateUvScale() {
            int width = Math.max(1, viewWidth);
            int height = Math.max(1, viewHeight);
            float viewAspect = width / (float) Math.max(1, height);
            float sceneAspect = sceneWidth / (float) sceneHeight;
            if (viewAspect > sceneAspect) {
                uvScaleX = viewAspect / sceneAspect;
                uvScaleY = 1f;
            } else {
                uvScaleX = 1f;
                uvScaleY = sceneAspect / Math.max(0.01f, viewAspect);
            }
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            long now = SystemClock.uptimeMillis();
            long position = scene.displayPositionMs();
            sceneCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            float sceneScale = sceneWidth / 1280f;
            sceneCanvas.save();
            sceneCanvas.scale(sceneScale, sceneScale);
            scene.drawSceneFrame(sceneCanvas, position, now);
            sceneCanvas.restore();

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneTexture);
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, sceneBitmap);

            int sourceTexture = sceneTexture;
            float blur = scene.currentBlurStrength();
            if (blur > 0.05f) {
                float radius = Math.min(8f, blur * 0.55f);
                blurPass(blurProgram, blurFboA, sceneTexture, 1f / sceneWidth * radius, 0f);
                blurPass(blurProgram, blurFboB, blurTextureA, 0f, 1f / sceneHeight * radius);
                sourceTexture = blurTextureB;
            }

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, viewWidth, viewHeight);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(finalProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexture);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(finalProgram, "uTex"), 0);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(finalProgram, "uQuadScale"),
                    1f, 1f);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(finalProgram, "uUvScale"),
                    uvScaleX, uvScaleY);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(finalProgram, "uDistortion"), 0.10f);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(finalProgram, "uDispersion"), 0.0022f);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(finalProgram, "uVignette"), 0.35f);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(finalProgram, "uGrain"), 0.07f);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(finalProgram, "uContrast"), 0.06f);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(finalProgram, "uHalftone"), 0.16f);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(finalProgram, "uRgbShift"), 0.0018f);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(finalProgram, "uTime"),
                    (now % 100000L) / 1000f);
            drawQuad(quadBuffer, finalProgram);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }

        private void blurPass(int program, int fbo, int texture, float dx, float dy) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glViewport(0, 0, sceneWidth, sceneHeight);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0);
            // The blur pass renders into an FBO that is exactly scene-sized, so
            // the quad must not be scaled here. Leaving this uniform unset used
            // to collapse the quad to a single point (the GL default is 0,0),
            // which cleared both blur targets and made the whole screen flash
            // black on every blur-based lyric transition.
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uQuadScale"), 1f, 1f);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uUvScale"), 1f, 1f);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uDir"), dx, dy);
            drawQuad(quadBuffer, program);
        }

        private int createTexture(int width, int height) {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            return textures[0];
        }

        private int createFbo(int texture) {
            int[] fbos = new int[1];
            GLES20.glGenFramebuffers(1, fbos, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            return fbos[0];
        }
    }

    // ------------------------------------------------------------------
    // Shader helpers
    // ------------------------------------------------------------------

    private static int createQuad() {
        float[] vertices = {
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f,
        };
        FloatBuffer buffer = ByteBuffer.allocateDirect(vertices.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(vertices).position(0);
        int[] ids = new int[1];
        GLES20.glGenBuffers(1, ids, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertices.length * 4,
                buffer, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        return ids[0];
    }

    private static void drawQuad(int quadBuffer, int program) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuffer);
        int position = GLES20.glGetAttribLocation(program, "aPos");
        int uv = GLES20.glGetAttribLocation(program, "aUv");
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, 0);
        GLES20.glEnableVertexAttribArray(uv);
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, 8);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(position);
        GLES20.glDisableVertexAttribArray(uv);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    private static int buildProgram(String vertexSource, String fragmentSource) {
        int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (vertex == 0 || fragment == 0) return 0;
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            Log.e("LyricsGl", "link failed: " + GLES20.glGetProgramInfoLog(program));
        }
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e("LyricsGl", "compile failed: " + GLES20.glGetShaderInfoLog(shader));
        }
        return shader;
    }


    private static final String BLUR_VERTEX =
            "attribute vec4 aPos;\n"
                    + "attribute vec2 aUv;\n"
                    + "varying vec2 vUv;\n"
                    + "uniform vec2 uQuadScale;\n"
                    + "uniform vec2 uUvScale;\n"
                    + "void main() {\n"
                    // Only V is flipped: the Android bitmap is uploaded top-down
                    // while GL texture t=0 is the first uploaded row, so the
                    // scene must be flipped vertically. Flipping U as well (the
                    // previous behaviour) mirrored the picture left/right.
                    // uUvScale > 1 pushes the sample past the scene edge; with
                    // CLAMP_TO_EDGE the edge pixels are smeared into the
                    // letterbox area, so non-16:9 screens show the blurred
                    // backdrop there instead of black bars.
                    + "  vUv = vec2((aUv.x - 0.5) * uUvScale.x + 0.5,\n"
                    + "              (0.5 - aUv.y) * uUvScale.y + 0.5);\n"
                    + "  gl_Position = vec4(aPos.x * uQuadScale.x, aPos.y * uQuadScale.y, 0.0, 1.0);\n"
                    + "}\n";

    private static final String BLUR_FRAGMENT =
            "precision mediump float;\n"
                    + "varying vec2 vUv;\n"
                    + "uniform sampler2D uTex;\n"
                    + "uniform vec2 uDir;\n"
                    + "void main() {\n"
                    + "  vec4 sum = texture2D(uTex, vUv) * 0.227027;\n"
                    + "  sum += texture2D(uTex, vUv + uDir * 1.384615) * 0.316216;\n"
                    + "  sum += texture2D(uTex, vUv - uDir * 1.384615) * 0.316216;\n"
                    + "  sum += texture2D(uTex, vUv + uDir * 3.230769) * 0.070270;\n"
                    + "  sum += texture2D(uTex, vUv - uDir * 3.230769) * 0.070270;\n"
                    + "  gl_FragColor = sum;\n"
                    + "}\n";

    private static final String FINAL_FRAGMENT =
            "precision mediump float;\n"
                    + "varying vec2 vUv;\n"
                    + "uniform sampler2D uTex;\n"
                    + "uniform vec2 uQuadScale;\n"
                    + "uniform float uDistortion;\n"
                    + "uniform float uDispersion;\n"
                    + "uniform float uVignette;\n"
                    + "uniform float uGrain;\n"
                    + "uniform float uContrast;\n"
                    + "uniform float uHalftone;\n"
                    + "uniform float uRgbShift;\n"
                    + "uniform float uTime;\n"
                    + "void main() {\n"
                    + "  vec2 uv = vUv;\n"
                    + "  vec2 centered = uv - 0.5;\n"
                    + "  float r2 = dot(centered, centered);\n"
                    + "  vec2 base = uv + centered * (r2 * uDistortion);\n"
                    + "  float d = uDispersion;\n"
                    + "  vec2 redUv = uv + vec2(uRgbShift, 0.0) + centered * (r2 * uDistortion + d);\n"
                    + "  vec2 blueUv = uv - vec2(uRgbShift, 0.0) + centered * (r2 * uDistortion - d);\n"
                    + "  vec3 color;\n"
                    + "  color.r = texture2D(uTex, redUv).r;\n"
                    + "  color.g = texture2D(uTex, base).g;\n"
                    + "  color.b = texture2D(uTex, blueUv).b;\n"
                    + "  float vig = smoothstep(0.95, 0.15, length(centered) * 1.35);\n"
                    + "  color *= mix(1.0, vig, uVignette);\n"
                    + "  float noise = fract(sin(dot(uv * 1024.0 + uTime, vec2(12.9898, 78.233))) * 43758.5453);\n"
                    + "  color += (noise - 0.5) * uGrain;\n"
                    + "  float dots = sin(base.x * 1500.0) * sin(base.y * 1500.0);\n"
                    + "  color = mix(color, color * (0.82 + 0.18 * step(0.0, dots)), uHalftone);\n"
                    + "  color = (color - 0.5) * (1.0 + uContrast) + 0.5;\n"
                    + "  gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);\n"
                    + "}\n";
}
