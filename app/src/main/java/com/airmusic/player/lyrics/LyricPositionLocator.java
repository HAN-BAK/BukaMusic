package com.airmusic.player.lyrics;

import android.util.Log;

/**
 * AirPlay 歌词位置识别：用「参考音频」和 AirPlay 收到的 PCM 做滑动互相关，
 * 算出当前播放到歌曲的哪个位置。
 *
 * <p>两段都是 8kHz 单声道（{@link AirplayPcmTap} 与 {@link ReferenceAudioDecoder}
 * 输出格式一致）。做法：
 * <ol>
 *   <li>取采集缓冲的最后 3 秒作为模板；</li>
 *   <li>在参考音频里粗搜（步长 8 样本）再精搜（±8 样本）算归一化互相关；</li>
 *   <li>峰值位置 + 模板长度 = 缓冲结尾对应的歌曲位置；</li>
 *   <li>峰值 / 次峰（排除峰值邻域）作为置信度，太低就认为没匹配上。</li>
 * </ol>
 */
public final class LyricPositionLocator {

    private static final String TAG = "LyricPos";
    /** 用缓冲最后几秒做模板（太长会拖慢，太短容易误匹配）。 */
    private static final int TAIL_SECONDS = 3;
    /** 置信度阈值：峰值至少是次峰的 1.6 倍。 */
    private static final double MIN_CONFIDENCE = 1.6;
    /**
     * 粗搜的降采样倍数：先在 1kHz 上找大概位置，再回全分辨率精修。
     * 直接在 8kHz 上暴力搜 200 多秒的参考音频是几十亿次乘加，盒子要跑几分钟，
     * 表现就是「校准永远没有结果」。
     */
    private static final int COARSE_DECIMATE = 8;

    private LyricPositionLocator() {
    }

    /**
     * @param reference 参考音频的 8kHz 单声道 PCM
     * @param buffer    AirPlay 采集缓冲（按时间顺序，8kHz 单声道）
     * @return 当前播放位置（毫秒）；匹配不上或置信度不足返回 -1
     */
    public static long locate(short[] reference, short[] buffer) {
        if (reference == null || buffer == null) return -1;
        int tail = Math.min(TAIL_SECONDS * AirplayPcmTap.TARGET_RATE, buffer.length);
        if (tail < AirplayPcmTap.TARGET_RATE) return -1;          // 缓冲不足 1 秒
        if (reference.length < tail + AirplayPcmTap.TARGET_RATE) return -1;
        int bufferOffset = buffer.length - tail;
        int lastStart = reference.length - tail;

        // 第一级：8 倍降采样后的粗搜（参考与模板都按同一相位抽取，位置按倍数还原）
        short[] refCoarse = decimate(reference, COARSE_DECIMATE);
        short[] bufCoarse = decimate(buffer, COARSE_DECIMATE, bufferOffset);
        int coarseTail = tail / COARSE_DECIMATE;
        int coarseLast = refCoarse.length - coarseTail;
        double best = -2;
        int bestCoarse = -1;
        double second = -2;
        for (int start = 0; start <= coarseLast; start++) {
            double score = score(refCoarse, start, bufCoarse, 0, coarseTail);
            if (score > best) {
                second = best;
                best = score;
                bestCoarse = start;
            } else if (score > second) {
                second = score;
            }
        }
        if (bestCoarse < 0) return -1;

        // 第二级：粗位置附近 ±2 个降采样点，用全分辨率精修
        int window = 2 * COARSE_DECIMATE;
        int from = Math.max(0, bestCoarse * COARSE_DECIMATE - window);
        int to = Math.min(lastStart, bestCoarse * COARSE_DECIMATE + window);
        int bestStart = from;
        for (int start = from; start <= to; start++) {
            double score = score(reference, start, buffer, bufferOffset, tail);
            if (score > best) {
                best = score;
                bestStart = start;
            }
        }
        double confidence = second <= 0 ? 99 : best / second;
        long positionMs = (long) ((bestStart + tail) * 1000L / AirplayPcmTap.TARGET_RATE);
        Log.i(TAG, "match: ref=" + reference.length + " tail=" + tail
                + " score=" + String.format("%.3f", best)
                + " confidence=" + String.format("%.2f", confidence)
                + " position=" + positionMs + "ms");
        if (best < 0.5 || confidence < MIN_CONFIDENCE) return -1;
        return positionMs;
    }

    /** 归一化互相关（-1~1），两段长度相同。 */
    private static double score(short[] ref, int refStart, short[] buf, int bufStart, int length) {
        double dot = 0;
        double refEnergy = 0;
        double bufEnergy = 0;
        for (int i = 0; i < length; i++) {
            double a = ref[refStart + i];
            double b = buf[bufStart + i];
            dot += a * b;
            refEnergy += a * a;
            bufEnergy += b * b;
        }
        double denom = Math.sqrt(refEnergy * bufEnergy);
        return denom <= 0 ? 0 : dot / denom;
    }

    /** 按固定步长抽取（起始相位固定），用于粗搜。 */
    private static short[] decimate(short[] source, int factor) {
        return decimate(source, factor, 0);
    }

    private static short[] decimate(short[] source, int factor, int offset) {
        int count = Math.max(0, (source.length - offset) / factor);
        short[] out = new short[count];
        for (int i = 0; i < count; i++) {
            out[i] = source[offset + i * factor];
        }
        return out;
    }
}
