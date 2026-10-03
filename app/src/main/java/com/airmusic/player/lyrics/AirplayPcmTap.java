package com.airmusic.player.lyrics;

/**
 * AirPlay 音频的 PCM 采集缓冲。
 *
 * <p>在 AirPlay 引擎把解码后的 PCM16 写进 AudioTrack 之前调用 {@link #push}，
 * 这里统一降采样成 8kHz 单声道存进环形缓冲，只保留最近几秒，供歌词位置识别
 * （与网易云参考音频做互相关）使用。占用很小：8kHz×2B ≈ 16KB/秒。
 *
 * <p>切歌 / 断开 AirPlay 时调用 {@link #clear()}，会话号 {@link #session()}
 * 用于丢弃上一首歌迟到的数据。
 */
public final class AirplayPcmTap {

    /** 目标采样率：互相关只需要低频轮廓，8kHz 足够且快得多。 */
    public static final int TARGET_RATE = 8000;
    /** 保留时长（秒）。 */
    private static final int KEEP_SECONDS = 6;
    private static final int CAPACITY = TARGET_RATE * KEEP_SECONDS;

    private static final Object LOCK = new Object();
    private static final short[] RING = new short[CAPACITY];
    private static int writeIndex;
    private static int filled;
    private static int session;
    /** 上一次输入的重采样相位，保证块与块之间连续。 */
    private static double phase;
    private static int lastRate;

    private AirplayPcmTap() {
    }

    /** 开始新的采集会话（切歌 / 重新连接）。 */
    public static int nextSession() {
        synchronized (LOCK) {
            session++;
            writeIndex = 0;
            filled = 0;
            phase = 0;
            lastRate = 0;
            return session;
        }
    }

    public static int session() {
        synchronized (LOCK) {
            return session;
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            writeIndex = 0;
            filled = 0;
            phase = 0;
        }
    }

    /**
     * 送入一段解码后的 PCM16 小端数据。
     *
     * @param pcm     PCM16 little-endian
     * @param length  有效字节数
     * @param rate    采样率（例如 44100）
     * @param channels 声道数（1 或 2）
     */
    public static void push(byte[] pcm, int length, int rate, int channels) {
        if (pcm == null || length <= 0 || rate <= 0 || channels <= 0) return;
        synchronized (LOCK) {
            if (lastRate != rate) {
                lastRate = rate;
                phase = 0;
            }
            int frames = length / (2 * channels);
            double step = rate / (double) TARGET_RATE;
            double position = phase;
            for (int frame = 0; frame < frames; frame++) {
                if (position >= step) {
                    position -= step;
                    int index = frame * 2 * channels;
                    int sample = (short) ((pcm[index] & 0xFF) | (pcm[index + 1] << 8));
                    if (channels > 1) {
                        int right = (short) ((pcm[index + 2] & 0xFF) | (pcm[index + 3] << 8));
                        sample = (sample + right) / 2;
                    }
                    RING[writeIndex] = (short) sample;
                    writeIndex = (writeIndex + 1) % CAPACITY;
                    if (filled < CAPACITY) filled++;
                }
                position += 1.0;
            }
            phase = position;
            // 采集诊断：每跨过 1 秒样本打一条日志，用来确认盒子上拿到的是有效音频
            int seconds = filled / TARGET_RATE;
            if (seconds != lastLoggedSeconds) {
                lastLoggedSeconds = seconds;
                android.util.Log.i("AirplayPcmTap", "session=" + session + " rate=" + rate
                        + " ch=" + channels + " buffered=" + seconds + "s"
                        + " last=" + RING[(writeIndex - 1 + CAPACITY) % CAPACITY]);
            }
        }
    }

    private static int lastLoggedSeconds = -1;

    /** 已缓存的有效样本数（8kHz 单声道）。 */
    public static int available() {
        synchronized (LOCK) {
            return filled;
        }
    }

    /** 拷贝出当前的缓冲内容（按时间顺序）。 */
    public static short[] snapshot() {
        synchronized (LOCK) {
            short[] out = new short[filled];
            int start = (writeIndex - filled + CAPACITY) % CAPACITY;
            for (int i = 0; i < filled; i++) {
                out[i] = RING[(start + i) % CAPACITY];
            }
            return out;
        }
    }
}
