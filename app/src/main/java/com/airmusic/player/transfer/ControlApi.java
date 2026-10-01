package com.airmusic.player.transfer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.airmusic.player.library.MusicLibrary;
import com.airmusic.player.library.Track;
import com.airmusic.player.service.PlaybackService;
import com.airmusic.player.util.PlayerUiState;
import com.airmusic.player.util.Prefs;
import com.airmusic.player.util.StateBus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * JSON control API for the desktop companion app, served by
 * {@link MusicTransferServer} on the same port as the web upload page.
 *
 * <p>Every endpoint answers JSON (except the cover image):
 * <pre>
 *   GET  /api/info       device + library summary (also used by UDP discovery)
 *   GET  /api/state      live playback state
 *   GET  /api/settings   all settings
 *   POST /api/settings   partial settings update
 *   GET  /api/library    track list
 *   POST /api/control    {action, value} playback control
 *   POST /api/delete     {paths:[...]} delete songs and rescan
 *   GET  /api/cover      current cover art (JPEG) or 404
 *   GET  /api/cover?path=<file>  that track's embedded cover (library cards)
 * </pre>
 */
public final class ControlApi {

    private static final String TAG = "ControlApi";
    private static final int API_VERSION = 1;
    private static final int COVER_SIZE = 500;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ControlApi() {
    }

    /** Handles the request; returns false when the path is not an API route. */
    public static boolean handle(Context context, String method, String path,
                                 byte[] body, OutputStream out) throws IOException {
        return handle(context, method, path, body, out, null);
    }

