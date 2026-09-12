package com.airmusic.player.sonnet;

import android.graphics.Paint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Kinematic typography layout for one Sonnet shot.
 *
 * <p>Segments are assigned hero / semi-hero / support roles, sized with the
 * original 3.0-5.5x base-font multipliers, then placed by one of the seven
 * composition families. All coordinates are relative to the shot origin; the
 * renderer moves the whole shot around the scene plane with the camera.
 */
public final class SonnetLayout {

    private static final float SAFE_HALF_W = 0.46f;
    private static final float SAFE_HALF_H = 0.44f;
    /** Scale the last layout had to apply to make everything fit. */
    public static volatile float lastContentScale = 1f;

    public static final class Placement {
        public int segmentIndex;
        public String text;
        public SonnetDirector.Role role;
        public float x;
        public float y;
        public float fontSize;
        public float rotation;
        public float enterX;
        public float enterY;
        public boolean vertical;

        float measuredWidth;
        float measuredHeight;
    }

    private SonnetLayout() {
    }

    public static List<Placement> layout(SonnetDirector.Shot shot, float width, float height,
                                         float baseFontSize, Paint measurePaint) {
        lastContentScale = 1f;
        List<SonnetDirector.Segment> segments = new ArrayList<>();
        for (SonnetDirector.Segment segment : shot.segments) {
            if (segment.wordLike) segments.add(segment);
        }
        if (segments.isEmpty()) return new ArrayList<>();
        if (segments.size() > 10) segments = new ArrayList<>(segments.subList(0, 10));
        if (shot.interlude) {
            return layoutInterlude(shot, baseFontSize, measurePaint, segments);
        }

        List<Placement> placements = new ArrayList<>(segments.size());
        int visibleChars = 0;
        for (SonnetDirector.Segment segment : segments) {
            visibleChars += Math.max(1, segment.text.trim().length());
        }
        int variantSeed = visibleChars + segments.size();

        int heroIndex = findHeroIndex(segments);
        int secondaryHero = -1;
        int editorialVariant = variantSeed % 5;
        if (editorialVariant == 3 && segments.size() > 2) {
            secondaryHero = findSecondaryHero(segments, heroIndex);
            if (secondaryHero < 0) editorialVariant = 0;
        } else if (editorialVariant == 3) {
            editorialVariant = 0;
        } else if (editorialVariant == 4 && segments.size() < 2) {
            editorialVariant = 2;
        }
        int ribbonVariant = variantSeed % 3;
        int tableauVariant = variantSeed % 4;
        int collageVariant = variantSeed % 3;

        for (int i = 0; i < segments.size(); i++) {
            SonnetDirector.Segment segment = segments.get(i);
            boolean isHero = i == heroIndex
                    || (i == secondaryHero && shot.kind == SonnetDirector.Kind.EDITORIAL_COLUMN
                    && editorialVariant == 3);
            boolean isSemiHero = (i == heroIndex - 1 || i == heroIndex + 1) && !isHero;
            SonnetDirector.Role role = isHero ? SonnetDirector.Role.HERO
                    : isSemiHero ? SonnetDirector.Role.SEMI_HERO : SonnetDirector.Role.SUPPORT;

            float heroScale = 3.0f;
            float supportScale = 1.15f;
            boolean vertical = false;
            float rotation = 0f;
            switch (shot.kind) {
                case EDITORIAL_COLUMN:
                    if (editorialVariant == 3) {
                        heroScale = 3.8f;
                        supportScale = 1.3f;
                    } else if (editorialVariant == 4) {
                        heroScale = 4.2f;
                        supportScale = 1.25f;
                        vertical = isHero || isSemiHero;
                    } else {
                        heroScale = editorialVariant == 2 ? 3.2f : 4.0f;
                        supportScale = 1.2f;
                        vertical = (isHero || isSemiHero) && editorialVariant != 2;
                    }
                    break;
                case TYPE_IMPACT:
                    heroScale = 5.5f;
                    supportScale = 1.5f;
                    break;
                case FRAGMENT_COLLAGE:
                    heroScale = 3.2f;
                    supportScale = 1.35f;
                    vertical = isSemiHero || i % 4 == 0;
                    break;
                case TRACKING_RIBBON:
                    heroScale = 3.5f;
                    supportScale = 1.5f;
                    break;
                case MASK_REVEAL:
                    heroScale = 4.5f;
                    supportScale = 1.6f;
                    vertical = isHero || isSemiHero;
                    break;
                case POSTER_BLOCKS:
                    heroScale = 4.4f;
                    supportScale = 1.15f;
                    break;
                case QUIET_TABLEAU:
                default:
                    heroScale = 3.0f;
                    supportScale = 1.15f;
                    vertical = (isHero || isSemiHero) && (tableauVariant == 0 || tableauVariant == 1);
                    break;
            }

            float fontScale = isHero ? heroScale
                    : isSemiHero ? Math.max(supportScale * 1.35f, heroScale * 0.72f)
                    : supportScale;
            boolean cjk = containsCjk(segment.text);
            if (vertical && !cjk && segment.text.length() > 1) {
                vertical = false;
                rotation += (float) (Math.PI / 2);
            }

            Placement placement = new Placement();
            placement.segmentIndex = i;
            placement.text = segment.text;
            placement.role = role;
            float roleLimit = isHero ? 260f : isSemiHero ? 170f : 110f;
            placement.fontSize = Math.min(roleLimit,
                    Math.max(baseFontSize * 0.85f, baseFontSize * fontScale));
            // Vertical pillars and long horizontal words must not outgrow the
            // safe area; capping the font here is what stops vertical lyrics
            // from overlapping their neighbours.
            int placementChars = Math.max(1, visibleCharCount(placement.text));
            if (vertical) {
                float verticalLimit = height * 0.42f / (placementChars * 1.45f);
                placement.fontSize = Math.min(placement.fontSize, Math.max(20f, verticalLimit));
            } else {
                float horizontalLimit = width * 0.50f / (placementChars * 0.58f);
                placement.fontSize = Math.min(placement.fontSize, Math.max(20f, horizontalLimit));
            }
            placement.rotation = rotation;
            placement.vertical = vertical;
            measure(placement, measurePaint);
            placements.add(placement);
        }

        place(shot, placements, heroIndex, width, height, baseFontSize,
                editorialVariant, ribbonVariant, tableauVariant, collageVariant);
        resolveOverlaps(placements, width, height);
        fit(shot, placements, width, height, measurePaint);
        resolveOverlaps(placements, width, height);
        // A final proportional fit: scaling positions and fonts together
        // cannot introduce new overlaps, unlike clamping boxes one by one.
        fit(shot, placements, width, height, measurePaint);
        assignEntrances(shot, placements, heroIndex, width, height, baseFontSize);
        return placements;
    }

