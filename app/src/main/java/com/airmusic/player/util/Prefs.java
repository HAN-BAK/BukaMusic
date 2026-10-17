package com.airmusic.player.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import com.airmusic.player.library.MusicPath;
import com.airmusic.player.library.NetSource;

/**
 * Central place for app settings.
 */
public final class Prefs {

    public static final String PLAY_MODE_SEQUENCE = "SEQUENCE";
    public static final String PLAY_MODE_REPEAT_ONE = "REPEAT_ONE";
    public static final String PLAY_MODE_SHUFFLE = "SHUFFLE";
    public static final String PLAY_MODE_FOLDER_LOOP = "FOLDER_LOOP";

    private static final String FILE = "airmusic_prefs";

    private static final String KEY_AIRPLAY_NAME = "airplay_name";
    private static final String KEY_MUSIC_FOLDER_URI = "music_folder_uri";
    private static final String KEY_MUSIC_FOLDER_PATH = "music_folder_path";
    private static final String KEY_MUSIC_FOLDER_DISPLAY = "music_folder_display";
    /** 本地音乐路径列表（JSON 数组），首个与上面三个旧键保持同步。 */
    private static final String KEY_LOCAL_PATHS = "local_music_paths";
    /** 网络音乐路径列表（JSON 数组）。 */
    private static final String KEY_NET_SOURCES = "net_music_sources";
    /** 上次扫描网络音乐路径的时间（elapsedRealtime），用于缓存判断。 */
    private static final String KEY_NET_SCAN_AT = "net_music_scan_at";
    private static final String KEY_LAST_UPDATE_CHECK = "last_update_check";
    private static final String KEY_PLAY_MODE = "play_mode";
    private static final String KEY_AUTO_PLAY = "auto_play_on_start";
    private static final String KEY_BALANCE = "balance";
    private static final String KEY_MULTICAST_DELAY_COMP = "multicast_delay_comp_ms";
    private static final String KEY_SHOW_APPS_BUTTON = "show_apps_button";
    private static final String KEY_BLUR_MODE = "blur_mode";
    private static final String KEY_LANGUAGE = "language";
    // ---- 屏保 ----
    private static final String KEY_SCREENSAVER_SWIPE = "screensaver_swipe";
    private static final String KEY_SCREENSAVER_BLINK = "screensaver_blink";
    private static final String KEY_SCREENSAVER_24H = "screensaver_24h";
    private static final String KEY_SCREENSAVER_FONT = "screensaver_font";
    private static final String KEY_SCREENSAVER_IDLE = "screensaver_idle_ms";
    private static final String KEY_ONBOARDING_DONE = "onboarding_done";
    private static final String KEY_ONLINE_LYRICS = "online_lyrics";
    private static final String KEY_BACKGROUND_RUN = "background_run";
    private static final String KEY_EQ_GAINS = "eq_gains";
    private static final String KEY_EQ_PRESETS = "eq_presets";

    private static final int EQ_BANDS = 10;

    private static final String KEY_LAST_TRACK_URI = "last_track_uri";
    private static final String KEY_LAST_TRACK_POSITION = "last_track_position";
    private static final String KEY_LIBRARY_GROUP = "library_group";

    private final Context context;
    private final SharedPreferences sp;

