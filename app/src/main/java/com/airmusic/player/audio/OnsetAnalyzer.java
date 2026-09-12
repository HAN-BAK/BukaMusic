package com.airmusic.player.audio;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;

import com.airmusic.player.library.Track;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Offline vocal-onset detector.
 *
 * <p>Plain LRC only carries a start time per line. To make individual words
 * appear when they are actually sung, the track is decoded once in the
 * background, its 300-4000 Hz vocal band is tracked with a 10 ms envelope, and
 * the rising edges of that envelope are kept as onset candidates. The lyric
 * director then snaps estimated word starts to those onsets. Results are cached
 * per track, so a song is only analysed once.
 */
public final class OnsetAnalyzer {

    public interface Callback {
        void onResult(List<Long> onsetTimesMs);
    }

    /**
     * Runs at background priority: analysing a whole song decodes the file and
     * must never starve the lyric renderer (the TV box has few cores, and a
     * normal-priority analysis made the lyric transition stutter).
     */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "onset-analyzer");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY + 1);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int FRAME_MS = 10;

    private OnsetAnalyzer() {
    }

    public static void analyzeAsync(final Context context, final Track track,
                                    final Callback callback) {
        if (track == null) {
            if (callback != null) callback.onResult(null);
            return;
        }
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            List<Long> result = loadCache(appContext, track);
            if (result == null) {
                result = analyze(appContext, track);
                if (result != null) saveCache(appContext, track, result);
            }
            final List<Long> value = result;
            MAIN.post(() -> {
                if (callback != null) callback.onResult(value);
            });
        });
    }

    // ------------------------------------------------------------------
    // Decoding + envelope
    // ------------------------------------------------------------------

    private static List<Long> analyze(Context context, Track track) {
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(context, track.uri, null);
            int audioTrack = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    format = candidate;
                    break;
                }
            }
            if (audioTrack < 0 || format == null) return null;
            extractor.selectTrack(audioTrack);

            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) return null;
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44_100;
            int channelCount = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

            Biquad bandPass = Biquad.bandPass(sampleRate, 1800f, 0.8f);
            Envelope envelope = new Envelope(sampleRate);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            while (!outputDone) {
                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(10_000);
                    if (inputIndex >= 0) {
                        ByteBuffer input = codec.getInputBuffer(inputIndex);
                        int size = extractor.readSampleData(input, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outputIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outputIndex >= 0) {
                    if (info.size > 0) {
                        ByteBuffer output = codec.getOutputBuffer(outputIndex);
                        if (output != null) {
                            output.position(info.offset);
                            output.limit(info.offset + info.size);
                            consume(output, sampleRate, channelCount, bandPass, envelope);
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = codec.getOutputFormat();
                    if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        bandPass = Biquad.bandPass(sampleRate, 1800f, 0.8f);
                        envelope = new Envelope(sampleRate);
                    }
                    if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                }
            }
            return envelope.pickOnsets();
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (extractor != null) extractor.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void consume(ByteBuffer buffer, int sampleRate, int channelCount,
                                Biquad bandPass, Envelope envelope) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int bytesPerSample = 2;
        int frameBytes = bytesPerSample * Math.max(1, channelCount);
        while (buffer.remaining() >= frameBytes) {
            float mono = 0f;
            for (int c = 0; c < channelCount; c++) {
                mono += buffer.getShort() / 32768f;
            }
            mono /= Math.max(1, channelCount);
            envelope.push(bandPass.process(mono));
        }
    }

    // ------------------------------------------------------------------
    // Envelope + peak picking
    // ------------------------------------------------------------------

    private static final class Envelope {
        private final int frameSize;
        private final List<Float> flux = new ArrayList<>();
        private float sumSquares;
        private int count;
        private float previousRms;

        Envelope(int sampleRate) {
            frameSize = Math.max(16, sampleRate * FRAME_MS / 1000);
        }

        void push(float sample) {
            sumSquares += sample * sample;
            count++;
            if (count < frameSize) return;
            float rms = (float) Math.sqrt(sumSquares / count);
            flux.add(Math.max(0f, rms - previousRms));
            previousRms = rms;
            sumSquares = 0f;
            count = 0;
        }

        List<Long> pickOnsets() {
            if (flux.size() < 8) return null;
            float mean = 0f;
            for (float value : flux) mean += value;
            mean /= flux.size();
            float variance = 0f;
            for (float value : flux) variance += (value - mean) * (value - mean);
            float std = (float) Math.sqrt(variance / flux.size());
            // Higher sensitivity: the previous 0.55σ threshold missed soft
            // syllable attacks, so use 0.25σ and fall back further when a song
            // still yields very few onsets.
            float threshold = mean + std * 0.25f;

            List<Long> onsets = new ArrayList<>();
            int lastIndex = -100;
            for (int i = 1; i < flux.size() - 1; i++) {
                float value = flux.get(i);
                if (value < threshold) continue;
                if (value <= flux.get(i - 1) || value < flux.get(i + 1)) continue;
                if (i - lastIndex < 7) continue; // at least 70 ms apart
                onsets.add((long) i * FRAME_MS);
                lastIndex = i;
            }
            if (onsets.size() < 6) {
                float relaxed = mean + std * 0.10f;
                onsets.clear();
                lastIndex = -100;
                for (int i = 1; i < flux.size() - 1; i++) {
                    float value = flux.get(i);
                    if (value < relaxed) continue;
                    if (value <= flux.get(i - 1) || value < flux.get(i + 1)) continue;
                    if (i - lastIndex < 7) continue;
                    onsets.add((long) i * FRAME_MS);
                    lastIndex = i;
                }
            }
            return onsets.isEmpty() ? null : onsets;
        }
    }

    /** Simple RBJ band-pass biquad. */
    private static final class Biquad {
        private final float b0;
        private final float b1;
        private final float b2;
        private final float a1;
        private final float a2;
        private float x1;
        private float x2;
        private float y1;
        private float y2;

        private Biquad(float b0, float b1, float b2, float a1, float a2) {
            this.b0 = b0;
            this.b1 = b1;
            this.b2 = b2;
            this.a1 = a1;
            this.a2 = a2;
        }

        static Biquad bandPass(float sampleRate, float centerHz, float q) {
            double w0 = 2.0 * Math.PI * centerHz / sampleRate;
            double alpha = Math.sin(w0) / (2.0 * q);
            double a0 = 1.0 + alpha;
            return new Biquad(
                    (float) (alpha / a0),
                    0f,
                    (float) (-alpha / a0),
                    (float) (-2.0 * Math.cos(w0) / a0),
                    (float) ((1.0 - alpha) / a0));
        }

        float process(float input) {
            float output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = input;
            y2 = y1;
            y1 = output;
            return output;
        }
    }

    // ------------------------------------------------------------------
    // Cache
    // ------------------------------------------------------------------

    private static File cacheFile(Context context, Track track) {
        String key = track.filePath != null ? track.filePath : String.valueOf(track.uri);
        long modified = 0L;
        if (track.filePath != null) {
            File file = new File(track.filePath);
            if (file.isFile()) modified = file.lastModified();
        }
        // The version suffix invalidates caches produced by the older, less
        // sensitive detector.
        String hash = Integer.toHexString(
                (key + "#" + modified + "#" + track.durationMs + "#v2").hashCode());
        File dir = new File(context.getCacheDir(), "onsets");
        if (!dir.exists() && !dir.mkdirs()) return null;
        return new File(dir, hash + ".txt");
    }

    private static List<Long> loadCache(Context context, Track track) {
        File file = cacheFile(context, track);
        if (file == null || !file.isFile()) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            if (line == null || line.isEmpty()) return null;
            List<Long> values = new ArrayList<>();
            for (String part : line.split(",")) {
                if (!part.isEmpty()) values.add(Long.parseLong(part));
            }
            return values.isEmpty() ? null : values;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void saveCache(Context context, Track track, List<Long> onsets) {
        File file = cacheFile(context, track);
        if (file == null) return;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < onsets.size(); i++) {
                if (i > 0) builder.append(',');
                builder.append(onsets.get(i));
            }
            writer.write(builder.toString());
        } catch (Throwable ignored) {
        }
    }
}