    // ------------------------------------------------------------------
    // Placement families
    // ------------------------------------------------------------------

    /**
     * Interlude markers are real lyric typography: three equal dots in the
     * same bold face, laid out along the break's flow direction and entering
     * one after another through the normal glyph timing.
     */
    private static List<Placement> layoutInterlude(SonnetDirector.Shot shot,
                                                   float baseFontSize, Paint measurePaint,
                                                   List<SonnetDirector.Segment> segments) {
        List<Placement> placements = new ArrayList<>(segments.size());
        float fontSize = Math.max(74f, Math.min(175f, baseFontSize * 1.95f));
        float step = fontSize * (shot.interludeVertical ? 0.2325f : 0.285f);
        for (int i = 0; i < segments.size(); i++) {
            Placement placement = new Placement();
            placement.segmentIndex = i;
            placement.text = segments.get(i).text;
            placement.role = SonnetDirector.Role.SUPPORT;
            placement.fontSize = fontSize;
            placement.rotation = 0f;
            placement.vertical = false;
            if (shot.interludeVertical) {
                placement.x = 0f;
                placement.y = (i - (segments.size() - 1) * 0.5f) * step;
                placement.enterX = 40f;
                placement.enterY = 0f;
            } else {
                placement.x = (i - (segments.size() - 1) * 0.5f) * step;
                placement.y = 0f;
                placement.enterX = 0f;
                placement.enterY = 40f;
            }
            measure(placement, measurePaint);
            placements.add(placement);
        }
        return placements;
    }

