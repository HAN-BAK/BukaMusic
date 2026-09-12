package com.airmusic.player.util;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import androidx.core.content.ContextCompat;

import com.airmusic.player.R;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * App-wide dark blurred background. Paints the current album art (or the
 * dedicated "not playing" placeholder when idle) heavily blurred plus a 60% dark
 * scrim onto the activity's window background, so every screen shares the
 * same look. Activities keep their root layout transparent.
 */
public final class BlurBackground {

    private static Bitmap cachedBlur;
    private static Bitmap cachedSource;
    private static Bitmap placeholderCache;
    /** Live updates per activity so the background follows track changes. */
    private static final Map<Activity, StateBus.Listener> listeners =
            new WeakHashMap<>();
    /** What each window currently shows, so a new cover can crossfade in. */
    private static final Map<Activity, Bitmap> shown =
            new WeakHashMap<>();
    /** Crossfade length when the album art changes (track switch). */
    private static final int FADE_MS = 600;

    private BlurBackground() {
    }

    /** Applies the blurred background, or the fallback drawable when off. */
    public static void apply(Activity activity, int fallbackRes) {
        try {
            if (Prefs.BLUR_OFF.equals(new Prefs(activity).getBlurMode())) {
                shown.remove(activity);
                setFallback(activity, fallbackRes);
                return;
            }
            Bitmap art = currentArt();
            if (art == null) art = placeholder(activity);
            Bitmap blurred = build(activity, art);
            if (blurred == null) {
                shown.remove(activity);
                setFallback(activity, fallbackRes);
                return;
            }
            Bitmap current = shown.get(activity);
            // Nothing changed: leave a running crossfade (and the window)
            // untouched, otherwise every state update would cut it short.
            if (current == blurred) return;
            shown.put(activity, blurred);
            setBackground(activity, current, blurred);
            attachLiveUpdates(activity, fallbackRes);
        } catch (Throwable t) {
            shown.remove(activity);
            setFallback(activity, fallbackRes);
        }
    }

    /**
     * Swaps the window background, crossfading from the previous blurred
     * cover so a track change does not snap between two pictures.
     */
    private static void setBackground(Activity activity, Bitmap from, Bitmap to) {
        android.content.res.Resources res = activity.getResources();
        if (from == null || from.isRecycled() || from == to) {
            activity.getWindow().setBackgroundDrawable(new BitmapDrawable(res, to));
            return;
        }
        BitmapDrawable oldLayer = new BitmapDrawable(res, from);
        BitmapDrawable newLayer = new BitmapDrawable(res, to);
        oldLayer.setGravity(android.view.Gravity.FILL);
        newLayer.setGravity(android.view.Gravity.FILL);
        android.graphics.drawable.TransitionDrawable transition =
                new android.graphics.drawable.TransitionDrawable(
                        new android.graphics.drawable.Drawable[]{oldLayer, newLayer});
        transition.setCrossFadeEnabled(true);
        activity.getWindow().setBackgroundDrawable(transition);
        transition.startTransition(FADE_MS);
    }

    /** Stops live updates (call from the activity's onDestroy). */
    public static void detach(Activity activity) {
        shown.remove(activity);
        StateBus.Listener l = listeners.remove(activity);
        if (l != null) {
            StateBus.get().removeListener(l);
        }
    }

    private static void attachLiveUpdates(final Activity activity,
                                          final int fallbackRes) {
        if (listeners.containsKey(activity)) return;
        StateBus.Listener l = state -> apply(activity, fallbackRes);
        listeners.put(activity, l);
        StateBus.get().addListener(l);
    }

    private static Bitmap currentArt() {
        PlayerUiState state = StateBus.get().getState();
        return (state != null && state.art != null) ? state.art : null;
    }

    /** The dedicated "not playing" placeholder art. */
    private static Bitmap placeholder(Activity activity) {
        if (placeholderCache == null) {
            placeholderCache = BitmapFactory.decodeResource(
                    activity.getResources(), R.drawable.ic_airplay);
        }
        return placeholderCache;
    }

    private static void setFallback(Activity activity, int fallbackRes) {
        try {
            Drawable d = ContextCompat.getDrawable(activity, fallbackRes);
            if (d != null) {
                activity.getWindow().setBackgroundDrawable(d);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Bitmap build(Activity activity, Bitmap art) {
        if (art == cachedSource && cachedBlur != null) return cachedBlur;
        try {
            int w = activity.getResources().getDisplayMetrics().widthPixels;
            int h = activity.getResources().getDisplayMetrics().heightPixels;
            if (art.getWidth() <= 0 || art.getHeight() <= 0) return null;
            // Keep the art's aspect ratio; two-pass blur for a soft look.
            float aspect = art.getWidth() / (float) art.getHeight();
            int sw = Math.max(2, w / 384);
            int sh = Math.max(2, Math.round(sw / aspect));
            Bitmap tiny = Bitmap.createScaledBitmap(art, sw, sh, true);
            int mw = Math.max(6, sw * 4);
            int mh = Math.max(6, sh * 4);
            Bitmap mid = Bitmap.createScaledBitmap(tiny, mw, mh, true);
            if (tiny != mid) tiny.recycle();
            float scale = Math.max(w / (float) mw, h / (float) mh);
            int bW = Math.max(w, Math.round(mw * scale));
            int bH = Math.max(h, Math.round(mh * scale));
            Bitmap blurred = Bitmap.createScaledBitmap(mid, bW, bH, true);
            if (mid != blurred) mid.recycle();
            Bitmap result = Bitmap.createBitmap(bW, bH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(result);
            c.drawBitmap(blurred, 0, 0, null);
            c.drawColor(0x99000000);
            if (blurred != result) blurred.recycle();
            cachedSource = art;
            cachedBlur = result;
            return result;
        } catch (Throwable t) {
            return null;
        }
    }
}