    /** Same as above, with the request's Range header (used by /api/file). */
    public static boolean handle(Context context, String method, String path,
                                 byte[] body, OutputStream out, String rangeHeader)
            throws IOException {
        if (path == null || !path.startsWith("/api/")) return false;
        String route = path.substring(5);
        String query = "";
        int question = route.indexOf('?');
        if (question >= 0) {
            query = route.substring(question + 1);
            route = route.substring(0, question);
        }
        try {
            switch (route) {
                case "info":
                    sendJson(out, 200, infoJson(context));
                    return true;
                case "state":
                    sendJson(out, 200, stateJson(context));
                    return true;
                case "library":
                    sendJson(out, 200, libraryJson(context));
                    return true;
                case "lyrics":
                    sendJson(out, 200, lyricsJson(context));
                    return true;
                case "multicast":
                    if ("POST".equals(method)) {
                        sendJson(out, 200, applyMulticast(body));
                    } else {
                        sendJson(out, 200, multicastJson());
                    }
                    return true;
                case "settings":
                    if ("POST".equals(method)) {
                        sendJson(out, 200, applySettings(context, body));
                    } else {
                        sendJson(out, 200, settingsJson(context));
                    }
                    return true;
                case "control":
                    sendJson(out, 200, control(body));
                    return true;
                case "delete":
                    sendJson(out, 200, deleteFiles(context, body));
                    return true;
                case "cover":
                    sendCover(context, out, query);
                    return true;
                case "file":
                    sendFile(context, out, query, rangeHeader);
                    return true;
                default:
                    sendJson(out, 404, error("unknown route"));
                    return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "api failed: " + route, t);
            sendJson(out, 500, error(String.valueOf(t.getMessage())));
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Read endpoints
    // ------------------------------------------------------------------

    public static JSONObject infoJson(Context context) {
        Prefs prefs = new Prefs(context);
        JSONObject json = new JSONObject();
        try {
            json.put("apiVersion", API_VERSION);
            json.put("name", prefs.getAirPlayName());
            json.put("model", Build.MODEL == null ? "" : Build.MODEL);
            json.put("manufacturer", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER);
            json.put("android", Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE);
            String version = "";
            try {
                version = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionName;
            } catch (Throwable ignored) {
            }
            json.put("appVersion", version);
            json.put("ip", localIpAddress());
            json.put("musicFolder", prefs.getMusicFolderDisplay());
            json.put("playMode", prefs.getPlayMode());
            MusicLibrary library = MusicLibrary.getInstance();
            List<Track> tracks = library.getCachedTracks();
            json.put("trackCount", tracks == null ? 0 : tracks.size());
        } catch (Throwable ignored) {
        }
        return json;
    }

    private static JSONObject stateJson(Context context) {
        PlayerUiState state = StateBus.get().getState();
        JSONObject json = new JSONObject();
        try {
            json.put("source", state == null ? "IDLE" : state.source.name());
            json.put("playing", state != null && state.playing);
            // File path of the local track, so the desktop library can mark the
            // row that is playing right now.
            PlaybackService service = PlaybackService.getInstance();
            Track current = service == null ? null : service.getCurrentTrack();
            json.put("path", current == null || current.filePath == null
                    ? "" : current.filePath);
            json.put("title", state == null ? "" : nullToEmpty(state.title));
            json.put("artist", state == null ? "" : nullToEmpty(state.artist));
            json.put("album", state == null ? "" : nullToEmpty(state.album));
            json.put("positionMs", state == null ? 0 : state.positionMs);
            json.put("durationMs", state == null ? 0 : state.durationMs);
            json.put("mode", state == null || state.mode == null
                    ? "SEQUENCE" : state.mode.key);
            json.put("statusText", state == null ? "" : nullToEmpty(state.statusText));
            json.put("clientName", state == null ? "" : nullToEmpty(state.clientName));
            // Which screen the device is showing ("lyrics" / "main"), so the
            // desktop console can offer the matching button.
            json.put("screen", com.airmusic.player.LyricsActivity.visible ? "lyrics" : "main");
            json.put("hasCover", state != null && state.art != null);
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio != null) {
                json.put("volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC));
                json.put("volumeMax", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            }
        } catch (Throwable ignored) {
        }
        return json;
    }

    private static JSONObject settingsJson(Context context) {
        Prefs prefs = new Prefs(context);
        JSONObject json = new JSONObject();
        try {
            json.put("deviceName", prefs.getAirPlayName());
            json.put("musicFolder", prefs.getMusicFolderPath());
            json.put("musicFolderDisplay", prefs.getMusicFolderDisplay());
            json.put("playMode", prefs.getPlayMode());
            json.put("autoPlayOnStart", prefs.isAutoPlayOnStart());
            json.put("balance", prefs.getBalance());
            json.put("showAppsButton", prefs.isShowAppsButton());
            json.put("blurMode", prefs.getBlurMode());
            json.put("language", prefs.getLanguage());
            json.put("onlineLyrics", prefs.isOnlineLyrics());
            double[] gains = prefs.getEqGains();
            JSONArray eq = new JSONArray();
            if (gains != null) {
                for (double gain : gains) eq.put(gain);
            }
            json.put("eqGains", eq);
            // Centre frequencies + the presets saved on the device, so the
            // desktop equalizer can label its sliders and offer the same list.
            JSONArray frequencies = new JSONArray();
            for (double frequency
                    : com.airmusic.player.playback.FirEqualizer.CENTER_FREQS) {
                frequencies.put(frequency);
            }
            json.put("eqFrequencies", frequencies);
            JSONArray presets = new JSONArray();
            for (String name : prefs.getEqPresetNames()) {
                JSONObject preset = new JSONObject();
                preset.put("name", name);
                JSONArray gainsArray = new JSONArray();
                double[] presetGains = prefs.getEqPresetGains(name);
                if (presetGains != null) {
                    for (double gain : presetGains) gainsArray.put(gain);
                }
                preset.put("gains", gainsArray);
                presets.put(preset);
            }
            json.put("eqPresets", presets);
        } catch (Throwable ignored) {
        }
        return json;
    }

    private static JSONObject libraryJson(Context context) {
        MusicLibrary library = MusicLibrary.getInstance();
        List<Track> tracks = library.getCachedTracks();
        if (tracks == null || tracks.isEmpty()) {
            PlaybackService service = PlaybackService.getInstance();
            if (service != null) service.rescanLibrary();
        }
        String root = new Prefs(context).getMusicFolderPath();
        JSONArray array = new JSONArray();
        JSONObject json = new JSONObject();
        try {
            if (tracks != null) {
                for (Track track : tracks) {
                    JSONObject item = new JSONObject();
                    item.put("path", track.filePath == null ? "" : track.filePath);
                    item.put("relativePath", relativize(root, track.filePath));
                    item.put("name", track.filePath == null ? ""
                            : new File(track.filePath).getName());
                    item.put("title", nullToEmpty(track.title));
                    item.put("artist", nullToEmpty(track.artist));
                    item.put("album", nullToEmpty(track.album));
                    item.put("durationMs", track.durationMs);
                    item.put("folder", nullToEmpty(track.folder));
                    item.put("extension", nullToEmpty(track.extension));
                    File file = track.filePath == null ? null : new File(track.filePath);
                    item.put("sizeBytes", file != null && file.isFile() ? file.length() : 0L);
                    array.put(item);
                }
            }
            json.put("scanning", tracks == null || tracks.isEmpty());
            json.put("tracks", array);
        } catch (Throwable ignored) {
        }
        return json;
    }

    /** Parsed lyrics of the current track (same JSON the multi-room wire uses). */
    private static JSONObject lyricsJson(Context context) {
        JSONObject json = new JSONObject();
        try {
            PlaybackService service = PlaybackService.getInstance();
            Track track = service == null ? null : service.getCurrentTrack();
            PlayerUiState state = StateBus.get().getState();
            if (track == null) {
                // A multi-room receiver has no local file: the master pushed the
                // parsed lyrics (and the metadata) over, so serve those instead.
                PlaybackService.RemoteLyrics remote =
                        service == null ? null : service.getRemoteLyrics();
                boolean receiving = state != null
                        && state.source == PlayerUiState.Source.REMOTE;
                if (receiving && remote != null && remote.lyrics != null
                        && !remote.lyrics.isEmpty()) {
                    JSONObject pushed = new JSONObject(
                            com.airmusic.player.lyrics.LyricWire.encode(
                                    remote.seed, remote.hints, remote.lyrics));
                    json.put("available", true);
                    json.put("title", state == null ? "" : nullToEmpty(state.title));
                    json.put("artist", state == null ? "" : nullToEmpty(state.artist));
                    json.put("lyrics", pushed);
                    return json;
                }
                json.put("available", false);
                json.put("source", state == null ? "IDLE" : state.source.name());
                return json;
            }
            long duration = track.durationMs > 0 ? track.durationMs
                    : (state == null ? 0 : state.durationMs);
            com.airmusic.player.lyrics.Lyrics lyrics =
                    com.airmusic.player.lyrics.LyricRepository.load(context, track, duration);
            JSONObject payload = new JSONObject(
                    com.airmusic.player.lyrics.LyricWire.encode(
                            com.airmusic.player.lyrics.LyricWire.seedFor(
                                    track.title, track.artist, track.album),
                            com.airmusic.player.lyrics.LyricWire.hintsFor(
                                    track.title, track.artist, track.album),
                            lyrics));
            json.put("available", lyrics != null && !lyrics.isEmpty());
            json.put("title", nullToEmpty(track.title));
            json.put("artist", nullToEmpty(track.artist));
            json.put("lyrics", payload);
        } catch (Throwable t) {
            try {
                json.put("available", false);
                json.put("error", String.valueOf(t.getMessage()));
            } catch (Throwable ignored) {
            }
        }
        return json;
    }

    /** Multi-room state: selected receivers + devices this device can see. */
    private static JSONObject multicastJson() {
        JSONObject json = new JSONObject();
        try {
            PlaybackService service = PlaybackService.getInstance();
            if (service == null) {
                json.put("active", false);
                return json;
            }
            JSONArray targets = new JSONArray();
            for (String name : service.getMultiRoomTargetNames()) targets.put(name);
            JSONArray devices = new JSONArray();
            List<com.airmusic.player.multicast.MultiRoomDiscovery.DeviceInfo> found =
                    service.getMultiRoomDevices();
            if (found.isEmpty()) {
                // The desktop polls this endpoint while its multi-room page is
                // open; an empty answer also nudges the device to re-register its
                // mDNS service (a late network can leave it unannounced).
                service.ensureMultiRoomDiscovery();
            }
            for (com.airmusic.player.multicast.MultiRoomDiscovery.DeviceInfo device : found) {
                JSONObject item = new JSONObject();
                item.put("name", nullToEmpty(device.name));
                item.put("port", device.port);
                JSONArray addresses = new JSONArray();
                if (device.addresses != null) {
                    for (String address : device.addresses) {
                        if (address != null) addresses.put(address);
                    }
                }
                item.put("addresses", addresses);
                item.put("selected", service.getMultiRoomTargetNames().contains(device.name));
                devices.put(item);
            }
            json.put("active", service.hasMultiRoomTargets());
            json.put("targets", targets);
            json.put("devices", devices);
        } catch (Throwable t) {
            try {
                json.put("error", String.valueOf(t.getMessage()));
            } catch (Throwable ignored) {
            }
        }
        return json;
    }

    /** Selects the receivers by name ({@code devices: []} disconnects all). */
    private static JSONObject applyMulticast(byte[] body) {
        JSONObject result = new JSONObject();
        try {
            JSONObject request = body == null || body.length == 0
                    ? new JSONObject() : new JSONObject(new String(body, StandardCharsets.UTF_8));
            JSONArray devices = request.optJSONArray("devices");
            final java.util.List<String> names = new java.util.ArrayList<>();
            if (devices != null) {
                for (int i = 0; i < devices.length(); i++) {
                    String name = devices.optString(i, "");
                    if (!name.trim().isEmpty()) names.add(name.trim());
                }
            }
            PlaybackService service = PlaybackService.getInstance();
            if (service != null) runOnMain(() -> service.selectMultiRoomTargets(names));
            result.put("ok", true);
            result.put("selected", new JSONArray(names));
        } catch (Throwable t) {
            try {
                result.put("error", String.valueOf(t.getMessage()));
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    /**
     * Sends a cover image: the current track's art by default, or - with
     * {@code ?path=} - the embedded art of any library file (used by the album
     * and artist tiles on both clients).
     */
    /**
     * Streams one song file, honouring a Range header so the desktop console can
     * play and seek without downloading the whole file first.
     */
    private static void sendFile(Context context, OutputStream out, String query,
                                 String rangeHeader) throws IOException {
        String requested = queryParameter(query, "path");
        if (requested == null || requested.isEmpty()) {
            sendJson(out, 400, error("no path"));
            return;
        }
        File file = new File(requested);
        String root = new Prefs(context).getMusicFolderPath();
        if (!file.isFile()
                || (root != null && !root.isEmpty()
                    && !file.getAbsolutePath().startsWith(root))) {
            sendJson(out, 404, error("no such file"));
            return;
        }

        long total = file.length();
        long start = 0;
        long end = total - 1;
        boolean partial = false;
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String spec = rangeHeader.substring(6).trim();
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                String from = spec.substring(0, dash).trim();
                String to = spec.substring(dash + 1).trim();
                try {
                    if (!from.isEmpty()) start = Long.parseLong(from);
                    if (!to.isEmpty()) end = Long.parseLong(to);
                } catch (NumberFormatException ignored) {
                    start = 0;
                    end = total - 1;
                }
                if (start < 0) start = 0;
                if (end > total - 1) end = total - 1;
                if (end < start) end = start;
                partial = true;
            }
        }
        long length = Math.max(0, end - start + 1);

        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(partial ? "206 Partial Content" : "200 OK").append("\r\n");
        head.append("Content-Type: ").append(mimeOf(file.getName())).append("\r\n");
        head.append("Content-Length: ").append(length).append("\r\n");
        head.append("Accept-Ranges: bytes\r\n");
        if (partial) {
            head.append("Content-Range: bytes ").append(start).append('-').append(end)
                    .append('/').append(total).append("\r\n");
        }
        head.append("Connection: close\r\n");
        head.append("Access-Control-Allow-Origin: *\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            raf.seek(start);
            byte[] buffer = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int read = raf.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read <= 0) break;
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
        out.flush();
    }

    /** Content type for the supported audio containers. */
    private static String mimeOf(String name) {
        String lower = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".m4a") || lower.endsWith(".aac")) return "audio/mp4";
        if (lower.endsWith(".ogg") || lower.endsWith(".opus")) return "audio/ogg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".wma")) return "audio/x-ms-wma";
        if (lower.endsWith(".ape")) return "audio/x-ape";
        return "application/octet-stream";
    }
    private static void sendCover(Context context, OutputStream out, String query)
            throws IOException {
        PlayerUiState state = StateBus.get().getState();
        Bitmap art = null;
        String requested = queryParameter(query, "path");
        if (requested != null && !requested.isEmpty()) {
            Track track = findTrack(context, requested);
            if (track != null) {
                art = embeddedArt(context, track);
                if (art == null) {
                    // Files of one album do not all carry the picture; borrow it
                    // from a sibling so the album card still shows art.
                    java.util.List<Track> candidates =
                            com.airmusic.player.library.CoverArt.siblingsOf(track,
                                    com.airmusic.player.library.CoverArt.MAX_TRIES);
                    art = com.airmusic.player.library.CoverArt.firstBitmap(context, candidates);
                }
            }
        } else {
            // No explicit file asked for: the only sensible answer is the cover
            // of what is playing right now.
            art = state == null ? null : state.art;
        }
        if (art == null || art.getWidth() <= 0) {
            sendJson(out, 404, error("no cover"));
            return;
        }
        float scale = Math.min(1f, COVER_SIZE / (float) Math.max(art.getWidth(), art.getHeight()));
        Bitmap scaled = scale < 1f
                ? Bitmap.createScaledBitmap(art,
                Math.max(1, Math.round(art.getWidth() * scale)),
                Math.max(1, Math.round(art.getHeight() * scale)), true)
                : art;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 88, buffer);
        byte[] png = buffer.toByteArray();
        String header = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: image/jpeg\r\n"
                + "Content-Length: " + png.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.ISO_8859_1));
        out.write(png);
        out.flush();
    }

    /** Decodes the art embedded in one file, or null when it has none. */
    private static Bitmap embeddedArt(Context context, Track track) {
        MediaMetadataRetriever retriever = MusicLibrary.openRetriever(context, track.uri);
        if (retriever == null) return null;
        try {
            byte[] bytes = retriever.getEmbeddedPicture();
            if (bytes == null || bytes.length == 0) return null;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Finds a library track by absolute path. */
    private static Track findTrack(Context context, String path) {
        MusicLibrary library = MusicLibrary.getInstance();
        List<Track> tracks = library.getCachedTracks();
        if (tracks != null) {
            for (Track track : tracks) {
                if (path.equals(track.filePath)) return track;
            }
        }
        File file = new File(path);
        if (!file.isFile()) return null;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return new Track(Uri.fromFile(file), null, null, null, 0L,
                file.getParent(), file.getAbsolutePath(),
                dot >= 0 ? name.substring(dot + 1) : "");
    }

    private static String queryParameter(String query, String name) {
        if (query == null || query.isEmpty()) return null;
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            if (equals <= 0) continue;
            String key = part.substring(0, equals);
            if (!name.equals(key)) continue;
            try {
                return java.net.URLDecoder.decode(
                        part.substring(equals + 1), StandardCharsets.UTF_8.name());
            } catch (Throwable ignored) {
                return part.substring(equals + 1);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Write endpoints
    // ------------------------------------------------------------------

    private static JSONObject control(byte[] body) {
        JSONObject result = new JSONObject();
        try {
            JSONObject request = body == null || body.length == 0
                    ? new JSONObject() : new JSONObject(new String(body, StandardCharsets.UTF_8));
            String action = request.optString("action", "");
            PlaybackService service = PlaybackService.getInstance();
            Context context = service == null ? null : service.getApplicationContext();
            switch (action) {
                case "play":
                    if (service != null && !isPlaying()) {
                        runOnMain(service::togglePlay);
                    }
                    break;
                case "pause":
                    if (service != null && isPlaying()) {
                        runOnMain(service::togglePlay);
                    }
                    break;
                case "toggle":
                    if (service != null) runOnMain(service::togglePlay);
                    break;
                case "next":
                    if (service != null) runOnMain(service::next);
                    break;
                case "previous":
                    if (service != null) runOnMain(service::previous);
                    break;
                case "seek":
                    if (service != null) {
                        final int position = request.optInt("positionMs", 0);
                        runOnMain(() -> service.seekTo(position));
                    }
                    break;
                case "volume":
                    if (context != null) {
                        AudioManager audio = (AudioManager) context
                                .getSystemService(Context.AUDIO_SERVICE);
                        if (audio != null) {
                            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                            int value = Math.max(0, Math.min(max, request.optInt("value", 0)));
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0);
                        }
                    }
                    break;
                case "mode":
                    if (service != null) {
                        final String mode = request.optString("value", "SEQUENCE");
                        runOnMain(() -> service.applyPlayMode(mode));
                    }
                    break;
                case "rescan":
                    if (service != null) runOnMain(service::rescanLibrary);
                    break;
                case "saveEqPreset": {
                    // Preset handling mirrors the equalizer screen: the name must
                    // be unique, gains are the ten band values currently shown.
                    String name = request.optString("name", "").trim();
                    if (context == null || name.isEmpty()) {
                        result.put("result", "empty");
                        break;
                    }
                    JSONArray bandArray = request.optJSONArray("gains");
                    double[] values = new double[10];
                    if (bandArray != null) {
                        for (int i = 0; i < values.length && i < bandArray.length(); i++) {
                            values[i] = bandArray.optDouble(i, 0d);
                        }
                    }
                    boolean added = new Prefs(context).addEqPreset(name, values);
                    result.put("result", added ? "ok" : "duplicate");
                    break;
                }
                case "deleteEqPreset": {
                    String name = request.optString("name", "");
                    if (context != null && !name.isEmpty()) {
                        new Prefs(context).removeEqPreset(name);
                    }
                    result.put("result", "ok");
                    break;
                }
                case "exportEqPresets": {
                    result.put("presets", context == null ? "[]"
                            : new Prefs(context).exportEqPresets());
                    break;
                }
                case "importEqPresets": {
                    int imported = context == null ? 0
                            : new Prefs(context).importEqPresets(request.optString("json", ""));
                    result.put("imported", imported);
                    break;
                }
                case "disconnectMultiRoomReceiver":
                    // Receiver side: fade the multi-room audio out, then ask the
                    // master to drop this device (same as the in-app button).
                    if (service != null) runOnMain(service::disconnectFromMaster);
                    break;
                case "openLyrics":
                case "openMain":
                    // Switch the device between the lyric screen and the main
                    // playback screen (the desktop console's button).
                    if (context != null) {
                        final boolean lyrics = "openLyrics".equals(action);
                        runOnMain(() -> {
                            android.content.Intent intent = new android.content.Intent(context,
                                    lyrics ? com.airmusic.player.LyricsActivity.class
                                           : com.airmusic.player.MainActivity.class);
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                    | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                            context.startActivity(intent);
                        });
                    }
                    break;
                case "playTrack":
                    // Play one song of the library (used by the desktop library
                    // list). An optional "queue" of paths plays inside one album
                    // or artist, so the play modes stay inside that group.
                    if (service != null) {
                        final String path = request.optString("path", "");
                        final JSONArray queue = request.optJSONArray("queue");
                        runOnMain(() -> {
                            Track target = null;
                            List<Track> library = MusicLibrary.getInstance().getCachedTracks();
                            if (library != null) {
                                for (Track track : library) {
                                    if (track.filePath != null && track.filePath.equals(path)) {
                                        target = track;
                                        break;
                                    }
                                }
                            }
                            if (target == null) return;
                            List<Track> group = new java.util.ArrayList<>();
                            if (queue != null && library != null) {
                                for (int i = 0; i < queue.length(); i++) {
                                    String item = queue.optString(i, "");
                                    for (Track track : library) {
                                        if (track.filePath != null && track.filePath.equals(item)) {
                                            group.add(track);
                                            break;
                                        }
                                    }
                                }
                            }
                            if (!group.isEmpty() && group.contains(target)) {
                                service.playTrackInGroup(target, group);
                            } else {
                                service.playTrack(target);
                            }
                        });
                    }
                    break;
                default:
                    result.put("error", "unknown action");
                    return result;
            }
            result.put("ok", true);
            result.put("action", action);
        } catch (Throwable t) {
            try {
                result.put("error", String.valueOf(t.getMessage()));
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private static JSONObject applySettings(Context context, byte[] body) {
        Prefs prefs = new Prefs(context);
        PlaybackService service = PlaybackService.getInstance();
        JSONObject result = new JSONObject();
        try {
            JSONObject request = body == null || body.length == 0
                    ? new JSONObject() : new JSONObject(new String(body, StandardCharsets.UTF_8));
            if (request.has("deviceName")) {
                prefs.setAirPlayName(request.optString("deviceName", "").trim());
            }
            if (request.has("musicFolder")) {
                String path = request.optString("musicFolder", "").trim();
                String display = request.optString("musicFolderDisplay", path);
                prefs.setMusicFolderPath(path.isEmpty() ? null : path,
                        path.isEmpty() ? null : display);
                if (service != null) runOnMain(service::rescanLibrary);
            }
            if (request.has("playMode") && service != null) {
                final String mode = request.optString("playMode", "SEQUENCE");
                runOnMain(() -> service.applyPlayMode(mode));
            }
            if (request.has("autoPlayOnStart")) {
                prefs.setAutoPlayOnStart(request.optBoolean("autoPlayOnStart", false));
            }
            if (request.has("balance") && service != null) {
                final float balance = (float) request.optDouble("balance", 0d);
                runOnMain(() -> service.setBalance(balance));
            }
            if (request.has("showAppsButton")) {
                prefs.setShowAppsButton(request.optBoolean("showAppsButton", true));
            }
            if (request.has("blurMode")) {
                prefs.setBlurMode(request.optString("blurMode", Prefs.BLUR_DARK));
            }
            if (request.has("language")) {
                prefs.setLanguage(request.optString("language", "zh"));
            }
            if (request.has("onlineLyrics")) {
                prefs.setOnlineLyrics(request.optBoolean("onlineLyrics", true));
            }
            if (request.has("eqGains") && service != null) {
                JSONArray gains = request.optJSONArray("eqGains");
                if (gains != null && gains.length() > 0) {
                    double[] values = new double[gains.length()];
                    for (int i = 0; i < gains.length(); i++) values[i] = gains.optDouble(i, 0d);
                    runOnMain(() -> service.setEqualizerGains(values));
                }
            }
            result.put("ok", true);
            result.put("settings", settingsJson(context));
        } catch (Throwable t) {
            try {
                result.put("error", String.valueOf(t.getMessage()));
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private static JSONObject deleteFiles(Context context, byte[] body) {
        JSONObject result = new JSONObject();
        String root = new Prefs(context).getMusicFolderPath();
        int deleted = 0;
        JSONArray failed = new JSONArray();
        try {
            JSONObject request = body == null || body.length == 0
                    ? new JSONObject() : new JSONObject(new String(body, StandardCharsets.UTF_8));
            JSONArray paths = request.optJSONArray("paths");
            if (paths != null) {
                for (int i = 0; i < paths.length(); i++) {
                    String path = paths.optString(i, "");
                    File file = new File(path);
                    if (!file.isFile()) {
                        failed.put(path);
                        continue;
                    }
                    if (root != null && !root.isEmpty()
                            && !file.getAbsolutePath().startsWith(root)) {
                        // Only songs inside the configured library may be removed.
                        failed.put(path);
                        continue;
                    }
                    if (file.delete()) {
                        deleted++;
                    } else {
                        failed.put(path);
                    }
                }
            }
            PlaybackService service = PlaybackService.getInstance();
            if (service != null && deleted > 0) {
                final PlaybackService rescan = service;
                runOnMain(() -> {
                            // No clearCache() here: the scan swaps the list when it
                            // finishes, so /api/library never reports an empty
                            // library while the desktop is deleting files.
                            rescan.rescanLibrary();
                });
            }
            result.put("ok", true);
            result.put("deleted", deleted);
            result.put("failed", failed);
        } catch (Throwable t) {
            try {
                result.put("error", String.valueOf(t.getMessage()));
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static boolean isPlaying() {
        PlayerUiState state = StateBus.get().getState();
        return state != null && state.playing;
    }

    /**
     * Playback and settings changes must run on the main thread: the media
     * player refuses to be touched from the HTTP worker thread. The caller
     * waits briefly so the API can report the real outcome.
     */
    private static void runOnMain(Runnable action) throws InterruptedException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
            return;
        }
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        final Throwable[] failure = new Throwable[1];
        MAIN.post(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                latch.countDown();
            }
        });
        latch.await(4, java.util.concurrent.TimeUnit.SECONDS);
        if (failure[0] != null) {
            throw new IllegalStateException(String.valueOf(failure[0].getMessage()));
        }
    }

    private static String relativize(String root, String path) {
        if (root == null || path == null) return "";
        String prefix = root.endsWith("/") ? root : root + "/";
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static JSONObject error(String message) {
        JSONObject json = new JSONObject();
        try {
            json.put("error", message == null ? "error" : message);
        } catch (Throwable ignored) {
        }
        return json;
    }

    private static void sendJson(OutputStream out, int code, JSONObject json) throws IOException {
        byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "Error") + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.ISO_8859_1));
        out.write(data);
        out.flush();
    }

    /** First non-loopback IPv4 address of the device. */
    public static String localIpAddress() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }
}