    private static void place(SonnetDirector.Shot shot, List<Placement> boxes, int heroIndex,
                              float width, float height, float baseFontSize,
                              int editorialVariant, int ribbonVariant,
                              int tableauVariant, int collageVariant) {
        placeFlow(shot, boxes, heroIndex, width, height, baseFontSize,
                ribbonVariant, tableauVariant, collageVariant);
    }

    /**
     * The original layouts are flow based: consecutive segments sit one
     * flowGap apart and the reading order never jumps across the stage. That
     * is what keeps the camera glide short and stable, so this simplified
     * director uses the same principle for every shot kind and only varies the
     * flow axis / jitter per family.
     */
    private static void placeFlow(SonnetDirector.Shot shot, List<Placement> boxes, int heroIndex,
                                  float width, float height, float baseFontSize,
                                  int ribbonVariant, int tableauVariant, int collageVariant) {
        float flowGap = clamp(baseFontSize * 0.7f, 28f, 84f);
        float stackGap = Math.max(40f, flowGap * 1.5f);
        boolean vertical = shot.kind == SonnetDirector.Kind.QUIET_TABLEAU
                || shot.kind == SonnetDirector.Kind.MASK_REVEAL;
        boolean jitter = shot.kind == SonnetDirector.Kind.FRAGMENT_COLLAGE
                || shot.kind == SonnetDirector.Kind.POSTER_BLOCKS
                || shot.kind == SonnetDirector.Kind.TYPE_IMPACT;
        Placement hero = boxes.get(heroIndex);
        hero.x = 0f;
        hero.y = 0f;

        if (vertical) {
            float down = hero.y + hero.measuredHeight * 0.5f + stackGap;
            float up = hero.y - hero.measuredHeight * 0.5f - stackGap;
            int slot = 0;
            for (int i = heroIndex + 1; i < boxes.size(); i++) {
                Placement box = boxes.get(i);
                box.x = hero.x + jitterOffset(slot, tableauVariant, box);
                box.y = down + box.measuredHeight * 0.5f;
                down += box.measuredHeight + stackGap;
                slot++;
            }
            slot = 0;
            for (int i = heroIndex - 1; i >= 0; i--) {
                Placement box = boxes.get(i);
                box.x = hero.x + jitterOffset(slot, tableauVariant, box);
                box.y = up - box.measuredHeight * 0.5f;
                up -= box.measuredHeight + stackGap;
                slot++;
            }
        } else {
            float right = hero.x + hero.measuredWidth * 0.5f + flowGap;
            float left = hero.x - hero.measuredWidth * 0.5f - flowGap;
            int slot = 0;
            for (int i = heroIndex + 1; i < boxes.size(); i++) {
                Placement box = boxes.get(i);
                box.x = right + box.measuredWidth * 0.5f;
                box.y = hero.y + jitterOffset(slot, collageVariant, box);
                right += box.measuredWidth + flowGap;
                slot++;
            }
            slot = 0;
            for (int i = heroIndex - 1; i >= 0; i--) {
                Placement box = boxes.get(i);
                box.x = left - box.measuredWidth * 0.5f;
                box.y = hero.y + jitterOffset(slot, collageVariant, box);
                left -= box.measuredWidth + flowGap;
                slot++;
            }
        }
        if (jitter) {
            for (int i = 0; i < boxes.size(); i++) {
                boxes.get(i).rotation += i % 2 == 0 ? -0.018f : 0.022f;
            }
        }
        // Give every word its own typographic voice: staggered baselines and a
        // slight rotation make the segmentation visible while the compact flow
        // keeps consecutive words close enough for a calm camera.
        for (int i = 0; i < boxes.size(); i++) {
            if (i == heroIndex) continue;
            Placement box = boxes.get(i);
            float stagger = Math.min(52f, box.fontSize * 0.42f);
            if (vertical) {
                box.x += ((i % 3) - 1) * stagger * 0.7f;
            } else {
                box.y += ((i % 3) - 1) * stagger;
            }
            box.rotation += (i % 2 == 0 ? -0.045f : 0.055f);
        }
    }

    private static float jitterOffset(int slot, int variant, Placement box) {
        int pattern = (variant + slot) % 3;
        if (pattern == 0) return 0f;
        return (pattern == 1 ? 1f : -1f) * Math.min(26f, box.measuredHeight * 0.18f);
    }

