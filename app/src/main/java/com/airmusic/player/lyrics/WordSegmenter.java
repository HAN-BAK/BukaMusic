package com.airmusic.player.lyrics;

import android.os.Build;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Offline word segmentation, mirroring the original's Intl.Segmenter fallback.
 *
 * <p>Android ships the same ICU word-break engine that powers
 * {@code Intl.Segmenter} in the browser, exposed as
 * {@code android.icu.text.BreakIterator}. It is dictionary based, so Chinese /
 * Japanese / Korean lines are split into real words instead of fixed-length
 * character chunks, and it needs no network or API key. On Android 6.0 the ICU
 * class is missing, so a conservative character-chunk fallback is used.
 */
public final class WordSegmenter {

    private WordSegmenter() {
    }

    public static List<String> segment(String text) {
        return segment(text, null);
    }

    public static List<String> segment(String text, List<String> hints) {
        List<String> fallback = new ArrayList<>();
        if (text == null) return fallback;
        String value = text.trim();
        if (value.isEmpty()) return fallback;
        if (Build.VERSION.SDK_INT >= 24) {
            List<String> icu = segmentIcu(value);
            if (icu != null && !icu.isEmpty()) {
                return mergeHints(mergeFunctionWords(attachTrailingPunctuation(icu), value), hints);
            }
        }
        return mergeHints(mergeFunctionWords(fallbackChunks(value), value), hints);
    }

    /**
     * Reflective class loading keeps the ICU class out of API 23 devices,
     * where {@code android.icu} does not exist.
     */
    @SuppressWarnings("unchecked")
    private static List<String> segmentIcu(String text) {
        try {
            Class<?> segmenter = Class.forName("com.airmusic.player.lyrics.IcuWordSegmenter");
            Method method = segmenter.getMethod("segment", String.class);
            return (List<String>) method.invoke(null, text);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Mirrors the original prompt's rules: a grammatical tail / particle is
     * glued to the content word it belongs to instead of being shown as its own
     * floating segment (孤独的, 不知道, 見えない, ように...).
     */
    private static List<String> mergeFunctionWords(List<String> pieces, String source) {
        List<String> out = new ArrayList<>();
        for (int index = 0; index < pieces.size(); index++) {
            String piece = pieces.get(index);
            if (piece == null || piece.isEmpty()) continue;
            String trimmed = piece.trim();
            if (trimmed.isEmpty()) {
                if (!out.isEmpty()) out.set(out.size() - 1, out.get(out.size() - 1) + piece);
                continue;
            }
            if (isPrefixParticle(trimmed) && index + 1 < pieces.size()) {
                out.add(piece + pieces.get(index + 1));
                index++;
            } else if (isSuffixParticle(trimmed) && !out.isEmpty()) {
                out.set(out.size() - 1, out.get(out.size() - 1) + piece);
            } else {
                out.add(piece);
            }
        }
        if (out.isEmpty()) out.add(source);
        return out;
    }

    /** Particles that attach to the following verb/adjective (不知道, 没见过). */
    private static boolean isPrefixParticle(String text) {
        return "\u4e0d\u6ca1\u65e0\u522b\u5f88\u592a\u6700\u66f4\u518d\u53c8"
                .indexOf(text) >= 0 && text.length() == 1;
    }

    /** Particles / auxiliaries that attach to the preceding content word. */
    private static boolean isSuffixParticle(String text) {
        if (text.length() == 1) {
            return "\u7684\u4e86\u7740\u8fc7\u5730\u5f97\u5417\u5462\u5427"
                    .indexOf(text) >= 0
                    || "\u554a\u5440\u54e6\u55ef\u4e4b\u4e8e\u800c\u5176"
                    .indexOf(text) >= 0
                    || "\u306f\u304c\u3092\u306b\u3078\u3068\u3067\u3082"
                    .indexOf(text) >= 0
                    || "\u306e\u304b\u3088\u306d\u3055\u3060\u305f\u3066"
                    .indexOf(text) >= 0;
        }
        return "\u3067\u3059".equals(text) || "\u307e\u3059".equals(text)
                || "\u305f\u3044".equals(text) || "\u306a\u3044".equals(text);
    }

    /**
     * Song / artist names from the current track are protected words: ICU does
     * not know names like 黄龄 or HOYO-MiX and would split them into single
     * characters, so adjacent pieces are re-joined when they match a hint.
     */
    private static List<String> mergeHints(List<String> pieces, List<String> hints) {
        if (hints == null || hints.isEmpty() || pieces.isEmpty()) return pieces;
        List<String> out = new ArrayList<>(pieces);
        for (String hintRaw : hints) {
            String hint = normalizeHint(hintRaw);
            if (hint.length() < 2) continue;
            for (int i = 0; i < out.size(); i++) {
                StringBuilder concat = new StringBuilder();
                int end = i;
                while (end < out.size() && concat.length() < hint.length()) {
                    concat.append(normalizeHint(out.get(end)));
                    end++;
                }
                if (concat.toString().equals(hint) && end > i + 1) {
                    StringBuilder merged = new StringBuilder();
                    for (int k = i; k < end; k++) merged.append(out.get(k));
                    for (int k = end - 1; k >= i; k--) out.remove(k);
                    out.add(i, merged.toString());
                    break;
                }
            }
        }
        return out;
    }

    private static String normalizeHint(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) out.append(c);
        }
        return out.toString();
    }

