package com.airmusic.player.library;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import com.airmusic.player.R;
import com.airmusic.player.util.Prefs;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Scans music from the user-configured folder (SAF, including USB drives) and
 * the system media library (MediaStore).
 */
public final class MusicLibrary {

    private static final String TAG = "MusicLibrary";

    public interface ScanCallback {
        void onScanFinished(List<Track> tracks, String error);
    }

    private static MusicLibrary instance;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile List<Track> cachedTracks = new ArrayList<>();
    private volatile boolean scanning;
    /** 上次扫描结果的磁盘缓存（列表 + 歌曲数），冷启动先读它，避免每次全量重扫。 */
    private static final String CACHE_FILE = "library_cache.tsv";

    private MusicLibrary() {
    }

    public static synchronized MusicLibrary getInstance() {
        if (instance == null) instance = new MusicLibrary();
        return instance;
    }

    public List<Track> getCachedTracks() {
        return cachedTracks;
    }

    /**
     * 冷启动时把上次的扫描结果读回内存：界面立刻有曲库，后台再重扫更新。
     * 没有缓存文件（首次安装）时什么都不做，走原来的扫描流程。
     */
    public synchronized void loadFromDisk(Context context) {
        if (!cachedTracks.isEmpty() || context == null) return;
        File file = new File(context.getApplicationContext().getFilesDir(), CACHE_FILE);
        if (!file.exists()) return;
        List<Track> list = new ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] f = line.split("\t", -1);
                if (f.length < 9) continue;
                try {
                    list.add(new Track(f[0].isEmpty() ? null : Uri.parse(f[0]),
                            f[1], f[2], f[3], Integer.parseInt(f[4]), Long.parseLong(f[5]),
                            f[6], f[7], f[8]));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "load cache failed", e);
            return;
        }
        if (!list.isEmpty()) {
            cachedTracks = list;
            Log.i(TAG, "loaded " + list.size() + " tracks from disk cache");
        }
    }

    /** 扫描成功后落盘，供下次冷启动秒开。 */
    private void saveToDisk(Context context, List<Track> tracks) {
        if (context == null || tracks == null) return;
        File file = new File(context.getApplicationContext().getFilesDir(), CACHE_FILE);
        try (java.io.BufferedWriter writer = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(new java.io.FileOutputStream(file),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            for (Track track : tracks) {
                writer.write(clean(track.uri == null ? "" : track.uri.toString()));
                writer.write('\t');
                writer.write(clean(track.title));
                writer.write('\t');
                writer.write(clean(track.artist));
                writer.write('\t');
                writer.write(clean(track.album));
                writer.write('\t');
                writer.write(Integer.toString(track.trackNo));
                writer.write('\t');
                writer.write(Long.toString(track.durationMs));
                writer.write('\t');
                writer.write(clean(track.folder));
                writer.write('\t');
                writer.write(clean(track.filePath));
                writer.write('\t');
                writer.write(clean(track.extension));
                writer.write('\n');
            }
        } catch (Throwable e) {
            Log.w(TAG, "save cache failed", e);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ');
    }

    public boolean isScanning() {
        return scanning;
    }

    /** Drops the cached track list so the next rescan starts clean. */
    public void clearCache() {
        cachedTracks = new ArrayList<>();
    }

    public void rescan(Context context, ScanCallback callback) {
        scanning = true;
        final Context appContext = context.getApplicationContext();
        executor.execute(() -> {
            List<Track> tracks = new ArrayList<>();
            String error = null;
            try {
                tracks = scanInternal(appContext);
            } catch (SecurityException e) {
                error = appContext.getString(R.string.library_no_permission);
                Log.e(TAG, "scan failed", e);
            } catch (Exception e) {
                error = appContext.getString(R.string.library_scan_failed,
                        e.getMessage() == null ? "" : e.getMessage());
                Log.e(TAG, "scan failed", e);
            }
            final List<Track> result = tracks;
            final String err = error;
            cachedTracks = result;
            scanning = false;
            saveToDisk(appContext, result);
            mainHandler.post(() -> {
                if (callback != null) callback.onScanFinished(result, err);
            });
        });
    }

    private List<Track> scanInternal(Context context) throws Exception {
        Map<String, Track> byUri = new LinkedHashMap<>();

        Prefs prefs = new Prefs(context);
        boolean folderScanned = false;
        String folderPath = prefs.getMusicFolderPath();
        if (folderPath != null) {
            File dir = new File(folderPath);
            if (dir.isDirectory() && dir.canRead()) {
                scanFileTree(context, dir, byUri, 0);
                folderScanned = true;
            }
        }
        if (!folderScanned) {
            String folderUri = prefs.getMusicFolderUri();
            if (folderUri != null) {
                folderScanned = scanSafTree(context, Uri.parse(folderUri), byUri, 0);
            }
        }

        // When a folder is configured, the library is that folder only;
        // otherwise fall back to the system media library (including USB).
        if (!folderScanned) {
            scanMediaStore(context, byUri);
        }

        List<Track> tracks = new ArrayList<>(byUri.values());
        Collections.sort(tracks, new Comparator<Track>() {
            @Override
            public int compare(Track a, Track b) {
                int f = compareNullable(a.folder, b.folder);
                if (f != 0) return f;
                return compareNullable(a.title, b.title);
            }
        });
        return tracks;
    }

    /** Scans a raw filesystem tree (internal storage or USB volume). */
    private void scanFileTree(Context context, File dir, Map<String, Track> byUri, int depth) {
        if (depth > 10) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                scanFileTree(context, file, byUri, depth + 1);
            } else if (file.isFile() && AudioExt.isAudio(file.getName())) {
                Track track = buildTrackFromFile(context, file, dir.getName());
                byUri.put(track.uri.toString(), track);
            }
        }
    }

    /** Reads file tags (title/artist/album/duration) when scanning folders or USB. */
    private Track buildTrackFromFile(Context context, File file, String folder) {
        String name = file.getName();
        String title = nameWithoutExt(name);
        String artist = "";
        String album = "";
        int trackNo = 0;
        long duration = 0;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            title = firstNonEmpty(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE), title);
            artist = trimToEmpty(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            album = trimToEmpty(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            trackNo = readTrackNo(retriever);
            String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) {
                try {
                    duration = Long.parseLong(d);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "metadata read failed: " + name, e);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        return new Track(Uri.fromFile(file), title, artist, album, trackNo, duration, folder,
                file.getAbsolutePath(), AudioExt.extensionOf(name));
    }

    /**
     * 音轨号：标签里可能是 "3"、"3/12" 或 "0"（0 视为没有）。
     * 读不到就返回 0，桌面端会退化回按标题排序。
     */
    private static int readTrackNo(MediaMetadataRetriever retriever) {
        try {
            String raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER);
            if (raw == null) return 0;
            String value = raw.trim();
            int slash = value.indexOf('/');
            if (slash > 0) value = value.substring(0, slash);
            int number = Integer.parseInt(value.trim());
            return number > 0 ? number : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int compareNullable(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.toLowerCase(Locale.US).compareTo(b.toLowerCase(Locale.US));
    }

    private boolean scanSafTree(Context context, Uri treeUri, Map<String, Track> byUri, int depth) {
        if (depth > 10) return true;
        DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
        if (root == null || !root.canRead()) return false;
        DocumentFile[] files = root.listFiles();
        if (files == null) return false;
        for (DocumentFile file : files) {
            if (file.isDirectory()) {
                scanSafTree(context, file.getUri(), byUri, depth + 1);
            } else if (file.isFile() && AudioExt.isAudio(file.getName())) {
                Track track = buildTrackFromSaf(context, file, root.getName());
                byUri.put(file.getUri().toString(), track);
            }
        }
        return true;
    }

    /** Reads file tags for SAF (content://) documents. */
    private Track buildTrackFromSaf(Context context, DocumentFile file, String folder) {
        String name = file.getName();
        String title = nameWithoutExt(name);
        String artist = "";
        String album = "";
        int trackNo = 0;
        long duration = 0;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, file.getUri());
            title = firstNonEmpty(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE), title);
            artist = trimToEmpty(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            album = trimToEmpty(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            trackNo = readTrackNo(retriever);
            String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) {
                try {
                    duration = Long.parseLong(d);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "metadata read failed: " + name, e);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        return new Track(file.getUri(), title, artist, album, trackNo, duration, folder,
                null, AudioExt.extensionOf(name));
    }

    private static String nameWithoutExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String firstNonEmpty(String value, String fallback) {
        return value != null && value.trim().length() > 0 ? value.trim() : fallback;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private void scanMediaStore(Context context, Map<String, Track> byUri) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        List<String> volumes = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29) {
            Set<String> extra = MediaStore.getExternalVolumeNames(context);
            for (String v : extra) {
                // "external" is only an alias for the primary volume on
                // API 29+; scanning both "external" and "external_primary"
                // would list every song twice, so only use canonical names.
                if (!volumes.contains(v)) volumes.add(v);
            }
        }
        if (volumes.isEmpty()) {
            volumes.add("external");
        }

        for (String volume : volumes) {
            queryVolume(context, volume, byUri);
        }
    }

    private void queryVolume(Context context, String volume, Map<String, Track> byUri) {
        Uri collection = MediaStore.Audio.Media.getContentUri(volume);
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
        };
        String relativePath = null;
        if (Build.VERSION.SDK_INT >= 29) {
            projection = new String[]{
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.RELATIVE_PATH,
            };
        }
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    collection, projection,
                    MediaStore.Audio.Media.IS_MUSIC + " != 0", null,
                    MediaStore.Audio.Media.TITLE + " COLLATE NOCASE");
            if (cursor == null) return;
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);
            int pathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                Uri uri = ContentUris.withAppendedId(collection, id);
                String title = cursor.getString(titleCol);
                String artist = cursor.getString(artistCol);
                String album = cursor.getString(albumCol);
                long duration = cursor.getLong(durCol);
                String data = dataCol >= 0 ? cursor.getString(dataCol) : null;
                String folder = pathCol >= 0 ? cursor.getString(pathCol) : null;
                if (folder == null && data != null) {
                    File f = new File(data);
                    folder = f.getParentFile() != null ? f.getParentFile().getName() : null;
                }
                if (title == null || title.trim().length() == 0) {
                    if (data != null) title = nameWithoutExt(new File(data).getName());
                    else continue;
                }
                Track track = new Track(
                        uri, title, artist, album, duration,
                        folder, data, AudioExt.extensionOf(data == null ? title : data));
                // Deduplicate by the underlying file path when available: the
                // same physical file can be reported under several URIs.
                String key = data != null && data.length() > 0
                        ? "path:" + data : uri.toString();
                byUri.put(key, track);
            }
        } catch (Exception e) {
            Log.e(TAG, "queryVolume failed for " + volume, e);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /**
     * Loads rich metadata (title/artist/album/duration/embedded art) for a
     * track, filling gaps left by the scan.
     */
    public static MediaMetadataRetriever openRetriever(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            return retriever;
        } catch (Exception e) {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
            return null;
        }
    }
}
