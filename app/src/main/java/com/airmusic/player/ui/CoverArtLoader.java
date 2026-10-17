package com.airmusic.player.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.airmusic.player.R;
import com.airmusic.player.library.CoverArt;
import com.airmusic.player.library.MusicLibrary;
import com.airmusic.player.library.Track;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads embedded album art for library cards off the main thread, with an
 * in-memory cache so scrolling the album / artist grid never re-reads a file
 * twice.
 */
public final class CoverArtLoader {

    private static final int TARGET_PX = 512;
    /**
     * 全进程共享的封面缓存（不是每个页面一份）。
     *
     * <p>原来 32MB 且随页面销毁清空：337 张专辑一滑就全部被挤出去，滑回来只能重新
     * 解码，看起来就是"滑到哪儿才加载哪儿"。现在 96MB、跨页面复用，仍是有上限的
     * LRU，不会无限增长。单张 512px 位图约 1MB，大致能稳住上百张封面。
     */
    private static final int CACHE_BYTES = 96 * 1024 * 1024;
    private static LruCache<String, Bitmap> sharedCache;

    private final Context context;
    private final LruCache<String, Bitmap> cache;
    private final ExecutorService pool = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "cover-art");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final Handler main = new Handler(Looper.getMainLooper());

    public CoverArtLoader(Context context) {
        this.context = context.getApplicationContext();
        this.cache = sharedCache();
    }

    private static synchronized LruCache<String, Bitmap> sharedCache() {
        if (sharedCache == null) {
            sharedCache = new LruCache<String, Bitmap>(CACHE_BYTES) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value == null ? 0 : value.getByteCount();
                }
            };
        }
        return sharedCache;
    }

    /** Cache key for one album / artist group. */
    public static String keyOf(String groupKey, Track first) {
        if (first == null) return groupKey;
        if (first.filePath != null && first.filePath.length() > 0) return first.filePath;
        return first.uri == null ? groupKey : first.uri.toString();
    }

    /**
     * 页面销毁时调用：停掉线程池并清空缓存。
     *
     * <p>线程池不关的话，每进一次曲库就留下 3 条常驻线程；缓存不清会让
     * 已经结束的页面继续攥着最多 32MB 的封面位图（位图占的是 native 内存，
     * 攒起来就是整机卡死）。
     */
    public void close() {
        try {
            pool.shutdownNow();
        } catch (Throwable ignored) {
        }
        // 缓存是跨页面共享的，这里不清空（有上限的 LRU，留着下次进曲库直接命中）。
    }

    /**
     * Loads the cover of one album / artist. The group's files are tried in
     * order because a single album often has the picture in only some of its
     * files.
     */
    public void load(String key, List<Track> candidates, ImageView view, int placeholderRes) {
        if (view == null) return;
        Bitmap cached = cache.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setTag(R.id.card_art, key);
        view.setImageResource(placeholderRes);
        if (candidates == null || candidates.isEmpty()) return;
        pool.execute(() -> {
            Bitmap art = decodeGroup(candidates);
            main.post(() -> {
                Object tag = view.getTag(R.id.card_art);
                if (!key.equals(tag)) return;
                if (art != null) {
                    cache.put(key, art);
                    view.setImageBitmap(art);
                } else {
                    view.setImageResource(placeholderRes);
                }
            });
        });
    }

    private Bitmap decodeGroup(List<Track> candidates) {
        int tries = 0;
        for (Track track : candidates) {
            Bitmap art = decode(track);
            if (art != null) return art;
            if (++tries >= CoverArt.MAX_TRIES) break;
        }
        return null;
    }

    private Bitmap decode(Track track) {
        MediaMetadataRetriever retriever = MusicLibrary.openRetriever(context, track.uri);
        if (retriever == null) return null;
        try {
            byte[] bytes = retriever.getEmbeddedPicture();
            if (bytes == null || bytes.length == 0) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= TARGET_PX
                    || bounds.outHeight / (sample * 2) >= TARGET_PX) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }
}
