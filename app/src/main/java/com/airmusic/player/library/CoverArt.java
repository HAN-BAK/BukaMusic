package com.airmusic.player.library;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads embedded cover art, with a fallback for albums whose first track has no
 * art: some files of one album carry the picture while others do not, so the
 * first few siblings are tried before giving up.
 */
public final class CoverArt {

    /** How many files of one album / artist are inspected before giving up. */
    public static final int MAX_TRIES = 6;

    private CoverArt() {
    }

    /** Raw embedded picture of one file, or null. */
    public static byte[] bytesOf(Context context, Track track) {
        if (track == null) return null;
        MediaMetadataRetriever retriever = MusicLibrary.openRetriever(context, track.uri);
        if (retriever == null) return null;
        try {
            byte[] bytes = retriever.getEmbeddedPicture();
            return bytes == null || bytes.length == 0 ? null : bytes;
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Decoded cover of the first file in the list that has one. */
    public static android.graphics.Bitmap firstBitmap(Context context, List<Track> candidates) {
        byte[] bytes = firstBytes(context, candidates);
        if (bytes == null) return null;
        try {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Raw cover of the first file in the list that has one. */
    public static byte[] firstBytes(Context context, List<Track> candidates) {
        if (candidates == null) return null;
        int tries = 0;
        for (Track track : candidates) {
            if (track == null) continue;
            byte[] bytes = bytesOf(context, track);
            if (bytes != null) return bytes;
            if (++tries >= MAX_TRIES) break;
        }
        return null;
    }

    /** Other library files that belong to the same album (and artist). */
    public static List<Track> siblingsOf(Track track, int limit) {
        List<Track> out = new ArrayList<>();
        if (track == null) return out;
        List<Track> library = MusicLibrary.getInstance().getCachedTracks();
        if (library == null) return out;
        for (Track other : library) {
            if (other == null || other.equals(track)) continue;
            boolean sameAlbum = other.displayAlbum().equals(track.displayAlbum());
            boolean sameArtist = other.displayArtist().equals(track.displayArtist());
            if (sameAlbum && sameArtist) {
                out.add(other);
            }
            if (out.size() >= limit) break;
        }
        return out;
    }
}
