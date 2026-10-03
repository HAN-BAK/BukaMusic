package com.airmusic.player.lyrics;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * 把网易云下载的参考音频（MP3 / M4A 等）解码成 8kHz 单声道 PCM16。
 *
 * <p>结果会另存成同名 {@code .pcm} 文件，互相关每次识别都要用，解码一次即可。
 * 输出格式与 {@link AirplayPcmTap} 完全一致（8kHz / 单声道 / short），
 * 才能直接做滑动互相关。
 */
public final class ReferenceAudioDecoder {

    private static final String TAG = "RefAudioDec";
    private static final int TARGET_RATE = AirplayPcmTap.TARGET_RATE;
    /** 参考音频最长取 10 分钟，避免异常文件把内存吃满（8kHz×2B×600s ≈ 9.6MB）。 */
    private static final int MAX_SAMPLES = TARGET_RATE * 600;

    private ReferenceAudioDecoder() {
    }

    /** 取参考音频的 8kHz 单声道 PCM（优先读 .pcm 缓存），失败返回 null。 */
    public static short[] load(File source) {
        if (source == null || !source.exists() || source.length() == 0) return null;
        File pcmCache = new File(source.getAbsolutePath() + ".pcm");
        short[] cached = readPcm(pcmCache);
        if (cached != null) return cached;
        short[] decoded = decode(source);
        if (decoded != null) writePcm(pcmCache, decoded);
        return decoded;
    }

    private static short[] readPcm(File file) {
        if (!file.exists() || file.length() < 2) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] raw = new byte[(int) file.length()];
            int offset = 0;
            while (offset < raw.length) {
                int read = in.read(raw, offset, raw.length - offset);
                if (read <= 0) break;
                offset += read;
            }
            short[] out = new short[offset / 2];
            ByteBuffer.wrap(raw, 0, offset).order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer().get(out);
            return out.length == 0 ? null : out;
        } catch (Throwable t) {
            Log.d(TAG, "read pcm cache failed: " + t);
            return null;
        }
    }

    private static void writePcm(File file, short[] samples) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            ByteBuffer buffer = ByteBuffer.allocate(samples.length * 2)
                    .order(ByteOrder.LITTLE_ENDIAN);
            buffer.asShortBuffer().put(samples);
            out.write(buffer.array());
        } catch (Throwable t) {
            Log.d(TAG, "write pcm cache failed: " + t);
        }
    }

    private static short[] decode(File source) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(source.getAbsolutePath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = candidate;
                    break;
                }
            }
            if (track < 0 || format == null) return null;
            extractor.selectTrack(track);

            String mime = format.getString(MediaFormat.KEY_MIME);
            int inRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int inChannels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            if (inRate <= 0) inRate = 44100;
            if (inChannels <= 0) inChannels = 2;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            short[] out = new short[MAX_SAMPLES];
            int outCount = 0;
            double step = inRate / (double) TARGET_RATE;
            double phase = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;

            while (!outputDone && outCount < MAX_SAMPLES) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                        int size = inBuf == null ? -1 : extractor.readSampleData(inBuf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outIndex >= 0) {
                    ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        ShortBuffer shorts = outBuf.order(ByteOrder.nativeOrder()).asShortBuffer();
                        int frames = shorts.remaining() / inChannels;
                        for (int frame = 0; frame < frames; frame++) {
                            int base = frame * inChannels;
                            int sample = shorts.get(base);
                            for (int ch = 1; ch < inChannels; ch++) {
                                sample += shorts.get(base + ch);
                            }
                            sample /= inChannels;
                            phase += 1.0;
                            if (phase >= step && outCount < MAX_SAMPLES) {
                                phase -= step;
                                out[outCount++] = (short) sample;
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat changed = codec.getOutputFormat();
                    if (changed.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        inChannels = Math.max(1, changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                    }
                }
            }
            Log.i(TAG, "decoded " + source.getName() + ": rate=" + inRate + " ch=" + inChannels
                    + " pcm=" + outCount + " samples (" + (outCount / TARGET_RATE) + "s)");
            if (outCount == 0) return null;
            short[] result = new short[outCount];
            System.arraycopy(out, 0, result, 0, outCount);
            return result;
        } catch (Throwable t) {
            Log.d(TAG, "decode failed: " + t);
            return null;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignored) {
                }
                codec.release();
            }
            extractor.release();
        }
    }
}