    @SuppressWarnings("unused")
    private static void placeFamilies(SonnetDirector.Shot shot, List<Placement> boxes, int heroIndex,
                                      float width, float height, float baseFontSize,
                                      int editorialVariant, int ribbonVariant,
                                      int tableauVariant, int collageVariant) {
        Placement hero = boxes.get(heroIndex);
        float flowGap = clamp(baseFontSize * 0.5f, 20f, 60f);
        float stackGap = Math.max(30f, flowGap * 1.4f);
        switch (shot.kind) {
            case TYPE_IMPACT:
                hero.x = 0f;
                hero.y = 0f;
                placeSupportRing(boxes, heroIndex, width, height, 0.34f, 0.26f);
                break;
            case EDITORIAL_COLUMN:
                placeEditorial(boxes, heroIndex, editorialVariant, width, height, flowGap, stackGap);
                break;
            case TRACKING_RIBBON:
                boxRibbon(boxes, heroIndex, ribbonVariant, flowGap);
                break;
            case QUIET_TABLEAU:
                placeTableau(boxes, heroIndex, tableauVariant, height, stackGap);
                break;
            case FRAGMENT_COLLAGE:
                placeCollage(boxes, heroIndex, collageVariant, shot.index, width);
                break;
            case MASK_REVEAL:
                placeCross(boxes, heroIndex, width, height);
                break;
            case POSTER_BLOCKS:
            default:
                placePoster(boxes, heroIndex, width, height, flowGap, stackGap);
                break;
        }
    }

    private static void placeSupportRing(List<Placement> boxes, int heroIndex,
                                         float width, float height,
                                         float radiusX, float radiusY) {
        int n = boxes.size();
        int slot = 0;
        for (int i = 0; i < n; i++) {
            if (i == heroIndex) continue;
            double angle = -Math.PI / 2 + slot * 1.7;
            float[] anchors = {-1f, 1f};
            float side = anchors[slot % 2];
            boxes.get(i).x = (float) Math.cos(angle) * width * radiusX * side;
            boxes.get(i).y = (float) Math.sin(angle) * height * radiusY;
            slot++;
        }
    }

    private static void placeEditorial(List<Placement> boxes, int heroIndex, int variant,
                                       float width, float height, float flowGap, float stackGap) {
        Placement hero = boxes.get(heroIndex);
        if (variant == 1) {
            hero.x = width * 0.22f;
            hero.y = 0f;
            float cursorY = -height * 0.3f;
            for (int i = 0; i < boxes.size(); i++) {
                if (i == heroIndex) continue;
                Placement box = boxes.get(i);
                box.x = hero.x - hero.measuredWidth * 0.5f - flowGap - box.measuredWidth * 0.5f;
                box.y = cursorY + box.measuredHeight * 0.5f;
                cursorY += box.measuredHeight + stackGap;
            }
            return;
        }
        if (variant == 2) {
            hero.x = 0f;
            hero.y = -height * 0.06f;
            int slot = 0;
            float rowY = height * 0.26f;
            for (int i = 0; i < boxes.size(); i++) {
                if (i == heroIndex) continue;
                Placement box = boxes.get(i);
                box.x = (slot - (boxes.size() - 2) / 2f) * (box.measuredWidth + flowGap);
                box.y = rowY;
                slot++;
            }
            return;
        }
        if (variant >= 3) {
            hero.x = -width * 0.15f;
            hero.y = 0f;
            int slot = 0;
            for (int i = 0; i < boxes.size(); i++) {
                if (i == heroIndex) continue;
                Placement box = boxes.get(i);
                if (variant == 3 && i == heroIndex + 2) {
                    box.x = width * 0.18f;
                    box.y = 0f;
                    continue;
                }
                box.x = hero.x + hero.measuredWidth * 0.5f + flowGap + box.measuredWidth * 0.5f;
                box.y = (slot - (boxes.size() - 2) / 2f) * (box.measuredHeight + stackGap);
                slot++;
            }
            return;
        }
        // variant 0: vertical hero pillar, earlier words right, later words left.
        hero.x = -width * 0.15f;
        hero.y = 0f;
        float beforeY = hero.y - hero.measuredHeight * 0.5f + stackGap * 0.5f;
        float afterY = beforeY;
        for (int i = 0; i < boxes.size(); i++) {
            if (i == heroIndex) continue;
            Placement box = boxes.get(i);
            if (i < heroIndex) {
                box.x = hero.x + hero.measuredWidth * 0.5f + flowGap + box.measuredWidth * 0.5f;
                box.y = beforeY + box.measuredHeight * 0.5f;
                beforeY += box.measuredHeight + stackGap;
            } else {
                box.x = hero.x - hero.measuredWidth * 0.5f - flowGap - box.measuredWidth * 0.5f;
                box.y = afterY + box.measuredHeight * 0.5f;
                afterY += box.measuredHeight + stackGap;
            }
        }
    }

