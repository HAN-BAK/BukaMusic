package com.airmusic.player.lyrics;

import android.icu.text.BreakIterator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Direct ICU word segmentation. Kept in its own class so Android 6.0 devices,
 * which have no {@code android.icu} package, never load it: WordSegmenter
 * reaches this class through Class.forName only on API 24+.
 */
final class IcuWordSegmenter {

    private IcuWordSegmenter() {
    }

    public static List<String> segment(String text) {
        BreakIterator iterator = BreakIterator.getWordInstance(localeFor(text));
        iterator.setText(text);
        List<String> pieces = new ArrayList<>();
        int start = iterator.first();
        int end;
        while ((end = iterator.next()) != BreakIterator.DONE) {
            pieces.add(text.substring(start, end));
            start = end;
        }
        return pieces;
    }

    /**
     * Picks the ICU locale from the dominant script in the line. ICU ships
     * dictionary breakers for the languages that need them (Thai, Lao, Khmer,
     * Myanmar, Japanese, Chinese, Korean) and rule-based breakers for the rest,
     * so this is what makes segmentation work for every common writing system,
     * not just CJK and English.
     */
    private static Locale localeFor(String text) {
        int[] counts = new int[Script.values().length];
        for (int i = 0; i < text.length(); i++) {
            int script = Script.of(text.charAt(i));
            if (script >= 0) counts[script]++;
        }
        int best = 0;
        for (int i = 1; i < counts.length; i++) {
            if (counts[i] > counts[best]) best = i;
        }
        return Script.values()[best].locale;
    }

    private enum Script {
        LATIN(Locale.ENGLISH),
        VIETNAMESE(new Locale("vi")),
        RUSSIAN(new Locale("ru")),
        GREEK(new Locale("el")),
        ARABIC(new Locale("ar")),
        HEBREW(new Locale("iw")),
        THAI(new Locale("th")),
        LAO(new Locale("lo")),
        KHMER(new Locale("km")),
        MYANMAR(new Locale("my")),
        DEVANAGARI(new Locale("hi")),
        BENGALI(new Locale("bn")),
        TAMIL(new Locale("ta")),
        TELUGU(new Locale("te")),
        KANNADA(new Locale("kn")),
        MALAYALAM(new Locale("ml")),
        SINHALA(new Locale("si")),
        GEORGIAN(new Locale("ka")),
        ARMENIAN(new Locale("hy")),
        ETHIOPIC(new Locale("am")),
        HAN(Locale.CHINESE),
        JAPANESE(Locale.JAPANESE),
        KOREAN(Locale.KOREAN);

        final Locale locale;

        Script(Locale locale) {
            this.locale = locale;
        }

        static int of(char c) {
            if (c >= 0x4E00 && c <= 0x9FFF) return HAN.ordinal();
            if (c >= 0x3040 && c <= 0x30FF) return JAPANESE.ordinal();
            if (c >= 0xAC00 && c <= 0xD7AF) return KOREAN.ordinal();
            if (c >= 0x0E00 && c <= 0x0E7F) return THAI.ordinal();
            if (c >= 0x0E80 && c <= 0x0EFF) return LAO.ordinal();
            if (c >= 0x1780 && c <= 0x17FF) return KHMER.ordinal();
            if (c >= 0x1000 && c <= 0x109F) return MYANMAR.ordinal();
            if (c >= 0x0900 && c <= 0x097F) return DEVANAGARI.ordinal();
            if (c >= 0x0980 && c <= 0x09FF) return BENGALI.ordinal();
            if (c >= 0x0B80 && c <= 0x0BFF) return TAMIL.ordinal();
            if (c >= 0x0C00 && c <= 0x0C7F) return TELUGU.ordinal();
            if (c >= 0x0C80 && c <= 0x0CFF) return KANNADA.ordinal();
            if (c >= 0x0D00 && c <= 0x0D7F) return MALAYALAM.ordinal();
            if (c >= 0x0D80 && c <= 0x0DFF) return SINHALA.ordinal();
            if (c >= 0x0400 && c <= 0x04FF) return RUSSIAN.ordinal();
            if (c >= 0x0370 && c <= 0x03FF) return GREEK.ordinal();
            if (c >= 0x0590 && c <= 0x05FF) return HEBREW.ordinal();
            if ((c >= 0x0600 && c <= 0x06FF) || (c >= 0xFB50 && c <= 0xFEFF)) {
                return ARABIC.ordinal();
            }
            if (c >= 0x10A0 && c <= 0x10FF) return GEORGIAN.ordinal();
            if (c >= 0x0530 && c <= 0x058F) return ARMENIAN.ordinal();
            if (c >= 0x1200 && c <= 0x137F) return ETHIOPIC.ordinal();
            if (c >= 0x1EA0 && c <= 0x1EFF) return VIETNAMESE.ordinal();
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= 0x00C0 && c <= 0x024F)) {
                return LATIN.ordinal();
            }
            return -1;
        }
    }
}
