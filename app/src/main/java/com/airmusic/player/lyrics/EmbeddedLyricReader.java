package com.airmusic.player.lyrics;

import android.content.Context;
import android.net.Uri;

import com.airmusic.player.library.Track;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Reads unsynchronised lyrics from common audio tags without pulling in a tag
 * library: ID3v2 (MP3 / WAV), Vorbis comments (FLAC) and MP4 {@code ©lyr}
 * (M4A / MP4). SYLT and Ogg comments are intentionally left for a later pass.
 */
public final class EmbeddedLyricReader {

    private static final int MAX_TAG_BYTES = 16 * 1024 * 1024;

    private EmbeddedLyricReader() {
    }

    /** Returns the raw lyric text carried by the file, or null. */
    public static String read(Context context, Track track) {
        if (track == null) return null;
        String ext = track.extension == null ? "" : track.extension.toLowerCase(Locale.US);
        try {
            if ("flac".equals(ext)) return readFlac(context, track);
            if ("m4a".equals(ext) || "mp4".equals(ext) || "m4b".equals(ext)) return readMp4(track);
            if ("ogg".equals(ext) || "opus".equals(ext) || "oga".equals(ext)) {
                return readOgg(context, track);
            }
            if ("wav".equals(ext) || "wave".equals(ext)) {
                String wav = readWav(context, track);
                if (notEmpty(wav)) return wav;
            }
            return readId3(context, track);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // ID3v2 (MP3, and the optional ID3 chunk inside a WAV)
    // ------------------------------------------------------------------

    private static String readId3(Context context, Track track) throws IOException {
        InputStream in = open(context, track);
        if (in == null) return null;
        try {
            BufferedInputStream buffered = in instanceof BufferedInputStream
                    ? (BufferedInputStream) in : new BufferedInputStream(in, 8192);
            byte[] header = readN(buffered, 10);
            if (header == null) return null;
            if (header[0] != 'I' || header[1] != 'D' || header[2] != '3') return null;
            return readId3Body(buffered, header);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String readId3Body(InputStream in, byte[] header) throws IOException {
        int major = header[3] & 0xFF;
        int flags = header[5] & 0xFF;
        int size = syncSafe(header, 6);
        if (size <= 0 || size > MAX_TAG_BYTES) return null;
        byte[] body = readN(in, size);
        if (body == null) return null;
        if ((flags & 0x80) != 0) body = deUnsynchronise(body);

        int pos = 0;
        if ((flags & 0x40) != 0 && body.length >= 4) {
            if (major >= 4) {
                pos += syncSafe(body, 0);
            } else {
                int extSize = beInt(body, 0) + 4;
                if (extSize > 0 && extSize <= body.length) pos = extSize;
            }
        }
        if (major <= 2) return null; // ID3v2.2 frames are rare; unsupported here.
        return parseId3Frames(body, pos, major >= 4);
    }

    private static String parseId3Frames(byte[] body, int pos, boolean syncSafeSizes) {
        String fallback = null;
        while (pos + 10 <= body.length) {
            if (body[pos] == 0) break;
            String id = new String(body, pos, 4, StandardCharsets.ISO_8859_1);
            int size = syncSafeSizes ? syncSafe(body, pos + 4) : beInt(body, pos + 4);
            pos += 10;
            if (size <= 0 || pos + size > body.length) break;
            if ("SYLT".equals(id)) {
                String synced = parseSylt(body, pos, size);
                if (notEmpty(synced)) return synced;
            } else if ("USLT".equals(id) && fallback == null) {
                String text = parseUslt(body, pos, size);
                if (notEmpty(text)) fallback = text;
            }
            pos += size;
        }
        return fallback;
    }

    private static String parseUslt(byte[] frame, int offset, int length) {
        if (length < 5) return null;
        int encoding = frame[offset] & 0xFF;
        int cursor = offset + 4; // encoding + 3-byte language
        int end = offset + length;
        int textStart = findStringEnd(frame, cursor, end, encoding);
        if (textStart < 0 || textStart >= end) return null;
        return decode(frame, textStart, end - textStart, encoding);
    }

    /**
     * Converts an ID3 SYLT (synchronised lyrics) frame into LRC text, which
     * lets the renderer use real per-line timings instead of estimating them.
     */
    private static String parseSylt(byte[] frame, int offset, int length) {
        if (length < 7) return null;
        int encoding = frame[offset] & 0xFF;
        int timestampFormat = frame[offset + 4] & 0xFF;
        if (timestampFormat != 2) return null; // 2 = milliseconds
        int cursor = offset + 6;
        int end = offset + length;
        cursor = findStringEnd(frame, cursor, end, encoding);
        if (cursor < 0) return null;
        StringBuilder lrc = new StringBuilder();
        while (cursor + 4 <= end) {
            int textEnd = findStringEnd(frame, cursor, end, encoding);
            if (textEnd < 0 || textEnd + 4 > end) break;
            String text = decode(frame, cursor, textEnd - cursor, encoding);
            long time = beInt(frame, textEnd) & 0xFFFFFFFFL;
            cursor = textEnd + 4;
            if (text == null || text.trim().isEmpty()) continue;
            long totalSec = time / 1000;
            long hundredths = (time % 1000) / 10;
            lrc.append('[')
                    .append(String.format(Locale.US, "%02d:%02d.%02d",
                            totalSec / 60, totalSec % 60, hundredths))
                    .append(']')
                    .append(text.trim())
                    .append('\n');
        }
        return lrc.length() == 0 ? null : lrc.toString();
    }

    // ------------------------------------------------------------------
    // Ogg / Opus (Vorbis comment header)
    // ------------------------------------------------------------------

    private static String readOgg(Context context, Track track) throws IOException {
        InputStream in = open(context, track);
        if (in == null) return null;
        try {
            BufferedInputStream buffered = in instanceof BufferedInputStream
                    ? (BufferedInputStream) in : new BufferedInputStream(in, 16384);
            byte[] payload = new byte[512 * 1024];
            int payloadLength = 0;
            int pages = 0;
            while (pages < 12 && payloadLength < payload.length) {
                byte[] header = readN(buffered, 27);
                if (header == null) break;
                if (header[0] != 'O' || header[1] != 'g' || header[2] != 'g'
                        || header[3] != 'S') break;
                int segments = header[26] & 0xFF;
                byte[] lacing = readN(buffered, segments);
                if (lacing == null) break;
                int pageBytes = 0;
                for (int i = 0; i < segments; i++) pageBytes += lacing[i] & 0xFF;
                if (pageBytes <= 0) {
                    pages++;
                    continue;
                }
                if (payloadLength + pageBytes > payload.length) break;
                byte[] page = readN(buffered, pageBytes);
                if (page == null) break;
                System.arraycopy(page, 0, payload, payloadLength, pageBytes);
                payloadLength += pageBytes;
                pages++;
            }
            byte[] packetData = Arrays.copyOf(payload, payloadLength);
            String fromVorbis = findVorbisComment(packetData, payloadLength, 0x03, "vorbis");
            if (notEmpty(fromVorbis)) return fromVorbis;
            return findVorbisComment(packetData, payloadLength, 0, "OpusTags");
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String findVorbisComment(byte[] data, int length, int leadByte, String signature) {
        byte[] signatureBytes = signature.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i + 1 + signatureBytes.length < length; i++) {
            if (leadByte != 0 && (data[i] & 0xFF) != leadByte) continue;
            if (leadByte == 0 && data[i] != 0x4F) continue; // 'O' of OpusTags
            int start = leadByte == 0 ? i : i + 1;
            boolean match = true;
            for (int k = 0; k < signatureBytes.length; k++) {
                if (data[start + k] != signatureBytes[k]) {
                    match = false;
                    break;
                }
            }
            if (!match) continue;
            int commentStart = start + signatureBytes.length;
            String text = parseVorbisComments(data, commentStart);
            if (notEmpty(text)) return text;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // FLAC (Vorbis comment metadata block)
    // ------------------------------------------------------------------

    private static String readFlac(Context context, Track track) throws IOException {
        InputStream in = open(context, track);
        if (in == null) return null;
        try {
            BufferedInputStream buffered = in instanceof BufferedInputStream
                    ? (BufferedInputStream) in : new BufferedInputStream(in, 8192);
            byte[] marker = readN(buffered, 4);
            if (marker == null || marker[0] != 'f' || marker[1] != 'L'
                    || marker[2] != 'a' || marker[3] != 'C') return null;
            while (true) {
                byte[] head = readN(buffered, 4);
                if (head == null) return null;
                boolean last = (head[0] & 0x80) != 0;
                int type = head[0] & 0x7F;
                int length = ((head[1] & 0xFF) << 16) | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
                if (length < 0 || length > MAX_TAG_BYTES) return null;
                if (type == 4) {
                    byte[] block = readN(buffered, length);
                    if (block == null) return null;
                    String text = parseVorbisComments(block);
                    if (notEmpty(text)) return text;
                } else {
                    skipFully(buffered, length);
                }
                if (last) return null;
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String parseVorbisComments(byte[] block) {
        return parseVorbisComments(block, 0);
    }

    private static String parseVorbisComments(byte[] block, int startOffset) {
        try {
            int pos = startOffset;
            int vendorLen = leInt(block, pos);
            if (vendorLen < 0) return null;
            pos += 4 + vendorLen;
            int count = leInt(block, pos);
            if (count < 0) return null;
            pos += 4;
            if (count > 4096) return null;
            for (int i = 0; i < count; i++) {
                if (pos + 4 > block.length) return null;
                int len = leInt(block, pos);
                pos += 4;
                if (len < 0 || pos + len > block.length) return null;
                String comment = new String(block, pos, len, StandardCharsets.UTF_8);
                pos += len;
                int eq = comment.indexOf('=');
                if (eq <= 0) continue;
                String key = comment.substring(0, eq)
                        .toUpperCase(Locale.US).replace(" ", "").replace("_", "");
                if ("LYRICS".equals(key) || "LYRIC".equals(key)
                        || "UNSYNCEDLYRICS".equals(key) || "SYNCEDLYRICS".equals(key)) {
                    String value = comment.substring(eq + 1).trim();
                    if (notEmpty(value)) return value;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------
    // MP4 / M4A (©lyr)
    // ------------------------------------------------------------------

    private static String readMp4(Track track) {
        if (track.filePath == null) return null;
        File file = new File(track.filePath);
        if (!file.isFile()) return null;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            return walkMp4(raf, 0, raf.length(), 0);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String walkMp4(RandomAccessFile raf, long start, long end, int depth) {
        if (depth > 8) return null;
        long pos = start;
        try {
            while (pos + 8 <= end) {
                raf.seek(pos);
                long size = readU32(raf);
                byte[] typeBytes = new byte[4];
                raf.readFully(typeBytes);
                String type = new String(typeBytes, StandardCharsets.ISO_8859_1);
                long header = 8;
                if (size == 1) {
                    size = readU64(raf);
                    header = 16;
                } else if (size == 0) {
                    size = end - pos;
                }
                if (size < header || pos + size > end) return null;
                long payload = pos + header;
                long payloadEnd = pos + size;

                if ("moov".equals(type) || "udta".equals(type) || "ilst".equals(type)) {
                    String found = walkMp4(raf, payload, payloadEnd, depth + 1);
                    if (found != null) return found;
                } else if ("meta".equals(type)) {
                    String found = walkMp4(raf, payload + 4, payloadEnd, depth + 1);
                    if (found != null) return found;
                } else if (type.equals("\u00A9lyr") || "lyr".equals(type)) {
                    String found = readMp4LyricAtom(raf, payload, payloadEnd);
                    if (found != null) return found;
                }
                pos = payloadEnd;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String readMp4LyricAtom(RandomAccessFile raf, long start, long end) {
        long pos = start;
        try {
            while (pos + 8 <= end) {
                raf.seek(pos);
                long size = readU32(raf);
                byte[] typeBytes = new byte[4];
                raf.readFully(typeBytes);
                String type = new String(typeBytes, StandardCharsets.ISO_8859_1);
                long header = 8;
                if (size == 1) {
                    size = readU64(raf);
                    header = 16;
                } else if (size == 0) {
                    size = end - pos;
                }
                if (size < header || pos + size > end) return null;
                if ("data".equals(type)) {
                    long payload = pos + header;
                    long payloadEnd = pos + size;
                    if (payloadEnd - payload < 8) return null;
                    raf.seek(payload);
                    byte[] prefix = new byte[8];
                    raf.readFully(prefix);
                    int dataType = prefix[3] & 0xFF;
                    int textLen = (int) (payloadEnd - payload - 8);
                    byte[] text = new byte[textLen];
                    raf.readFully(text);
                    Charset charset = dataType == 2 ? StandardCharsets.UTF_16 : StandardCharsets.UTF_8;
                    String value = new String(text, charset).trim();
                    return notEmpty(value) ? value : null;
                }
                pos = pos + size;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------
    // WAV (RIFF "ID3 " chunk)
    // ------------------------------------------------------------------

    private static String readWav(Context context, Track track) throws IOException {
        InputStream in = open(context, track);
        if (in == null) return null;
        try {
            BufferedInputStream buffered = in instanceof BufferedInputStream
                    ? (BufferedInputStream) in : new BufferedInputStream(in, 8192);
            byte[] riff = readN(buffered, 12);
            if (riff == null || riff[0] != 'R' || riff[1] != 'I'
                    || riff[2] != 'F' || riff[3] != 'F') return null;
            while (true) {
                byte[] chunk = readN(buffered, 8);
                if (chunk == null) return null;
                String id = new String(chunk, 0, 4, StandardCharsets.ISO_8859_1);
                long size = leU32(chunk, 4);
                if (size < 0 || size > MAX_TAG_BYTES) return null;
                if ("ID3 ".equals(id) || "id3 ".equals(id)) {
                    byte[] block = readN(buffered, (int) size);
                    if (block != null) {
                        byte[] header = new byte[10];
                        header[0] = 'I';
                        header[1] = 'D';
                        header[2] = '3';
                        int bodyLen = Math.min(block.length, MAX_TAG_BYTES);
                        // The chunk already contains the full ID3 tag including
                        // its nested header; parse it as-is.
                        String text = parseId3Chunk(block);
                        if (notEmpty(text)) return text;
                    }
                } else {
                    skipFully(buffered, (int) size);
                }
                if (size % 2 == 1) skipFully(buffered, 1);
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String parseId3Chunk(byte[] block) {
        try {
            int pos = 0;
            if (block.length >= 10 && block[0] == 'I' && block[1] == 'D' && block[2] == '3') {
                int major = block[3] & 0xFF;
                int flags = block[5] & 0xFF;
                int size = syncSafe(block, 6);
                if (size <= 0 || 10 + size > block.length) return null;
                byte[] body = new byte[size];
                System.arraycopy(block, 10, body, 0, size);
                if ((flags & 0x80) != 0) body = deUnsynchronise(body);
                int framePos = 0;
                if ((flags & 0x40) != 0 && body.length >= 4) {
                    framePos = major >= 4 ? syncSafe(body, 0) : beInt(body, 0) + 4;
                }
                return major <= 2 ? null : parseId3Frames(body, framePos, major >= 4);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Small stream helpers
    // ------------------------------------------------------------------

    private static InputStream open(Context context, Track track) {
        try {
            if (track.filePath != null) {
                File file = new File(track.filePath);
                if (file.isFile()) return new FileInputStream(file);
            }
            Uri uri = track.uri;
            return uri == null || context == null
                    ? null : context.getContentResolver().openInputStream(uri);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static byte[] readN(InputStream in, int length) throws IOException {
        byte[] out = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(out, read, length - read);
            if (n < 0) return null;
            read += n;
        }
        return out;
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) return;
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    private static int findStringEnd(byte[] data, int start, int end, int encoding) {
        if (encoding == 1 || encoding == 2) {
            for (int i = start; i + 1 < end; i += 2) {
                if (data[i] == 0 && data[i + 1] == 0) return i + 2;
            }
            return -1;
        }
        for (int i = start; i < end; i++) {
            if (data[i] == 0) return i + 1;
        }
        return -1;
    }

    private static String decode(byte[] data, int start, int length, int encoding) {
        if (start < 0 || length <= 0 || start + length > data.length) return null;
        try {
            Charset charset;
            if (encoding == 1) charset = StandardCharsets.UTF_16;
            else if (encoding == 2) charset = StandardCharsets.UTF_16BE;
            else if (encoding == 3) charset = StandardCharsets.UTF_8;
            else {
                // Encoding 0 is officially Latin-1, but many Chinese taggers
                // write GBK bytes while keeping this marker. Prefer Latin-1
                // unless the byte stream clearly contains CJK text.
                String latin = new String(data, start, length, StandardCharsets.ISO_8859_1);
                if (containsHighBytes(data, start, length)) {
                    String cjk = new String(data, start, length, Charset.forName("GB18030"));
                    if (cjkRatio(cjk) > cjkRatio(latin) && !cjk.contains("\uFFFD")) {
                        return cjk.replace("\u0000", "").trim();
                    }
                }
                return latin.replace("\u0000", "").trim();
            }
            String value = new String(data, start, length, charset);
            return value.replace("\u0000", "").trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean containsHighBytes(byte[] data, int start, int length) {
        for (int i = start; i < start + length; i++) {
            if ((data[i] & 0x80) != 0) return true;
        }
        return false;
    }

    private static float cjkRatio(String value) {
        if (value == null || value.isEmpty()) return 0f;
        int cjk = 0;
        int total = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0) continue;
            total++;
            if ((c >= 0x4E00 && c <= 0x9FFF)
                    || (c >= 0x3040 && c <= 0x30FF)
                    || (c >= 0xAC00 && c <= 0xD7AF)) {
                cjk++;
            }
        }
        return total == 0 ? 0f : cjk / (float) total;
    }

    private static byte[] deUnsynchronise(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        for (int i = 0; i < data.length; i++) {
            out.write(data[i]);
            if (data[i] == (byte) 0xFF && i + 1 < data.length && data[i + 1] == 0x00) {
                i++;
            }
        }
        return out.toByteArray();
    }

    private static int syncSafe(byte[] data, int offset) {
        return ((data[offset] & 0x7F) << 21)
                | ((data[offset + 1] & 0x7F) << 14)
                | ((data[offset + 2] & 0x7F) << 7)
                | (data[offset + 3] & 0x7F);
    }

    private static int beInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static int leInt(byte[] data, int offset) {
        if (offset < 0 || offset + 4 > data.length) return -1;
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static long leU32(byte[] data, int offset) {
        return leInt(data, offset) & 0xFFFFFFFFL;
    }

    private static long readU32(RandomAccessFile raf) throws IOException {
        return ((long) (raf.readUnsignedByte()) << 24)
                | ((long) raf.readUnsignedByte() << 16)
                | ((long) raf.readUnsignedByte() << 8)
                | raf.readUnsignedByte();
    }

    private static long readU64(RandomAccessFile raf) throws IOException {
        return (readU32(raf) << 32) | readU32(raf);
    }

    private static boolean notEmpty(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