    private static void boxRibbon(List<Placement> boxes, int heroIndex, int variant, float flowGap) {
        Placement hero = boxes.get(heroIndex);
        hero.x = 0f;
        hero.y = 0f;
        float cursorLeft = -hero.measuredWidth * 0.5f - flowGap;
        float cursorRight = hero.measuredWidth * 0.5f + flowGap;
        int slot = 0;
        for (int i = heroIndex - 1; i >= 0; i--) {
            Placement box = boxes.get(i);
            box.x = cursorLeft - box.measuredWidth * 0.5f;
            box.y = variant == 2 ? (slot % 2 == 0 ? 12f : -12f) : 0f;
            cursorLeft -= box.measuredWidth + flowGap;
            slot++;
        }
        slot = 0;
        for (int i = heroIndex + 1; i < boxes.size(); i++) {
            Placement box = boxes.get(i);
            box.x = cursorRight + box.measuredWidth * 0.5f;
            box.y = variant == 2 ? (slot % 2 == 0 ? 12f : -12f) : 0f;
            cursorRight += box.measuredWidth + flowGap;
            slot++;
        }
    }

    private static void placeTableau(List<Placement> boxes, int heroIndex, int variant,
                                     float height, float stackGap) {
        Placement hero = boxes.get(heroIndex);
        hero.x = 0f;
        hero.y = variant >= 2 ? 0f : -height * 0.1f;
        float safeHalfH = height * 0.46f;
        float beforeY = hero.y - hero.measuredHeight * 0.5f - stackGap;
        int column = 0;
        float columnStep = maxWidth(boxes) + stackGap + (variant == 3 ? 70f : 0f);
        for (int i = heroIndex - 1; i >= 0; i--) {
            Placement box = boxes.get(i);
            if (beforeY - box.measuredHeight < -safeHalfH) {
                column++;
                beforeY = safeHalfH;
            }
            box.x = hero.x + column * columnStep;
            box.y = beforeY - box.measuredHeight * 0.5f;
            beforeY -= box.measuredHeight + stackGap;
        }
        column = 0;
        float afterY = hero.y + hero.measuredHeight * 0.5f + stackGap;
        for (int i = heroIndex + 1; i < boxes.size(); i++) {
            Placement box = boxes.get(i);
            if (afterY + box.measuredHeight > safeHalfH) {
                column++;
                afterY = -safeHalfH;
            }
            box.x = hero.x - column * columnStep;
            box.y = afterY + box.measuredHeight * 0.5f;
            afterY += box.measuredHeight + stackGap;
        }
    }

    private static void placeCollage(List<Placement> boxes, int heroIndex, int variant,
                                     int shotIndex, float width) {
        Placement hero = boxes.get(heroIndex);
        hero.x = 0f;
        hero.y = 0f;
        int slot = 0;
        for (int i = 0; i < boxes.size(); i++) {
            if (i == heroIndex) continue;
            Placement box = boxes.get(i);
            double angle = slot * 2.399 + shotIndex * 0.7 + variant;
            float radius = width * (0.18f + (slot % 3) * 0.075f);
            box.x = (float) Math.cos(angle) * radius;
            box.y = (float) Math.sin(angle) * radius * 0.62f;
            box.rotation += (float) Math.sin(angle * 1.7) * 0.16f;
            slot++;
        }
    }

