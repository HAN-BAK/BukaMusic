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

    private static volatile String currentKey;
    private static volatile short[] referencePcm;
    private static volatile long positionMs = -1L;
    /** 上一次成功定位的时刻（elapsedRealtime），用于两次校准之间按时钟推进位置。 */
    private static volatile long lastMatchAt;
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
        if (elapsed > 8000L) elapsed = 8000L;   // 长时间没校准就别再猜了
        return base + elapsed;
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
        running = true;
        if (!PREPARING.compareAndSet(false, true)) return;
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                File file = NeteaseReferenceAudio.fetch(appContext, title, artist, durationMs);
                if (file == null) {
                    Log.i(TAG, "no reference audio for \"" + title + "\"");
                    return;
                }
                short[] pcm = ReferenceAudioDecoder.load(file);
                if (pcm == null || pcm.length == 0) {
                    Log.i(TAG, "reference decode failed for \"" + title + "\"");
                    return;
                }
                referencePcm = pcm;
                Log.i(TAG, "reference ready: " + pcm.length + " samples for \"" + title + "\"");
                MAIN.post(AirplayLyricLocator::schedule);
            } finally {
                PREPARING.set(false);
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
                    positionMs = found;
                    lastMatchAt = android.os.SystemClock.elapsedRealtime();
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
