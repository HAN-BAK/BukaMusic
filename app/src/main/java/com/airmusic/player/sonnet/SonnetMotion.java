package com.airmusic.player.sonnet;

/**
 * Pure timing/motion port of Folia's sonnetMotion.ts. Keeping the exact camera
 * tables matters: the camera must keep drifting through the middle of a shot
 * instead of easing to a stop, which is what gives the original its PV feel.
 */
public final class SonnetMotion {

    public static final float CAMERA_BREATH_MAX_OFFSET = 0.006f;
    public static final float CAMERA_BREATH_MAX_SCALE = 0.002f;
    public static final float CAMERA_BREATH_MAX_ROTATION = 0.0015f;

    private SonnetMotion() {
    }

    public static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float cubicCoordinate(float p1, float p2, float t) {
        float inverse = 1f - t;
        return 3f * inverse * inverse * t * p1
                + 3f * inverse * t * t * p2
                + t * t * t;
    }

    public static float cubicBezier(float x1, float y1, float x2, float y2, float value) {
        float target = clamp01(value);
        if (target == 0f || target == 1f) return target;
        float low = 0f;
        float high = 1f;
        float parameter = target;
        for (int i = 0; i < 12; i++) {
            float x = cubicCoordinate(x1, x2, parameter);
            if (x < target) low = parameter;
            else high = parameter;
            parameter = (low + high) * 0.5f;
        }
        return cubicCoordinate(y1, y2, parameter);
    }

    public static float easeInOut(float value) {
        return cubicBezier(0.65f, 0f, 0.35f, 1f, value);
    }

    public static float easeEnter(float value) {
        return cubicBezier(0.22f, 1f, 0.36f, 1f, value);
    }

    /** High-tension PV entrance easing. */
    public static float expoOut(float value) {
        return value >= 1f ? 1f : (float) (1.0 - Math.pow(2.0, -10.0 * value));
    }

    /** PV-style shot path: fast entrance, constant-velocity middle, soft settle. */
    public static float shotPathProgress(SonnetDirector.Kind kind, float progress) {
        float linear = clamp01(progress);
        if (kind == SonnetDirector.Kind.TRACKING_RIBBON
                || kind == SonnetDirector.Kind.FRAGMENT_COLLAGE
                || kind == SonnetDirector.Kind.QUIET_TABLEAU
                || kind == SonnetDirector.Kind.POSTER_BLOCKS) {
            return linear * 0.55f + easeInOut(linear) * 0.45f;
        }
        if (linear < 0.18f) return expoOut(linear / 0.18f) * 0.22f;
        if (linear < 0.78f) return 0.22f + ((linear - 0.18f) / 0.6f) * 0.56f;
        float settle = (linear - 0.78f) / 0.22f;
        return 0.78f + (1f - (1f - settle) * (1f - settle)) * 0.22f;
    }

    /** Normalized camera frame [x, y, scale, rotation] for one shot kind. */
    public static float[] shotMotionFrame(SonnetDirector.Kind kind, float progress) {
        float linear = clamp01(progress);
        float e = shotPathProgress(kind, linear);
        switch (kind) {
            case EDITORIAL_COLUMN:
                return new float[]{-0.055f + e * 0.095f, 0.025f - e * 0.04f,
                        0.98f + e * 0.07f, -0.006f + e * 0.01f};
            case TYPE_IMPACT:
                return new float[]{-0.035f + e * 0.07f, 0.018f - e * 0.028f,
                        1f + (1f - expoOut(Math.min(linear / 0.18f, 1f))) * 0.22f + e * 0.08f,
                        -0.01f + e * 0.016f};
            case FRAGMENT_COLLAGE:
                return new float[]{-0.045f + e * 0.085f,
                        0.028f - (float) Math.sin(e * Math.PI) * 0.055f,
                        0.97f + e * 0.09f, -0.014f + e * 0.028f};
            case TRACKING_RIBBON:
                return new float[]{-0.16f + e * 0.28f, 0.05f - e * 0.085f,
                        0.98f + e * 0.07f, 0.008f - e * 0.014f};
            case MASK_REVEAL:
                return new float[]{0.035f - e * 0.065f, 0.1f - e * 0.135f,
                        0.96f + e * 0.12f, -0.006f + e * 0.009f};
            case POSTER_BLOCKS:
                return new float[]{-0.012f + e * 0.024f, 0.008f - e * 0.016f,
                        0.99f + e * 0.025f, -0.0015f + e * 0.003f};
            case QUIET_TABLEAU:
            default:
                return new float[]{-0.022f + e * 0.04f, 0.014f - e * 0.025f,
                        1f + e * 0.028f, -0.002f + e * 0.003f};
        }
    }

    public static float[] cameraBreath(long timeMs, float phase) {
        double tau = timeMs / 1000.0 * Math.PI * 2.0;
        float x = (float) ((Math.sin(tau * 0.13 + phase) * 0.65
                + Math.sin(tau * 0.31 + phase * 1.7) * 0.35) * CAMERA_BREATH_MAX_OFFSET);
        float y = (float) ((Math.cos(tau * 0.11 + phase * 2.3) * 0.65
                + Math.sin(tau * 0.29 + phase * 0.9) * 0.35) * CAMERA_BREATH_MAX_OFFSET);
        float scale = (float) (Math.sin(tau * 0.09 + phase * 1.3) * CAMERA_BREATH_MAX_SCALE);
        float rotation = (float) (Math.sin(tau * 0.07 + phase * 2.9) * CAMERA_BREATH_MAX_ROTATION);
        return new float[]{x, y, scale, rotation};
    }

    public static float breathWeight(long timeMs, long revealDoneMs, long rampMs) {
        if (rampMs <= 0) return timeMs >= revealDoneMs ? 1f : 0f;
        return easeInOut(clamp01((timeMs - revealDoneMs) / (float) rampMs));
    }

    /** Segment-local grapheme progress with the original ExpoOut hit. */
    public static float segmentProgress(long startMs, long settleMs, long timeMs) {
        return expoOut(clamp01((timeMs - startMs) / (float) Math.max(80L, settleMs - startMs)));
    }
}