    private static void placeCross(List<Placement> boxes, int heroIndex, float width, float height) {
        Placement hero = boxes.get(heroIndex);
        hero.x = 0f;
        hero.y = 0f;
        float[][] slots = {
                {-0.3f, 0f}, {0.3f, 0f}, {0f, -0.26f}, {0f, 0.26f},
                {-0.26f, -0.2f}, {0.26f, -0.2f}, {-0.26f, 0.2f}, {0.26f, 0.2f}
        };
        int slot = 0;
        for (int i = 0; i < boxes.size(); i++) {
            if (i == heroIndex) continue;
            Placement box = boxes.get(i);
            float[] point = slots[slot % slots.length];
            box.x = point[0] * width;
            box.y = point[1] * height;
            slot++;
        }
    }

    private static void placePoster(List<Placement> boxes, int heroIndex,
                                    float width, float height, float flowGap, float stackGap) {
        float[][] slots = {
                {-0.2f, -0.16f}, {0.16f, -0.2f}, {0.2f, 0.04f},
                {-0.16f, 0.2f}, {0.06f, 0.24f}, {-0.02f, -0.02f}
        };
        int slot = 0;
        for (int i = 0; i < boxes.size(); i++) {
            Placement box = boxes.get(i);
            if (i == heroIndex) {
                box.x = -width * 0.18f;
                box.y = 0f;
                continue;
            }
            float[] point = slots[slot % slots.length];
            box.x = point[0] * width;
            box.y = point[1] * height;
            slot++;
        }
    }

    // ------------------------------------------------------------------
    // Fit + entrance vectors
    // ------------------------------------------------------------------

    private static void fit(SonnetDirector.Shot shot, List<Placement> boxes,
                            float width, float height, Paint measurePaint) {
        float safeW = width * SAFE_HALF_W;
        float safeH = height * SAFE_HALF_H;
        float contentScale = 1f;
        for (int iteration = 0; iteration < 24; iteration++) {
            boolean fits = true;
            for (Placement box : boxes) {
                if (Math.abs(box.x) + box.measuredWidth * 0.5f > safeW
                        || Math.abs(box.y) + box.measuredHeight * 0.5f > safeH) {
                    fits = false;
                    break;
                }
            }
            if (fits) {
                lastContentScale = contentScale;
                return;
            }
            if (contentScale * 0.9f < 0.35f) {
                // Never shrink past readability; the camera zoom compensation
                // below takes over from here.
                break;
            }
            contentScale *= 0.9f;
            for (Placement box : boxes) {
                box.x *= 0.9f;
                box.y *= 0.9f;
                box.fontSize *= 0.9f;
                measure(box, measurePaint);
            }
        }
        lastContentScale = contentScale;
    }

    /**
     * Pushes measured boxes apart so two segments can never sit on top of each
     * other on the scene plane (the original's flow layouts guarantee this by
     * construction; this is the equivalent guard for the simplified layouts).
     */
    private static void resolveOverlaps(List<Placement> boxes, float width, float height) {
        float minGap = Math.max(22f, Math.min(width, height) * 0.03f);
        for (int iteration = 0; iteration < 30; iteration++) {
            boolean moved = false;
            for (int i = 0; i < boxes.size(); i++) {
                for (int j = i + 1; j < boxes.size(); j++) {
                    Placement a = boxes.get(i);
                    Placement b = boxes.get(j);
                    float dx = b.x - a.x;
                    float dy = b.y - a.y;
                    float ox = (a.measuredWidth + b.measuredWidth) * 0.5f + minGap - Math.abs(dx);
                    float oy = (a.measuredHeight + b.measuredHeight) * 0.5f + minGap - Math.abs(dy);
                    if (ox <= 0f || oy <= 0f) continue;
                    if (ox < oy) {
                        float push = ox * 0.5f + 0.5f;
                        float sign = dx >= 0f ? 1f : -1f;
                        a.x -= push * sign;
                        b.x += push * sign;
                    } else {
                        float push = oy * 0.5f + 0.5f;
                        float sign = dy >= 0f ? 1f : -1f;
                        a.y -= push * sign;
                        b.y += push * sign;
                    }
                    moved = true;
                }
            }
            if (!moved) return;
        }
    }