    /**
     * ICU returns words, spaces and punctuation as separate pieces. The
     * original prompt requires trailing punctuation and the following space to
     * stay glued to the word before them, so the typography engine never shows
     * a lone comma as its own segment.
     */
    private static List<String> attachTrailingPunctuation(List<String> pieces) {
        List<String> out = new ArrayList<>();
        StringBuilder tail = new StringBuilder();
        for (String piece : pieces) {
            if (hasLetterOrDigit(piece)) {
                if (tail.length() > 0) {
                    if (!out.isEmpty()) {
                        out.set(out.size() - 1, out.get(out.size() - 1) + tail);
                    } else {
                        out.add(tail.toString());
                    }
                    tail.setLength(0);
                }
                out.add(piece);
            } else {
                tail.append(piece);
            }
        }
        if (tail.length() > 0) {
            if (!out.isEmpty()) out.set(out.size() - 1, out.get(out.size() - 1) + tail);
            else out.add(tail.toString());
        }
        return out;
    }

    private static boolean hasLetterOrDigit(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) return true;
        }
        return false;
    }

    private static Locale localeFor(String text) {
        boolean kana = false;
        boolean hangul = false;
        boolean han = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x3040 && c <= 0x30FF) kana = true;
            else if (c >= 0xAC00 && c <= 0xD7AF) hangul = true;
            else if (c >= 0x4E00 && c <= 0x9FFF) han = true;
        }
        if (kana) return Locale.JAPANESE;
        if (hangul) return Locale.KOREAN;
        if (han) return Locale.CHINESE;
        return Locale.ENGLISH;
    }

    private static List<String> fallbackChunks(String value) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int latinRun = 0;
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            String ch = value.substring(i, i + charCount);
            boolean punctuation = isPunctuation(codePoint);
            boolean space = Character.isWhitespace(codePoint);
            current.append(ch);
            i += charCount;
            if (punctuation) {
                chunks.add(current.toString());
                current.setLength(0);
                latinRun = 0;
                continue;
            }
            if (space) {
                if (latinRun > 0) chunks.add(current.toString().trim());
                current.setLength(0);
                latinRun = 0;
                continue;
            }
            latinRun += isCjk(codePoint) ? 0 : 1;
            int visible = current.codePointCount(0, current.length());
            int limit = latinRun > 0 ? 11 : 5;
            if (visible >= limit) {
                chunks.add(current.toString());
                current.setLength(0);
                latinRun = 0;
            }
        }
        if (current.length() > 0) chunks.add(current.toString().trim());
        return chunks;
    }

    private static boolean isCjk(int codePoint) {
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0x3040 && codePoint <= 0x30FF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF);
    }

    private static boolean isPunctuation(int codePoint) {
        if (codePoint == ',' || codePoint == '.' || codePoint == '!' || codePoint == '?'
                || codePoint == ';' || codePoint == ':' || codePoint == '-') {
            return true;
        }
        return codePoint == 0x3001 || codePoint == 0x3002 || codePoint == 0xFF0C
                || codePoint == 0xFF01 || codePoint == 0xFF1F || codePoint == 0xFF1B
                || codePoint == 0xFF1A || codePoint == 0x2026 || codePoint == 0x2014;
    }
}
