package com.airmusic.player.lyrics;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * AirPlay 歌词位置识别用的「参考音频」缓存。
 *
 * <p>识别时要用当前这首歌的完整音频（从网易云拉取）和 AirPlay 收到的 PCM 做
 * 互相关，所以需要把参考音频落盘。上限 60MB，超过就删最久没用的；切歌 /
 * 退出 AirPlay 时把上一首删掉。
 */
public final class ReferenceAudioCache {

    /** 缓存上限 60MB。 */
    public static final long MAX_BYTES = 60L * 1024 * 1024;

    private static final String DIR = "airplay_ref";

    private ReferenceAudioCache() {
    }

    private static File dir(Context context) {
        File dir = new File(context.getCacheDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            android.util.Log.w("RefAudioCache", "cannot create " + dir);
        }
        return dir;
    }

    /** 这首歌的参考音频文件（可能还不存在）。 */
    public static File fileFor(Context context, String key) {
        String safe = Integer.toHexString(key == null ? 0 : key.hashCode());
        return new File(dir(context), safe + ".bin");
    }

    /** 写入参考音频（覆盖同名文件），并做一次容量清理。 */
    public static File put(Context context, String key, byte[] data) {
        if (data == null || data.length == 0) return null;
        File target = fileFor(context, key);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
            out.write(data);
        } catch (Exception e) {
            android.util.Log.w("RefAudioCache", "write failed", e);
            return null;
        }
        trim(context, key);
        return target;
    }

    /** 切歌 / 退出 AirPlay：把上一首的参考音频删掉。 */
    public static void drop(Context context, String key) {
        File file = fileFor(context, key);
        if (file.exists() && !file.delete()) {
            android.util.Log.w("RefAudioCache", "cannot delete " + file);
        }
    }

    /** 清空整个缓存目录。 */
    public static void clear(Context context) {
        File[] files = dir(context).listFiles();
        if (files == null) return;
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    public static long size(Context context) {
        File[] files = dir(context).listFiles();
        if (files == null) return 0;
        long total = 0;
        for (File file : files) total += file.length();
        return total;
    }

    /** 超过上限时按「最后修改时间」从旧到新删，keepKey 对应的文件永远保留。 */
    private static void trim(Context context, String keepKey) {
        File[] files = dir(context).listFiles();
        if (files == null || files.length == 0) return;
        List<File> sorted = new ArrayList<>(Arrays.asList(files));
        sorted.sort(Comparator.comparingLong(File::lastModified));
        String keepName = fileFor(context, keepKey).getName();
        long total = 0;
        for (File file : sorted) total += file.length();
        for (File file : sorted) {
            if (total <= MAX_BYTES) break;
            if (file.getName().equals(keepName)) continue;
            long length = file.length();
            if (file.delete()) total -= length;
        }
    }
}
