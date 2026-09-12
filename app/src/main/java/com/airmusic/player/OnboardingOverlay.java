package com.airmusic.player;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.airmusic.player.util.Prefs;

import java.util.ArrayList;
import java.util.List;

/**
 * First-run feature tour drawn as a spotlight: the screen is dimmed, the real
 * control being described is punched out of the scrim and ringed, and a small
 * card with one short sentence sits next to it. Only widgets that actually
 * exist (and are visible) get a step, so it adapts to the current layout.
 */
public class OnboardingOverlay extends FrameLayout {

    /** Marks the overlay inside the window's content view. */
    private static final String TAG = "onboarding-overlay";

    public interface Listener {
        void onDismissed();
    }

    private static final int[] ANCHORS = {
            R.id.album_art,
            R.id.btn_multicast,
            R.id.source_badge,
            R.id.volume_seek,
            R.id.btn_library,
            R.id.btn_apps,
            R.id.btn_settings,
    };

    private static final int[] TITLES = {
            R.string.ob_lyrics_title,
            R.string.ob_multicast_title,
            R.string.ob_airplay_title,
            R.string.ob_volume_title,
            R.string.ob_library_title,
            R.string.ob_apps_title,
            R.string.ob_settings_title,
    };

    private static final int[] BODIES = {
            R.string.ob_lyrics_body,
            R.string.ob_multicast_body,
            R.string.ob_airplay_body,
            R.string.ob_volume_body,
            R.string.ob_library_body,
            R.string.ob_apps_body,
            R.string.ob_settings_body,
    };

    private static final class Step {
        final View anchor;
        final int title;
        final int body;

        Step(View anchor, int title, int body) {
            this.anchor = anchor;
            this.title = title;
            this.body = body;
        }
    }

    private final List<Step> steps = new ArrayList<>();
    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path scrimPath = new Path();
    private final RectF hole = new RectF();
    private final RectF rounded = new RectF();
    private final int holePadding;
    private final int cardGap;
    private final int margin;

    private View card;
    private TextView titleView;
    private TextView bodyView;
    private LinearLayout dots;
    private Button nextButton;
    private Button skipButton;
    private int index;
    private Listener listener;
    private boolean dismissed;

    public OnboardingOverlay(Context context) {
        this(context, null);
    }

    public OnboardingOverlay(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        setFocusable(true);
        setFocusableInTouchMode(true);
        float density = getResources().getDisplayMetrics().density;
        holePadding = Math.round(8 * density);
        cardGap = Math.round(14 * density);
        margin = Math.round(20 * density);

        scrimPaint.setColor(0xD9060F1C);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(Math.round(2 * density));
        ringPaint.setColor(0xFF4FC3F7);

        card = LayoutInflater.from(context).inflate(R.layout.overlay_onboarding, this, false);
        addView(card);
        titleView = card.findViewById(R.id.onboarding_title);
        bodyView = card.findViewById(R.id.onboarding_body);
        dots = card.findViewById(R.id.onboarding_dots);
        nextButton = card.findViewById(R.id.onboarding_next);
        skipButton = card.findViewById(R.id.onboarding_skip);
        nextButton.setOnClickListener(v -> advance());
        skipButton.setOnClickListener(v -> dismiss());
    }

    public void setListener(Listener value) {
        listener = value;
    }