    public Prefs(Context context) {
        this.context = context.getApplicationContext();
        sp = this.context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String getAirPlayName() {
        String saved = sp.getString(KEY_AIRPLAY_NAME, null);
        if (saved != null && saved.trim().length() > 0) {
            return saved.trim();
        }
        return getSystemDeviceName();
    }

    /** The device name configured in Android system settings. */
    private String getSystemDeviceName() {
        try {
            if (Build.VERSION.SDK_INT >= 25) {
                String n = Settings.Global.getString(
                        context.getContentResolver(), Settings.Global.DEVICE_NAME);
                if (n != null && n.trim().length() > 0) return n.trim();
            }
        } catch (Throwable ignored) {
        }
        return Build.MODEL;
    }

    public void setAirPlayName(String name) {
        if (name != null && name.trim().length() > 0) {
            sp.edit().putString(KEY_AIRPLAY_NAME, name.trim()).apply();
        }
    }

    public String getMusicFolderUri() {
        return sp.getString(KEY_MUSIC_FOLDER_URI, null);
    }

    /** Raw filesystem path chosen with the built-in folder picker. */
    public String getMusicFolderPath() {
        return sp.getString(KEY_MUSIC_FOLDER_PATH, null);
    }

    public String getMusicFolderDisplay() {
        return sp.getString(KEY_MUSIC_FOLDER_DISPLAY, null);
    }

    // ------------------------------------------------------------------
    // 本地音乐路径（可多条）
    // ------------------------------------------------------------------

    /**
     * 本地音乐路径列表。
     *
     * <p>第一条会同步到上面三个旧键：音乐传输的上传目录、存储空间检查、桌面控制台的
     * "musicFolder" 等都还按旧逻辑走第一条路径，这样加多路径不会影响那些功能。
     * 旧版本只存了一条路径，这里会自动迁移成列表里的第一条。
     */
    public java.util.List<MusicPath> getLocalPaths() {
        java.util.List<MusicPath> out = new java.util.ArrayList<>();
        String raw = sp.getString(KEY_LOCAL_PATHS, null);
        if (raw != null && !raw.isEmpty()) {
            try {
                org.json.JSONArray array = new org.json.JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    MusicPath path = MusicPath.fromJson(array.optJSONObject(i));
                    if (path != null) out.add(path);
                }
            } catch (Throwable ignored) {
            }
        }
        if (out.isEmpty()) {
            String legacyPath = sp.getString(KEY_MUSIC_FOLDER_PATH, null);
            String legacyUri = sp.getString(KEY_MUSIC_FOLDER_URI, null);
            String legacyDisplay = sp.getString(KEY_MUSIC_FOLDER_DISPLAY, null);
            if (legacyPath != null && !legacyPath.isEmpty()) {
                out.add(new MusicPath(legacyPath, null, legacyDisplay));
            } else if (legacyUri != null && !legacyUri.isEmpty()) {
                out.add(new MusicPath(null, legacyUri, legacyDisplay));
            }
            if (!out.isEmpty()) saveLocalPaths(out);
        }
        return out;
    }

    public void addLocalPath(MusicPath path) {
        if (path == null) return;
        java.util.List<MusicPath> list = getLocalPaths();
        for (MusicPath existing : list) {
            if (existing.id().equals(path.id())) return;
        }
        list.add(path);
        saveLocalPaths(list);
    }