    private static void assignEntrances(SonnetDirector.Shot shot, List<Placement> boxes,
                                        int heroIndex, float width, float height, float baseFontSize) {
        Placement hero = boxes.get(heroIndex);
        for (int i = 0; i < boxes.size(); i++) {
            Placement box = boxes.get(i);
            if (i == heroIndex) {
                box.enterX = 0f;
                box.enterY = 0f;
                continue;
            }
            // Keep the glyph entrance subtle: a large hero font used to push
            // whole words down by 50-70 px on every shot change, which read as
            // the picture jumping downwards.
            float distance = Math.max(10f, Math.min(26f, box.fontSize * 0.09f));
            float dx = box.x - hero.x;
            float dy = box.y - hero.y;
            if (Math.abs(dx) < 1f && Math.abs(dy) < 1f) {
                dx = (i % 2 == 0 ? -1f : 1f) * width * 0.2f;
            }
            if (Math.abs(dx) >= Math.abs(dy)) {
                box.enterX = dx >= 0 ? -distance : distance;
                box.enterY = 0f;
            } else {
                box.enterX = 0f;
                // Items below the hero rise up into place; items above settle
                // downwards. Both come from their own side of the composition.
                box.enterY = dy >= 0 ? distance : -distance;
            }
            float normal = (((i * 37) % 100) / 100f * 2f - 1f) * box.fontSize * 0.12f;
            if (box.vertical) {
                box.enterX += (((i % 2 == 0) ? -1f : 1f) * box.fontSize * 0.10f);
            } else {
                box.enterY += normal * 0.35f;
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static int findHeroIndex(List<SonnetDirector.Segment> segments) {
        int best = 0;
        float bestScore = -Float.MAX_VALUE;
        for (int i = 0; i < segments.size(); i++) {
            SonnetDirector.Segment segment = segments.get(i);
            int visible = segment.text.trim().length();
            float score = visible * 1.6f + (segment.endMs - segment.startMs) / 1000f * 2.2f;
            if (visible >= 2) score += 6f;
            if (visible >= 4) score += 4f;
            float middle = Math.abs(i - (segments.size() - 1) * 0.5f)
                    / Math.max(1f, segments.size() * 0.5f);
            score += (1f - middle) * 6f;
            if (segment.text.matches(".*[!?\uFF01\uFF1F\u2026].*")) score += 5f;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private static int findSecondaryHero(List<SonnetDirector.Segment> segments, int heroIndex) {
        int best = -1;
        float bestScore = -Float.MAX_VALUE;
        for (int i = 0; i < segments.size(); i++) {
            if (i == heroIndex) continue;
            SonnetDirector.Segment segment = segments.get(i);
            int visible = segment.text.trim().length();
            if (visible == 0) continue;
            float score = visible * 1.4f + Math.abs(i - heroIndex) * 2f;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private static void measure(Placement box, Paint paint) {
        paint.setTextSize(box.fontSize);
        float baseWidth;
        float baseHeight;
        if (box.vertical) {
            // Android CJK fonts are much taller than the web font metrics the
            // original 0.9em advance assumed, so use the font's own
            // ascent/descent to stop vertical characters from overlapping.
            Paint.FontMetrics metrics = paint.getFontMetrics();
            float verticalAdvance = Math.max(box.fontSize * 1.30f,
                    (metrics.descent - metrics.ascent) * 0.95f) + 2f;
            baseWidth = box.fontSize * 1.02f;
            baseHeight = verticalAdvance * Math.max(1, box.text.length());
        } else {
            baseWidth = paint.measureText(box.text);
            baseHeight = box.fontSize * 1.18f;
        }
        // Latin words in a vertical composition are rotated 90 degrees, so
        // their footprint on the stage is the rotated bounding box, not the
        // unrotated text metrics. Using the rotated box is what stops the
        // words from overlapping along the flow.
        float cosine = Math.abs((float) Math.cos(box.rotation));
        float sine = Math.abs((float) Math.sin(box.rotation));
        box.measuredWidth = baseWidth * cosine + baseHeight * sine;
        box.measuredHeight = baseWidth * sine + baseHeight * cosine;
    }

    private static float maxWidth(List<Placement> boxes) {
        float max = 0f;
        for (Placement box : boxes) max = Math.max(max, box.measuredWidth);
        return max;
    }

    private static int visibleCharCount(String text) {
        if (text == null) return 1;
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) count++;
        }
        return Math.max(1, count == 0 ? text.length() : count);
    }

    private static boolean containsCjk(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3040 && c <= 0x30FF)
                    || (c >= 0xAC00 && c <= 0xD7AF)) {
                return true;
            }
        }
        return false;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