    /** Adds the tour to the playback screen the first time the app starts. */
    public static void showIfNeeded(final Activity activity) {
        final Prefs prefs = new Prefs(activity);
        if (prefs.isOnboardingDone()) return;
        final ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        // Wait for the first layout so the anchors have real positions.
        content.post(() -> {
            if (content.findViewWithTag(TAG) != null) return;
            OnboardingOverlay overlay = new OnboardingOverlay(activity);
            if (!overlay.collectSteps(activity)) {
                prefs.setOnboardingDone(true);
                return;
            }
            overlay.setListener(() -> prefs.setOnboardingDone(true));
            overlay.setTag(TAG);
            content.addView(overlay, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            overlay.setAlpha(0f);
            overlay.animate().alpha(1f).setDuration(240).start();
            overlay.requestFocus();
            overlay.showStep(0, false);
        });
    }

    /** Opens the tour from the settings screen (no "seen" flag involved). */
    public static void show(final Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        if (content.findViewWithTag(TAG) != null) return;
        OnboardingOverlay overlay = new OnboardingOverlay(activity);
        if (!overlay.collectSteps(activity)) return;
        overlay.setTag(TAG);
        content.addView(overlay, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(240).start();
        overlay.requestFocus();
        overlay.showStep(0, false);
    }

    /**
     * Resolves the widgets to point at. The playback screen behind the tour is
     * the source of truth: anything that is missing or hidden is skipped.
     */
    private boolean collectSteps(Activity activity) {
        steps.clear();
        for (int i = 0; i < ANCHORS.length; i++) {
            View anchor = activity.findViewById(ANCHORS[i]);
            if (anchor == null || anchor.getVisibility() != View.VISIBLE) continue;
            if (anchor.getWidth() <= 0 || anchor.getHeight() <= 0) continue;
            steps.add(new Step(anchor, TITLES[i], BODIES[i]));
        }
        return !steps.isEmpty();
    }

    private void buildDots() {
        dots.removeAllViews();
        int size = Math.round(7 * getResources().getDisplayMetrics().density);
        int gap = Math.round(5 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < steps.size(); i++) {
            View dot = new View(getContext());
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(0x4DFFFFFF);
            dot.setBackground(shape);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMargins(gap, 0, gap, 0);
            dot.setLayoutParams(params);
            dots.addView(dot);
        }
    }

    private void updateDots() {
        for (int i = 0; i < dots.getChildCount(); i++) {
            View dot = dots.getChildAt(i);
            if (dot.getBackground() instanceof GradientDrawable) {
                ((GradientDrawable) dot.getBackground())
                        .setColor(i == index ? 0xFF4FC3F7 : 0x4DFFFFFF);
            }
        }
    }

    private void showStep(int step, boolean animate) {
        if (steps.isEmpty()) {
            dismiss();
            return;
        }
        index = Math.max(0, Math.min(steps.size() - 1, step));
        Step current = steps.get(index);
        if (dots.getChildCount() != steps.size()) buildDots();
        titleView.setText(current.title);
        bodyView.setText(current.body);
        boolean last = index == steps.size() - 1;
        nextButton.setText(last ? R.string.onboarding_start : R.string.onboarding_next);
        skipButton.setVisibility(last ? View.INVISIBLE : View.VISIBLE);
        updateDots();
        updateHole(current.anchor);
        requestLayout();
        invalidate();
        if (animate) {
            card.setAlpha(0f);
            card.setTranslationY(12f * getResources().getDisplayMetrics().density);
            card.animate().alpha(1f).translationY(0f)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private void updateHole(View anchor) {
        int[] anchorLocation = new int[2];
        int[] myLocation = new int[2];
        anchor.getLocationOnScreen(anchorLocation);
        getLocationOnScreen(myLocation);
        float left = anchorLocation[0] - myLocation[0];
        float top = anchorLocation[1] - myLocation[1];
        hole.set(left - holePadding, top - holePadding,
                left + anchor.getWidth() + holePadding,
                top + anchor.getHeight() + holePadding);
        float radius = Math.max(12f * getResources().getDisplayMetrics().density,
                Math.min(hole.width(), hole.height()) * 0.22f);
        rounded.set(hole);
        scrimPath.reset();
        scrimPath.setFillType(Path.FillType.EVEN_ODD);
        scrimPath.addRect(0f, 0f, getWidth(), getHeight(), Path.Direction.CW);
        scrimPath.addRoundRect(rounded, radius, radius, Path.Direction.CW);
    }

    private void advance() {
        if (index >= steps.size() - 1) {
            dismiss();
        } else {
            showStep(index + 1, true);
        }
    }

    private void dismiss() {
        if (dismissed) return;
        dismissed = true;
        animate().alpha(0f).setDuration(180).withEndAction(() -> {
            ViewGroup parent = (ViewGroup) getParent();
            if (parent != null) parent.removeView(this);
            if (listener != null) listener.onDismissed();
        }).start();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        int height = bottom - top;
        if (card != null) {
            int cardWidth = Math.min(width - margin * 2,
                    Math.round(400 * getResources().getDisplayMetrics().density));
            card.measure(MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int cardHeight = card.getMeasuredHeight();
            RectF placement = choosePlacement(width, height, cardWidth, cardHeight);
            card.layout(Math.round(placement.left), Math.round(placement.top),
                    Math.round(placement.right), Math.round(placement.bottom));
        }
    }

    /**
     * Picks the card position that keeps the highlighted control visible:
     * below it, above it, then beside it - the highlighted widget is never
     * covered unless the screen is too small for any free spot.
     */
    private RectF choosePlacement(int width, int height, int cardWidth, int cardHeight) {
        RectF best = null;
        float bestOverlap = Float.MAX_VALUE;
        float[][] candidates = {
                // below (centred), above (centred), right (vertically centred),
                // left (vertically centred)
                {hole.centerX(), hole.bottom + cardGap},
                {hole.centerX(), hole.top - cardGap - cardHeight},
                {hole.right + cardGap + cardWidth / 2f, hole.centerY()},
                {hole.left - cardGap - cardWidth / 2f, hole.centerY()},
        };
        for (float[] candidate : candidates) {
            RectF rect = new RectF();
            rect.left = Math.round(candidate[0] - cardWidth / 2f);
            rect.top = Math.round(candidate[1]);
            rect.left = Math.max(margin, Math.min(width - cardWidth - margin, rect.left));
            rect.top = Math.max(margin, Math.min(height - cardHeight - margin, rect.top));
            rect.right = rect.left + cardWidth;
            rect.bottom = rect.top + cardHeight;
            float overlap = overlapArea(rect, hole);
            if (overlap <= 0f) return rect;
            if (overlap < bestOverlap) {
                bestOverlap = overlap;
                best = rect;
            }
        }
        return best != null ? best : new RectF(margin, margin,
                margin + cardWidth, margin + cardHeight);
    }

    private static float overlapArea(RectF a, RectF b) {
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        if (right <= left || bottom <= top) return 0f;
        return (right - left) * (bottom - top);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (!steps.isEmpty()) {
            updateHole(steps.get(index).anchor);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (hole.isEmpty()) {
            canvas.drawColor(0xD9060F1C);
            return;
        }
        canvas.drawPath(scrimPath, scrimPaint);
        canvas.drawRoundRect(rounded, rounded.width() * 0.2f, rounded.height() * 0.2f,
                ringPaint);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) dismiss();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** Keeps touches from reaching the playback screen behind the tour. */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return true;
    }
}
