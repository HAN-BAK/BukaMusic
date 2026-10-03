package com.airmusic.player.lyrics;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AirPlay 歌词位置识别的调度器：换歌时准备参考音频，之后周期性做一次互相关，
 * 把识别出的播放位置提供给歌词页。
 *
 * <p>拿不到参考音频，或互相关置信度不足时 {@link #positionMs()} 返回 -1，
 * 歌词页据此显示「暂未找到歌词」（不做手动对齐）。
 */
public final class AirplayLyricLocator {

    private static final String TAG = "AirplayLyric";
    /** 校准间隔：不必每句都算，5 秒校一次，中间靠时钟推进。 */
    private static final long CALIBRATE_INTERVAL_MS = 5000L;
    private static final int MIN_BUFFER_SECONDS = 2;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean PREPARING = new AtomicBoolean(false);
    /** 准备代次：切歌自增，晚到的旧任务结果会被丢弃。 */
    private static volatile int prepareGeneration;

    private static volatile String currentKey;
    private static volatile short[] referencePcm;
    private static volatile long positionMs = -1L;
    /** 上一次成功定位的时刻（elapsedRealtime），用于两次校准之间按时钟推进位置。 */
    private static volatile long lastMatchAt;
    /** 当前这首歌的时长（用来给「按时钟推进」兜底封顶）。 */
    private static volatile long durationMs;
    /** 连续被合理性检查拒绝的次数（连续 2 次就强制重新对齐）。 */
    private static volatile int rejectedInARow;
    private static volatile boolean running;

    private AirplayLyricLocator() {
    }

    /** 当前识别到的播放位置（毫秒）；-1 = 还没识别出来 / 置信度不足。 */
    public static long positionMs() {
        long base = positionMs;
        if (base < 0) return -1L;
        // 两次校准之间（5 秒）按时钟推进：否则某次校准没通过置信度，歌词就会
        // 停在最后那一句不动（表现就是「切歌后卡在某句」）。
        long elapsed = android.os.SystemClock.elapsedRealtime() - lastMatchAt;
        if (elapsed < 0) elapsed = 0;
        long limit = durationMs > 0 ? durationMs : 30L * 60L * 1000L;
        long advanced = base + elapsed;
        // 不再用「8 秒硬上限」：那会导致某段校准没通过时位置冻结、歌词卡在一句上。
        // 只在超过歌曲时长（或未知时长时的 30 分钟）后停下。
        return Math.min(advanced, limit);
    }

    public static boolean ready() {
        return referencePcm != null;
    }

    /**
     * 位置失效：发送端拖动进度 / 暂停（引擎 flush）时调用。
     * 参考音频不变，但缓冲里的音频已经跳到别处，旧位置不再可信，
     * 于是把位置置为「未识别」，歌词页不会再用旧值渲染，等下一次匹配即可。
     */
    public static void invalidatePosition() {
        positionMs = -1L;
        lastMatchAt = 0L;
    }

    /** AirPlay 开始播放 / 换歌时调用：准备参考音频并启动周期校准。 */
    public static void start(final Context context, final String title, final String artist,
                             final long durationMs) {
        if (title == null || title.trim().isEmpty()) {
            stop(context);
            return;
        }
        final String key = NeteaseReferenceAudio.keyOf(title, artist);
        if (key.equals(currentKey)) {
            // 同一首歌：歌词页每次刷新都会调进来，这里只保证在校准即可，
            // 绝不能把「正在准备参考音频」的会话停掉重来（那会永远准备不完）。
            running = true;
            if (referencePcm != null) schedule();
            return;
        }
        stop(context);
        currentKey = key;
        positionMs = -1L;
        lastMatchAt = 0L;
        AirplayLyricLocator.durationMs = durationMs > 0 ? durationMs : 0L;
        lastMatchAt = 0L;
        running = true;
        // 用「代次」而不是「只允许一个准备任务」：切歌时上一个参考音频可能还在
        // 下载/解码，旧写法会直接 return（新歌永远不准备），旧任务完成后还会把
        // referencePcm 覆盖成上一首的音频——表现就是「只有第一次投送是正常的」。
        final int generation = ++prepareGeneration;
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                File file = NeteaseReferenceAudio.fetch(appContext, title, artist, durationMs);
                if (generation != prepareGeneration) return;   // 已经换歌，结果作废
                if (file == null) {
                    Log.i(TAG, "no reference audio for \"" + title + "\"");
                    return;
                }
                short[] pcm = ReferenceAudioDecoder.load(file);
                if (generation != prepareGeneration) return;
                if (pcm == null || pcm.length == 0) {
                    Log.i(TAG, "reference decode failed for \"" + title + "\"");
                    return;
                }
                referencePcm = pcm;
                Log.i(TAG, "reference ready: " + pcm.length + " samples for \"" + title + "\"");
                MAIN.post(AirplayLyricLocator::schedule);
            } catch (Throwable t) {
                Log.w(TAG, "prepare failed: " + t, t);
            }
        });
    }

    /** AirPlay 结束 / 切走时调用：清缓冲、删缓存、停止校准。 */
    public static void stop(Context context) {
        running = false;
        MAIN.removeCallbacks(AirplayLyricLocator::calibrate);
        positionMs = -1L;
        referencePcm = null;
        AirplayPcmTap.clear();
        String key = currentKey;
        currentKey = null;
        if (context != null && key != null) {
            final Context appContext = context.getApplicationContext();
            EXECUTOR.execute(() -> ReferenceAudioCache.drop(appContext, key));
        }
    }

    private static void schedule() {
        MAIN.removeCallbacks(AirplayLyricLocator::calibrate);
        Log.i(TAG, "schedule: running=" + running + " ref="
                + (referencePcm == null ? "null" : referencePcm.length));
        // 已经停了就别再排，否则会变成空转死循环
        if (!running) return;
        MAIN.postDelayed(AirplayLyricLocator::calibrate, CALIBRATE_INTERVAL_MS);
    }

    private static void calibrate() {
        final short[] reference = referencePcm;
        Log.i(TAG, "calibrate: running=" + running + " ref="
                + (reference == null ? "null" : reference.length)
                + " buffered=" + AirplayPcmTap.available());
        if (!running || reference == null) {
            // 停了就彻底停；还没准备好参考音频则过一会儿再看（由 schedule 判断）
            MAIN.post(AirplayLyricLocator::schedule);
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                short[] buffer = AirplayPcmTap.snapshot();
                Log.i(TAG, "calibrating: buffer=" + buffer.length);
                if (buffer.length < MIN_BUFFER_SECONDS * AirplayPcmTap.TARGET_RATE) {
                    return;   // 缓冲还不够，finally 里会排下一次
                }
                long found = LyricPositionLocator.locate(reference, buffer);
                if (found >= 0) {
                    long now = android.os.SystemClock.elapsedRealtime();
                    boolean hasPrevious = positionMs >= 0 && lastMatchAt > 0;
                    long expected = hasPrevious ? positionMs + (now - lastMatchAt) : found;
                    // 合理性检查：和「上次位置 + 时钟推进」差太多的，基本是互相关
                    // 认到了别的段落（重复段/相似前奏），这种宁可不用，避免歌词整体错位。
                    if (!hasPrevious || Math.abs(found - expected) <= 15000L) {
                        positionMs = found;
                        lastMatchAt = now;
                        rejectedInARow = 0;
                    } else {
                        rejectedInARow++;
                        Log.i(TAG, "reject match " + found + "ms (expected ~" + expected
                                + "ms, streak " + rejectedInARow + ")");
                        lastMatchAt = now;
                        // 连续两次被拒：说明是「记录的位置错了」而不是匹配错——发送端
                        // 拖动进度时不一定发 FLUSH（那就没被作废），这时必须采信匹配
                        // 结果重新对齐，否则歌词会永远卡在旧位置。
                        if (rejectedInARow >= 2) {
                            Log.i(TAG, "resync to " + found + "ms after " + rejectedInARow + " rejects");
                            positionMs = found;
                            lastMatchAt = now;
                            rejectedInARow = 0;
                        }
                    }
                }
            } catch (Throwable t) {
                // 线程池会吞异常，这里必须打出来，否则校准会静默死掉
                Log.w(TAG, "calibrate failed: " + t, t);
            } finally {
                MAIN.post(AirplayLyricLocator::schedule);
            }
        });
    }
}
