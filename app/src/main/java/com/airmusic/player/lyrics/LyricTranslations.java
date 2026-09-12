package com.airmusic.player.lyrics;

import java.util.ArrayList;
import java.util.List;

/**
 * Separates translations from the original lyrics before the PV director runs.
 *
 * <p>Folia keeps translations out of the Sonnet typography and renders them in
 * a dedicated bottom subtitle overlay. Many LRC files, however, simply put the
 * translation on its own timestamped line, so the two languages would otherwise
 * both end up on the scene plane. This pass pairs a translation line with the
 * line it translates and removes it from the timed lyric list.
 */
public final class LyricTranslations {

    private static final long MAX_PAIR_GAP_MS = 1_500L;
    private static final int SCRIPT_LATIN = 1;
    private static final int SCRIPT_CJK = 2;

    private LyricTranslations() {
    }

    public static Lyrics resolve(Lyrics lyrics) {
        if (lyrics == null || lyrics.isEmpty()) return lyrics;
        List<LyricLine> input = lyrics.lines;
        List<LyricLine> out = new ArrayList<>(input.size());
        boolean changed = false;
        int index = 0;
        while (index < input.size()) {
            LyricLine current = input.get(index);
            if (isCreditLine(current.text)) {
                changed = true;
                index++;
                continue;
            }
            if (current.translation != null) {
                out.add(current);
                index++;
                continue;
            }
            LyricLine split = splitMixedLine(current);
            if (split != current) {
                out.add(split);
                changed = true;
                index++;
                continue;
            }
            LyricLine next = index + 1 < input.size() ? input.get(index + 1) : null;
            if (next != null && next.translation == null && isTranslationOf(current, next)) {
                out.add(new LyricLine(current.startMs,
                        Math.max(current.endMs, next.endMs), current.text, next.text));
                index += 2;
                changed = true;
                continue;
            }
            out.add(current);
            index++;
        }
        if (!changed) return lyrics;
        return new Lyrics(out, lyrics.synced);
    }

    /**
     * Splits "English original 中文翻译" (the NetEase/HOYO-MiX export style) into
     * a main line plus a translation. Only a single Latin&lt;-&gt;CJK transition
     * with letters on both sides is treated as bilingual text, so Japanese kana
     * and Han on one line never get split apart.
     */
    private static LyricLine splitMixedLine(LyricLine line) {
        String text = line.text;
        if (text == null || text.length() < 5) return line;
        int boundary = -1;
        int lastScript = scriptBucket(text.charAt(0));
        for (int i = 1; i < text.length(); i++) {
            int script = scriptBucket(text.charAt(i));
            if (script == 0) continue;
            if (lastScript != 0 && script != lastScript) {
                if (letterCount(text, 0, i) >= 2
                        && letterCount(text, i, text.length()) >= 2) {
                    boundary = i;
                    break;
                }
            }
            lastScript = script;
        }
        if (boundary <= 0) return line;
        String main = text.substring(0, boundary).trim();
        String translation = text.substring(boundary).trim();
        if (main.isEmpty() || translation.isEmpty()) return line;
        if (translation.startsWith("(") || translation.startsWith("\uFF08")) return line;
        return new LyricLine(line.startMs, line.endMs, main, translation);
    }

    private static int scriptBucket(char c) {
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= 0x00C0 && c <= 0x024F)) {
            return SCRIPT_LATIN;
        }
        if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3040 && c <= 0x30FF)
                || (c >= 0xAC00 && c <= 0xD7AF)) {
            return SCRIPT_CJK;
        }
        if (Character.isLetter(c)) return 3;
        return 0;
    }

    private static int letterCount(String text, int start, int end) {
        int count = 0;
        for (int i = start; i < end; i++) {
            if (scriptBucket(text.charAt(i)) != 0) count++;
        }
        return count;
    }

    private static boolean isCreditLine(String text) {
        if (text == null) return false;
        String value = text.toLowerCase(java.util.Locale.US);
        return value.contains("\u4f5c\u8bcd") || value.contains("\u4f5c\u66f2")
                || value.contains("\u7f16\u66f2") || value.contains("\u7f16\u66f2")
                || value.contains("\u5f55\u97f3") || value.contains("\u6df7\u97f3")
                || value.contains("\u6bcd\u5e26") || value.contains("\u5236\u4f5c\u4eba")
                || value.contains("\u51fa\u54c1") || value.contains("\u7279\u522b\u9e23\u8c22")
                || value.contains("lyrics by") || value.contains("composed by")
                || value.contains("arranged by") || value.contains("recording:")
                || value.contains("mixing") || value.contains("mastering")
                || value.contains("producer") || value.contains("produced by")
                || value.trim().equals("\u7eaf\u97f3\u4e50");
    }

    private static boolean isTranslationOf(LyricLine original, LyricLine candidate) {
        String a = original.text == null ? "" : original.text.trim();
        String b = candidate.text == null ? "" : candidate.text.trim();
        if (a.isEmpty() || b.isEmpty() || a.equals(b)) return false;
        long gap = Math.abs(candidate.startMs - original.startMs);
        if (gap > MAX_PAIR_GAP_MS) return false;
        int scriptA = script(a);
        int scriptB = script(b);
        if (scriptA == 0 || scriptB == 0 || scriptA == scriptB) return false;
        // A translation is normally not dramatically longer than its original.
        return b.length() <= Math.max(24, a.length() * 2.6f);
    }

    /**
     * Script buckets kept separate so a Japanese line (which normally carries
     * kana) can be paired with its Han-only Chinese translation:
     * 1 = Han, 2 = Kana/Japanese, 3 = Hangul, 4 = Latin, 0 = unknown.
     */
    private static int script(String text) {
        int han = 0;
        int kana = 0;
        int hangul = 0;
        int latin = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) han++;
            else if (c >= 0x3040 && c <= 0x30FF) kana++;
            else if (c >= 0xAC00 && c <= 0xD7AF) hangul++;
            else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) latin++;
            else if (Character.isLetter(c)) other++;
        }
        if (kana >= 1 && kana + han >= latin) return 2;
        if (hangul >= 2 && hangul >= latin) return 3;
        if (han >= 2 && han >= latin) return 1;
        if (latin >= 3) return 4;
        if (other >= 2) return 5;
        return 0;
    }
}