    public void removeLocalPath(String id) {
        if (id == null) return;
        java.util.List<MusicPath> list = getLocalPaths();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id().equals(id)) list.remove(i);
        }
        saveLocalPaths(list);
    }

    /**
     * 把某条本地路径设为「上传目录」：挪到列表第一条，旧键（音乐传输上传目录、
     * 存储空间检查、桌面控制台 musicFolder）都会跟着指向它。
     */
    public void setUploadPath(String id) {
        if (id == null) return;
        java.util.List<MusicPath> list = getLocalPaths();
        int index = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(id)) {
                index = i;
                break;
            }
        }
        if (index <= 0) return;
        MusicPath chosen = list.remove(index);
        list.add(0, chosen);
        saveLocalPaths(list);
    }

    /** 当前上传目录（= 本地列表第一条）的 id。 */
    public String getUploadPathId() {
        java.util.List<MusicPath> list = getLocalPaths();
        return list.isEmpty() ? null : list.get(0).id();
    }

    private void saveLocalPaths(java.util.List<MusicPath> paths) {
        org.json.JSONArray array = new org.json.JSONArray();
        for (MusicPath path : paths) {
            try {
                array.put(path.toJson());
            } catch (Throwable ignored) {
            }
        }
        android.content.SharedPreferences.Editor editor =
                sp.edit().putString(KEY_LOCAL_PATHS, array.toString());
        if (paths.isEmpty()) {
            editor.remove(KEY_MUSIC_FOLDER_PATH)
                    .remove(KEY_MUSIC_FOLDER_URI)
                    .remove(KEY_MUSIC_FOLDER_DISPLAY);
        } else {
            MusicPath first = paths.get(0);
            if (first.path != null && !first.path.isEmpty()) {
                editor.putString(KEY_MUSIC_FOLDER_PATH, first.path)
                        .remove(KEY_MUSIC_FOLDER_URI);
            } else {
                editor.remove(KEY_MUSIC_FOLDER_PATH)
                        .putString(KEY_MUSIC_FOLDER_URI, first.uri);
            }
            editor.putString(KEY_MUSIC_FOLDER_DISPLAY, first.label());
        }
        editor.apply();
    }

    // ------------------------------------------------------------------
    // 网络音乐路径（WebDAV / SMB / FTP，可多条）
    // ------------------------------------------------------------------

    public java.util.List<NetSource> getNetSources() {
        java.util.List<NetSource> out = new java.util.ArrayList<>();
        String raw = sp.getString(KEY_NET_SOURCES, null);
        if (raw == null || raw.isEmpty()) return out;
        try {
            org.json.JSONArray array = new org.json.JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                NetSource source = NetSource.fromJson(array.optJSONObject(i));
                if (source != null) out.add(source);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public NetSource getNetSource(String id) {
        if (id == null) return null;
        for (NetSource source : getNetSources()) {
            if (id.equals(source.id)) return source;
        }
        return null;
    }

    public void addNetSource(NetSource source) {
        if (source == null) return;
        java.util.List<NetSource> list = getNetSources();
        list.add(source);
        saveNetSources(list);
    }

    /** 按 id 覆盖已有条目（id 不存在时追加）。 */
    public void updateNetSource(NetSource source) {
        if (source == null) return;
        java.util.List<NetSource> list = getNetSources();
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(source.id)) {
                list.set(i, source);
                replaced = true;
                break;
            }
        }
        if (!replaced) list.add(source);
        saveNetSources(list);
    }

    public void removeNetSource(String id) {
        if (id == null) return;
        java.util.List<NetSource> list = getNetSources();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(id)) list.remove(i);
        }
        saveNetSources(list);
    }

    private void saveNetSources(java.util.List<NetSource> sources) {
        org.json.JSONArray array = new org.json.JSONArray();
        for (NetSource source : sources) {
            try {
                array.put(source.toJson());
            } catch (Throwable ignored) {
            }
        }
        sp.edit().putString(KEY_NET_SOURCES, array.toString()).apply();
    }

    /** 上次网络扫描的时刻（elapsedRealtime）；0 表示从未扫过。 */
    public long getNetScanAt() {
        return sp.getLong(KEY_NET_SCAN_AT, 0L);
    }

    public void setNetScanAt(long at) {
        sp.edit().putLong(KEY_NET_SCAN_AT, at).apply();
    }

    /** 上次检查更新的时刻（System.currentTimeMillis）。 */
    public long getLastUpdateCheck() {
        return sp.getLong(KEY_LAST_UPDATE_CHECK, 0L);
    }

    public void setLastUpdateCheck(long at) {
        sp.edit().putLong(KEY_LAST_UPDATE_CHECK, at).apply();
    }

    public void setMusicFolder(String uri, String display) {
        java.util.List<MusicPath> list = getLocalPaths();
        MusicPath path = new MusicPath(null, uri, display);
        if (list.isEmpty()) list.add(path); else list.set(0, path);
        saveLocalPaths(list);
    }

    public void setMusicFolderPath(String path, String display) {
        java.util.List<MusicPath> list = getLocalPaths();
        MusicPath entry = new MusicPath(path, null, display);
        if (list.isEmpty()) list.add(entry); else list.set(0, entry);
        saveLocalPaths(list);
    }

    public void clearMusicFolder() {
        saveLocalPaths(new java.util.ArrayList<MusicPath>());
    }

    public String getPlayMode() {
        // 首次打开（还没保存过）默认「列表循环」：装好就能一直放下去，不用先去
        // 设置里改。用户改过之后就以保存的为准。
        return sp.getString(KEY_PLAY_MODE, PLAY_MODE_FOLDER_LOOP);
    }

    public void setPlayMode(String mode) {
        sp.edit().putString(KEY_PLAY_MODE, mode).apply();
    }

    /** Library presentation remembered from last time (NONE / ALBUM / ARTIST). */
    public String getLibraryGroup() {
        return sp.getString(KEY_LIBRARY_GROUP, "NONE");
    }

    public void setLibraryGroup(String mode) {
        sp.edit().putString(KEY_LIBRARY_GROUP, mode == null ? "NONE" : mode).apply();
    }

    public boolean isAutoPlayOnStart() {
        return sp.getBoolean(KEY_AUTO_PLAY, false);
    }

    public void setAutoPlayOnStart(boolean enabled) {
        sp.edit().putBoolean(KEY_AUTO_PLAY, enabled).apply();
    }

    /** Left/right balance, -1 = full left, 0 = center, +1 = full right. */
    public float getBalance() {
        return sp.getFloat(KEY_BALANCE, 0f);
    }

    public void setBalance(float balance) {
        float clamped = Math.max(-1f, Math.min(1f, balance));
        sp.edit().putFloat(KEY_BALANCE, clamped).apply();
    }

    // ------------------------------------------------------------------ 屏保

    /**
     * 是否允许在后台运行（默认开）。
     *
     * <p>关掉后：开机不再自启、退到后台会停止播放服务（通知也消失）、
     * 被系统回收也不会自己爬起来。
     */
    public boolean isBackgroundRun() {
        return sp.getBoolean(KEY_BACKGROUND_RUN, true);
    }

    public void setBackgroundRun(boolean enabled) {
        sp.edit().putBoolean(KEY_BACKGROUND_RUN, enabled).apply();
    }

    /** 播放页上滑是否进入屏保（默认开）。 */
    public boolean isScreensaverSwipe() {
        return sp.getBoolean(KEY_SCREENSAVER_SWIPE, true);
    }

    public void setScreensaverSwipe(boolean enabled) {
        sp.edit().putBoolean(KEY_SCREENSAVER_SWIPE, enabled).apply();
    }

    /** 冒号是否闪烁。 */
    public boolean isScreensaverBlink() {
        return sp.getBoolean(KEY_SCREENSAVER_BLINK, true);
    }

    public void setScreensaverBlink(boolean enabled) {
        sp.edit().putBoolean(KEY_SCREENSAVER_BLINK, enabled).apply();
    }

    /** 是否 24 小时制（关掉用 12 小时制）。 */
    public boolean isScreensaver24h() {
        return sp.getBoolean(KEY_SCREENSAVER_24H, true);
    }

    public void setScreensaver24h(boolean enabled) {
        sp.edit().putBoolean(KEY_SCREENSAVER_24H, enabled).apply();
    }

    /** 屏保字体资源名（见 ui.ScreensaverFonts，默认 Google Sans Bold）。 */
    public String getScreensaverFont() {
        return sp.getString(KEY_SCREENSAVER_FONT,
                com.airmusic.player.ui.ScreensaverFonts.DEFAULT);
    }

    public void setScreensaverFont(String resource) {
        sp.edit().putString(KEY_SCREENSAVER_FONT,
                resource == null ? com.airmusic.player.ui.ScreensaverFonts.DEFAULT : resource)
                .apply();
    }

    /** 未播放音乐时，闲置多久自动进入屏保（毫秒；0 = 关闭）。 */
    public long getScreensaverIdleMs() {
        return sp.getLong(KEY_SCREENSAVER_IDLE, 0L);
    }

    public void setScreensaverIdleMs(long idleMs) {
        sp.edit().putLong(KEY_SCREENSAVER_IDLE, Math.max(0L, idleMs)).apply();
    }

    /** Whether the apps-list button is shown on the main playback screen. */
    public boolean isShowAppsButton() {
        return sp.getBoolean(KEY_SHOW_APPS_BUTTON, true);
    }

    public void setShowAppsButton(boolean enabled) {
        sp.edit().putBoolean(KEY_SHOW_APPS_BUTTON, enabled).apply();
    }

    public static final String BLUR_DARK = "dark";
    public static final String BLUR_OFF = "off";

    /** Player background blur: dark / light / off (default dark). */
    public String getBlurMode() {
        return sp.getString(KEY_BLUR_MODE, BLUR_DARK);
    }

    public void setBlurMode(String mode) {
        sp.edit().putString(KEY_BLUR_MODE, mode).apply();
    }

    /** App UI language: "zh", "en", "ja" or "ko". */
    public String getLanguage() {
        return sp.getString(KEY_LANGUAGE, "zh");
    }

    public void setLanguage(String lang) {
        sp.edit().putString(KEY_LANGUAGE, lang).apply();
    }

    /** True once the first-run feature tour has been shown and dismissed. */
    public boolean isOnboardingDone() {
        return sp.getBoolean(KEY_ONBOARDING_DONE, false);
    }

    public void setOnboardingDone(boolean done) {
        sp.edit().putBoolean(KEY_ONBOARDING_DONE, done).apply();
    }

    /** Look lyrics up online (by title) when nothing is stored locally. */
    public boolean isOnlineLyrics() {
        return sp.getBoolean(KEY_ONLINE_LYRICS, true);
    }

    public void setOnlineLyrics(boolean enabled) {
        sp.edit().putBoolean(KEY_ONLINE_LYRICS, enabled).apply();
    }

    // ------------------------------------------------------------------
    // Equalizer
    // ------------------------------------------------------------------

    /** Current 10-band gains in dB (default all zero). */
    public double[] getEqGains() {
        double[] gains = new double[EQ_BANDS];
        String s = sp.getString(KEY_EQ_GAINS, null);
        if (s != null) {
            try {
                JSONArray a = new JSONArray(s);
                for (int i = 0; i < EQ_BANDS && i < a.length(); i++) {
                    gains[i] = a.optDouble(i, 0);
                }
            } catch (Throwable ignored) {
            }
        }
        return gains;
    }

    public void setEqGains(double[] gains) {
        try {
            JSONArray a = new JSONArray();
            for (int i = 0; i < EQ_BANDS; i++) {
                a.put(gains != null && i < gains.length ? gains[i] : 0);
            }
            sp.edit().putString(KEY_EQ_GAINS, a.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    public java.util.List<String> getEqPresetNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString(KEY_EQ_PRESETS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                names.add(a.getJSONObject(i).optString("name", ""));
            }
        } catch (Throwable ignored) {
        }
        return names;
    }

    public double[] getEqPresetGains(String name) {
        double[] gains = new double[EQ_BANDS];
        try {
            JSONArray a = new JSONArray(sp.getString(KEY_EQ_PRESETS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (name.equals(o.optString("name"))) {
                    JSONArray g = o.optJSONArray("gains");
                    if (g != null) {
                        for (int j = 0; j < EQ_BANDS && j < g.length(); j++) {
                            gains[j] = g.optDouble(j, 0);
                        }
                    }
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        return gains;
    }

    /** Adds a preset; returns false when the name already exists. */
    public boolean addEqPreset(String name, double[] gains) {
        if (name == null || name.trim().length() == 0) return false;
        name = name.trim();
        try {
            JSONArray a = new JSONArray(sp.getString(KEY_EQ_PRESETS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                if (name.equals(a.getJSONObject(i).optString("name"))) return false;
            }
            JSONObject o = new JSONObject();
            o.put("name", name);
            JSONArray g = new JSONArray();
            for (int i = 0; i < EQ_BANDS; i++) {
                g.put(gains != null && i < gains.length ? gains[i] : 0);
            }
            o.put("gains", g);
            a.put(o);
            sp.edit().putString(KEY_EQ_PRESETS, a.toString()).apply();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void removeEqPreset(String name) {
        try {
            JSONArray a = new JSONArray(sp.getString(KEY_EQ_PRESETS, "[]"));
            JSONArray b = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                if (!name.equals(a.getJSONObject(i).optString("name"))) {
                    b.put(a.getJSONObject(i));
                }
            }
            sp.edit().putString(KEY_EQ_PRESETS, b.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** Exports all presets as a JSON string (for file sharing). */
    public String exportEqPresets() {
        return sp.getString(KEY_EQ_PRESETS, "[]");
    }

    /** Imports presets, replacing same-name entries. Returns count added. */
    public int importEqPresets(String json) {
        int added = 0;
        try {
            JSONArray incoming = new JSONArray(json);
            JSONArray current = new JSONArray(sp.getString(KEY_EQ_PRESETS, "[]"));
            for (int i = 0; i < incoming.length(); i++) {
                JSONObject o = incoming.getJSONObject(i);
                String name = o.optString("name", "");
                if (name.length() == 0) continue;
                JSONArray b = new JSONArray();
                boolean replaced = false;
                for (int j = 0; j < current.length(); j++) {
                    JSONObject cur = current.getJSONObject(j);
                    if (name.equals(cur.optString("name"))) {
                        b.put(o);
                        replaced = true;
                    } else {
                        b.put(cur);
                    }
                }
                if (!replaced) b.put(o);
                current = b;
                added++;
            }
            sp.edit().putString(KEY_EQ_PRESETS, current.toString()).apply();
        } catch (Throwable ignored) {
        }
        return added;
    }

    /** Receiver-side latency compensation for multi-room sync (-500..500 ms). */
    public int getMulticastDelayCompMs() {
        return sp.getInt(KEY_MULTICAST_DELAY_COMP, 0);
    }

    public void setMulticastDelayCompMs(int ms) {
        int clamped = Math.max(-500, Math.min(500, ms));
        sp.edit().putInt(KEY_MULTICAST_DELAY_COMP, clamped).apply();
    }

    public String getLastTrackUri() {
        return sp.getString(KEY_LAST_TRACK_URI, null);
    }

    public int getLastTrackPosition() {
        return sp.getInt(KEY_LAST_TRACK_POSITION, 0);
    }

    public void setLastTrack(String uri, int positionMs) {
        sp.edit()
            .putString(KEY_LAST_TRACK_URI, uri)
            .putInt(KEY_LAST_TRACK_POSITION, positionMs)
            .apply();
    }
}
