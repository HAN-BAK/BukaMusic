package com.airmusic.player.library;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import com.airmusic.player.net.NetClient;
import com.airmusic.player.net.RemoteFile;
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
import java.util.HashMap;
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
    /** 本轮扫描排队的回调：扫描进行中再来的请求不再另起一轮，直接并入本次。 */
    private final List<ScanCallback> pendingCallbacks = new ArrayList<>();
    /** 上次扫描结束的时刻（elapsedRealtime），用于短时间内不重复全量扫描。 */
    private volatile long lastScanFinishedAt;

    /** 网络音乐路径的扫描缓存时长：这段时间内不再重走 NAS（本地路径每次都扫，很快）。 */
    private static final long NET_SCAN_TTL_MS = 10 * 60 * 1000L;
    /** 置 true 后下一次扫描强制重扫网络（用户点「重新扫描」或改了网络路径时）。 */
    private static volatile boolean forceNetworkScan;
    /** 批量查询得到的 MediaStore 元数据（路径 → 曲目），避免逐首打开文件读标签。 */
    private final Map<String, Track> mediaStoreByPath = new HashMap<>();
    /** 本次扫描里元数据来自 MediaStore 缓存 / 退回逐首读标签的文件数（只有扫描线程用）。 */
    private int cacheHits;
    private int cacheMisses;
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

    /**
     * 距上次扫描结束不到 maxAgeMs 且没有手动要求重扫时，返回 true。
     *
     * <p>曲库页每次 onResume 都会请求刷新；以前每次都排一轮完整扫描，
     * 一进曲库就是 2~3 轮重复扫描 + 重复重建列表，看起来就是"曲库读取很慢"。
     */
    public boolean scanIsFresh(long maxAgeMs) {
        long at = lastScanFinishedAt;
        return !forceNetworkScan && at > 0
                && android.os.SystemClock.elapsedRealtime() - at < maxAgeMs;
    }

    /** 请求下一次扫描强制重走网络路径（手动重新扫描 / 增删改网络路径后调用）。 */
    public static void requestNetworkRefresh() {
        forceNetworkScan = true;
    }

    /** Drops the cached track list so the next rescan starts clean. */
    public void clearCache() {
        cachedTracks = new ArrayList<>();
    }

    public void rescan(Context context, ScanCallback callback) {
        synchronized (this) {
            if (scanning) {
                // 已经有一轮在跑：只登记回调，等这一轮结束后一起通知
                if (callback != null) pendingCallbacks.add(callback);
                return;
            }
            scanning = true;
        }
        final long scanStart = android.os.SystemClock.elapsedRealtime();
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
            saveToDisk(appContext, result);
            Log.i(TAG, "scan finished in " + (android.os.SystemClock.elapsedRealtime() - scanStart)
                    + "ms, " + result.size() + " tracks");
            mainHandler.post(() -> {
                List<ScanCallback> extra;
                synchronized (MusicLibrary.this) {
                    scanning = false;
                    lastScanFinishedAt = android.os.SystemClock.elapsedRealtime();
                    extra = new ArrayList<>(pendingCallbacks);
                    pendingCallbacks.clear();
                }
                if (callback != null) callback.onScanFinished(result, err);
                for (ScanCallback pending : extra) pending.onScanFinished(result, err);
            });
        });
    }

    private List<Track> scanInternal(Context context) throws Exception {
        final long started = android.os.SystemClock.elapsedRealtime();
        // 先把 MediaStore 的元数据一次性查出来（批量、约几十毫秒），
        // 后面扫描文件夹时直接用，只有 MediaStore 里没有的文件才逐首读标签。
        final long mediaQueryStart = android.os.SystemClock.elapsedRealtime();
        collectMediaStoreMetadata(context);
        cacheHits = 0;
        cacheMisses = 0;
        Log.i(TAG, "media metadata query took "
                + (android.os.SystemClock.elapsedRealtime() - mediaQueryStart) + "ms, "
                + mediaStoreByPath.size() + " entries");
        long phaseStart = started;
        Map<String, Track> byUri = new LinkedHashMap<>();

        Prefs prefs = new Prefs(context);
        boolean folderScanned = false;
        // 本地音乐路径：可以配多条，全部扫进来（本机路径或 SAF 目录树）
        for (MusicPath path : prefs.getLocalPaths()) {
            if (path.path != null && !path.path.isEmpty()) {
                File dir = new File(path.path);
                if (dir.isDirectory() && dir.canRead()) {
                    scanFileTree(context, dir, byUri, 0);
                    folderScanned = true;
                }
            } else if (path.uri != null && !path.uri.isEmpty()) {
                if (scanSafTree(context, Uri.parse(path.uri), byUri, 0)) folderScanned = true;
            }
        }
        // 网络音乐路径（WebDAV / SMB / FTP）：单个源连不上只记日志，不影响其它来源。
        // 网速慢的 NAS 一次要十几秒，所以 10 分钟内直接用上次结果（磁盘缓存里带着这些
        // buka-net 曲目），不再每次进曲库都重走一遍；点「重新扫描」或改网络路径会强制刷新。
        final boolean forceNet = forceNetworkScan;
        forceNetworkScan = false;
        final long now = android.os.SystemClock.elapsedRealtime();
        final boolean netFresh = !forceNet
                && prefs.getNetScanAt() > 0
                && now - prefs.getNetScanAt() < NET_SCAN_TTL_MS;
        int netTracks;
        if (netFresh) {
            netTracks = carryOverCachedNetTracks(byUri);
            Log.i(TAG, "网络路径沿用缓存：" + netTracks + " 首（"
                    + ((now - prefs.getNetScanAt()) / 1000) + " 秒前扫过）");
        } else {
            netTracks = scanNetSources(prefs, byUri);
            prefs.setNetScanAt(now);
        }

        // When a folder is configured, the library is that folder only;
        // otherwise fall back to the system media library (including USB).
        if (!folderScanned) {
            scanMediaStore(context, byUri);
        }
        // 无论是否配置了文件夹都要打点：配置了文件夹时才是最常见的路径
        final long folderMs = android.os.SystemClock.elapsedRealtime() - started;
        Log.i(TAG, "scan phases: folderOrNothing=" + folderMs + "ms mediaStore="
                + (android.os.SystemClock.elapsedRealtime() - started - folderMs) + "ms"
                + " folderScanned=" + folderScanned
                + " netTracks=" + netTracks
                + " metaCacheHits=" + cacheHits + " tagReads=" + cacheMisses);

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

    // ------------------------------------------------------------------
    // 网络音乐路径（WebDAV / SMB / FTP）
    // ------------------------------------------------------------------

    /** 扫所有网络音乐路径，返回加入的曲目数；单个源出错不影响其它源。 */
    private int scanNetSources(Prefs prefs, Map<String, Track> byUri) {
        int added = 0;
        for (NetSource source : prefs.getNetSources()) {
            final long start = android.os.SystemClock.elapsedRealtime();
            try {
                int count = scanNetDir(source, "", byUri, 0, source.displayName(), new int[]{0});
                added += count;
                Log.i(TAG, "网络路径 " + source.typeLabel() + " " + source.host
                        + " 扫到 " + count + " 首，用时 "
                        + (android.os.SystemClock.elapsedRealtime() - start) + "ms");
            } catch (Throwable t) {
                Log.w(TAG, "网络路径扫描失败（" + source.label() + "）：" + t);
            }
        }
        return added;
    }

    /** 网络扫描被缓存跳过时，把上次结果里的 buka-net 曲目搬进本次列表。 */
    private int carryOverCachedNetTracks(Map<String, Track> byUri) {
        int added = 0;
        for (Track track : cachedTracks) {
            if (track == null || track.uri == null) continue;
            if (!NetSource.SCHEME.equals(track.uri.getScheme())) continue;
            if (byUri.containsKey(track.uri.toString())) continue;
            byUri.put(track.uri.toString(), track);
            added++;
        }
        return added;
    }

    /**
     * 递归列一层网络目录。
     *
     * <p>深度、总条目数都有上限，避免某个大目录或慢服务器把整次扫描拖住；
     * 网络曲目只按扩展名判断，标签（标题/歌手/专辑）按"歌手/专辑/文件"这种常见
     * 目录结构猜一下，拿不到就留空——播放界面会用在线元数据补齐。
     */
    private int scanNetDir(NetSource source, String relativePath, Map<String, Track> byUri,
                           int depth, String rootName, int[] budget) {
        if (depth > 6 || budget[0] > 5000) return 0;
        List<RemoteFile> entries;
        try {
            entries = NetClient.list(source, relativePath);
        } catch (Throwable t) {
            Log.w(TAG, "列目录失败 " + relativePath + " -> " + t);
            return 0;
        }
        int added = 0;
        for (RemoteFile entry : entries) {
            if (budget[0] > 5000) break;
            String childPath = relativePath.isEmpty() ? entry.name
                    : relativePath + "/" + entry.name;
            budget[0]++;
            if (entry.directory) {
                added += scanNetDir(source, childPath, byUri, depth + 1, rootName, budget);
            } else if (AudioExt.isAudio(entry.name)) {
                String uri = source.trackUri(childPath).toString();
                if (byUri.containsKey(uri)) continue;
                byUri.put(uri, buildNetTrack(source, childPath, entry.name, rootName));
                added++;
            }
        }
        return added;
    }

    /** 网络曲目：标题取文件名，歌手/专辑按目录结构猜（有就填，没有就留未知）。 */
    private Track buildNetTrack(NetSource source, String relativePath, String fileName,
                                String rootName) {
        String[] parts = relativePath.split("/");
        String album = parts.length >= 2 ? parts[parts.length - 2] : "";
        String artist = parts.length >= 3 ? parts[parts.length - 3] : "";
        String display = source.typeLabel() + " · " + source.displayName() + "/" + relativePath;
        return new Track(source.trackUri(relativePath), nameWithoutExt(fileName),
                artist.isEmpty() ? Track.UNKNOWN_ARTIST : artist,
                album.isEmpty() ? Track.UNKNOWN_ALBUM : album,
                0, 0L, rootName, display, AudioExt.extensionOf(fileName));
    }

    /** Reads file tags (title/artist/album/duration) when scanning folders or USB. */
    private Track buildTrackFromFile(Context context, File file, String folder) {
        String name = file.getName();
        String title = nameWithoutExt(name);
        String artist = "";
        String album = "";
        int trackNo = 0;
        long duration = 0;
        // 先用一次批量查询拿到的 MediaStore 元数据（快），没有再逐首打开文件读标签。
        Track known = mediaStoreByPath.get(file.getAbsolutePath().toLowerCase(Locale.US));
        if (known != null) {
            cacheHits++;
            return new Track(Uri.fromFile(file), firstNonEmpty(known.title, title),
                    known.artist, known.album, known.trackNo, known.durationMs, folder,
                    file.getAbsolutePath(), AudioExt.extensionOf(name));
        }
        cacheMisses++;
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
        if (!hasAudioReadPermission(context)) return;
        for (String volume : mediaVolumes(context)) {
            queryVolume(context, volume, byUri);
        }
    }

    /** 是否已经拿到读取音频的权限（API 33 起是细分后的 READ_MEDIA_AUDIO）。 */
    private static boolean hasAudioReadPermission(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            return context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            return context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /**
     * 要查询的卷。API 29+ 用 MediaStore 给出的规范卷名（"external" 只是主卷的
     * 别名，同时用会和 "external_primary" 重复，把每首歌列两遍）。
     */
    private static List<String> mediaVolumes(Context context) {
        List<String> volumes = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29) {
            Set<String> extra = MediaStore.getExternalVolumeNames(context);
            for (String v : extra) {
                if (!volumes.contains(v)) volumes.add(v);
            }
        }
        if (volumes.isEmpty()) volumes.add("external");
        return volumes;
    }

    /**
     * 一次性批量查询 MediaStore，把「绝对路径 → 曲目」缓存起来。
     *
     * 配置了音乐文件夹时，扫描目录里的每首歌都要逐首打开文件读标签，实测 126 首
     * 要 11 秒（盒子上的 FLAC 尤其慢）。MediaStore 里其实已经有标题/歌手/专辑/
     * 音轨号/时长，一次查询几十毫秒就能拿全，命中缓存的文件就不用再开文件了；
     * 只有 MediaStore 没索引到的文件（刚拷进来还没媒体扫描、部分 U 盘卷）才回退到逐首读标签。
     */
    private void collectMediaStoreMetadata(Context context) {
        mediaStoreByPath.clear();
        if (!hasAudioReadPermission(context)) return;
        for (String volume : mediaVolumes(context)) {
            queryVolumeMetadata(context, volume);
        }
    }

    private void queryVolumeMetadata(Context context, String volume) {
        Uri collection = MediaStore.Audio.Media.getContentUri(volume);
        List<String> columns = new ArrayList<>();
        columns.add(MediaStore.Audio.Media._ID);
        columns.add(MediaStore.Audio.Media.TITLE);
        columns.add(MediaStore.Audio.Media.ARTIST);
        columns.add(MediaStore.Audio.Media.ALBUM);
        columns.add(MediaStore.Audio.Media.DURATION);
        columns.add(MediaStore.Audio.Media.TRACK);
        columns.add(MediaStore.Audio.Media.DATA);
        columns.add(MediaStore.Audio.Media.DISPLAY_NAME);
        if (Build.VERSION.SDK_INT >= 29) {
            // Android 10 起 _DATA 有时取不到，需要靠 RELATIVE_PATH + DISPLAY_NAME 拼回绝对路径。
            columns.add(MediaStore.Audio.Media.RELATIVE_PATH);
        }
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(collection,
                    columns.toArray(new String[0]),
                    MediaStore.Audio.Media.IS_MUSIC + " != 0", null, null);
            if (cursor == null) return;
            int idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
            int titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
            int artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
            int albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM);
            int durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);
            int trackCol = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK);
            int dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);
            int nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME);
            int pathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH);
            String volumeRoot = volumeRoot(volume);

            while (cursor.moveToNext()) {
                String data = dataCol >= 0 && !cursor.isNull(dataCol)
                        ? cursor.getString(dataCol) : null;
                String displayName = nameCol >= 0 && !cursor.isNull(nameCol)
                        ? cursor.getString(nameCol) : null;
                if ((data == null || data.length() == 0) && displayName != null) {
                    String relative = pathCol >= 0 && !cursor.isNull(pathCol)
                            ? cursor.getString(pathCol) : null;
                    if (relative != null && volumeRoot != null) {
                        data = volumeRoot + "/" + relative + displayName;
                    }
                }
                if (data == null || data.length() == 0) continue;

                String title = sanitize(cursor.getString(titleCol));
                if (title.length() == 0) title = nameWithoutExt(new File(data).getName());
                String artist = sanitize(cursor.getString(artistCol));
                String album = sanitize(albumCol >= 0 ? cursor.getString(albumCol) : null);
                long duration = durCol >= 0 && !cursor.isNull(durCol) ? cursor.getLong(durCol) : 0L;
                int trackNo = readMediaStoreTrackNo(
                        trackCol >= 0 && !cursor.isNull(trackCol) ? cursor.getInt(trackCol) : 0);
                Uri uri = idCol >= 0
                        ? ContentUris.withAppendedId(collection, cursor.getLong(idCol)) : null;
                if (uri == null) continue;

                File file = new File(data);
                String folder = file.getParentFile() != null
                        ? file.getParentFile().getName() : null;
                mediaStoreByPath.put(data.toLowerCase(Locale.US), new Track(
                        uri, title, artist, album, trackNo, duration, folder, data,
                        AudioExt.extensionOf(data)));
            }
        } catch (Exception e) {
            Log.e(TAG, "queryVolumeMetadata failed for " + volume, e);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /** 卷对应的挂载点，用来把 RELATIVE_PATH 拼成绝对路径。 */
    private static String volumeRoot(String volume) {
        if ("external".equals(volume) || MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(volume)) {
            return android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
        }
        return "/storage/" + volume;
    }

    /** MediaStore 用 "<unknown>" 表示没有该字段，等价于空。 */
    private static String sanitize(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.length() == 0
                || trimmed.equalsIgnoreCase(MediaStore.UNKNOWN_STRING)
                || trimmed.equalsIgnoreCase("<unknown>")) {
            return "";
        }
        return trimmed;
    }

    /** TRACK 列在多碟专辑里可能是 1003 这种「碟号*1000+音轨号」。 */
    private static int readMediaStoreTrackNo(int raw) {
        if (raw <= 0) return 0;
        return raw > 1000 ? raw % 1000 : raw;
    }

    private void queryVolume(Context context, String volume, Map<String, Track> byUri) {
        Uri collection = MediaStore.Audio.Media.getContentUri(volume);
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.TRACK,
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
                    MediaStore.Audio.Media.TRACK,
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
            int trackCol = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK);
            int dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);
            int pathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                Uri uri = ContentUris.withAppendedId(collection, id);
                String title = sanitize(cursor.getString(titleCol));
                String artist = sanitize(cursor.getString(artistCol));
                String album = sanitize(cursor.getString(albumCol));
                long duration = cursor.getLong(durCol);
                int trackNo = readMediaStoreTrackNo(
                        trackCol >= 0 && !cursor.isNull(trackCol) ? cursor.getInt(trackCol) : 0);
                String data = dataCol >= 0 ? cursor.getString(dataCol) : null;
                String folder = pathCol >= 0 ? cursor.getString(pathCol) : null;
                if (folder == null && data != null) {
                    File f = new File(data);
                    folder = f.getParentFile() != null ? f.getParentFile().getName() : null;
                }
                if (title.length() == 0) {
                    if (data != null) title = nameWithoutExt(new File(data).getName());
                    else continue;
                }
                Track track = new Track(
                        uri, title, artist, album, trackNo, duration,
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
