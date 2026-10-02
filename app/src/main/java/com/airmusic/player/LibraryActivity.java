package com.airmusic.player;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.airmusic.player.library.MusicLibrary;
import com.airmusic.player.library.Track;
import com.airmusic.player.service.PlaybackService;
import com.airmusic.player.util.BlurBackground;
import com.airmusic.player.util.Prefs;
import com.airmusic.player.util.StateBus;
import com.airmusic.player.ui.TrackAdapter;
import com.airmusic.player.ui.CoverArtLoader;

import java.io.File;
import java.util.List;
import com.airmusic.player.ui.BukaNotice;
import com.airmusic.player.ui.BukaDialog;
import com.airmusic.player.ui.BukaTheme;

public class LibraryActivity extends BaseActivity {

    private final TrackAdapter adapter = new TrackAdapter();
    private TextView txtTitle;
    private TextView txtSelectCount;
    private ImageButton btnDelete;
    private com.google.android.material.button.MaterialButton btnGroup;
    private RecyclerView list;
    private CoverArtLoader coverLoader;
    private int cardSpan = 3;
    /** Album / artist mode: jump to the playing song the first time we load. */
    private boolean locateCurrentOnFirstLoad;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);
        BlurBackground.apply(this, R.color.background);

        txtTitle = findViewById(R.id.txt_title);
        txtSelectCount = findViewById(R.id.txt_select_count);
        btnDelete = findViewById(R.id.btn_delete);
        btnGroup = findViewById(R.id.btn_group);
        btnGroup.setOnClickListener(v -> showGroupDialog());

        findViewById(R.id.btn_back).setOnClickListener(v -> {
            if (adapter.isSelectionMode()) {
                exitSelectionMode();
            } else if (adapter.getOpenGroupKey() != null) {
                // Inside one album / artist: go back to the tiles.
                adapter.closeGroup();
                applyLayoutMode();
            } else {
                finish();
            }
        });

        list = findViewById(R.id.track_list);
        int widthDp = (int) (getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density);
        cardSpan = Math.max(2, Math.min(6, widthDp / 200));
        coverLoader = new CoverArtLoader(this);
        adapter.setCoverLoader(coverLoader);
        adapter.setOnGroupClick(key -> {
            adapter.openGroup(key);
            applyLayoutMode();
        });
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        // 全应用统一外观：列表本体也做成卡片（与对话框同一套）
        // Remember how the library was presented last time (flat / by album /
        // by artist) and restore it right away.
        TrackAdapter.GroupBy saved = TrackAdapter.GroupBy.NONE;
        try {
            saved = TrackAdapter.GroupBy.valueOf(new Prefs(this).getLibraryGroup());
        } catch (Throwable ignored) {
        }
        if (saved != TrackAdapter.GroupBy.NONE) {
            adapter.setGroupBy(saved);
            applyLayoutMode();
            // Opening the library in album / artist mode jumps to whatever is
            // playing right now (once - not on every resume).
            locateCurrentOnFirstLoad = true;
        }

        adapter.setOnTrackClick(track -> {
            PlaybackService service = PlaybackService.getInstance();
            if (service != null) {
                List<Track> group = adapter.getOpenGroupTracks();
                if (group != null && !group.isEmpty()) {
                    // Playing from an album / artist keeps that album as the
                    // queue for 上一首 / 下一首 and every play mode.
                    service.playTrackInGroup(track, group);
                } else {
                    service.playTrack(track);
                }
                finish();
            }
        });
        adapter.setOnTrackLongClick(track -> updateSelectionUi());
        adapter.setOnSelectionChanged(count -> updateSelectionUi());
        btnDelete.setOnClickListener(v -> confirmDelete());

        loadTracks();
    }

    /** Album / artist browsing uses a grid of tiles, the flat list stays a list. */
    private void applyLayoutMode() {
        RecyclerView.LayoutManager manager = list.getLayoutManager();
        if (adapter.isShowingCards()) {
            if (!(manager instanceof GridLayoutManager)
                    || ((GridLayoutManager) manager).getSpanCount() != cardSpan) {
                list.setLayoutManager(new GridLayoutManager(this, cardSpan));
            }
        } else if (manager instanceof GridLayoutManager) {
            list.setLayoutManager(new LinearLayoutManager(this));
        }
        String open = adapter.getOpenGroupTitle();
        txtTitle.setText(open == null ? getString(R.string.library) : open);
        txtTitle.setVisibility(adapter.isSelectionMode() ? View.GONE : View.VISIBLE);
        btnGroup.setText(getString(labelFor(adapter.getGroupBy())));
        animateListIn();
    }

    /**
     * Staggered fade / rise for the rows or tiles that just appeared, so
     * switching between the tile grid and one album's songs animates instead of
     * snapping.
     */
    private void animateListIn() {
        list.post(() -> {
            if (isFinishing()) return;
            float shift = getResources().getDisplayMetrics().density * 26f;
            android.view.animation.Interpolator ease =
                    new android.view.animation.DecelerateInterpolator(1.6f);
            int count = list.getChildCount();
            for (int i = 0; i < count; i++) {
                android.view.View child = list.getChildAt(i);
                if (child == null) continue;
                // Never stack a new fade on top of an unfinished one, and make
                // sure the row ends up fully visible even if the animation is
                // cut short.
                child.animate().cancel();
                child.setAlpha(0f);
                child.setTranslationY(shift);
                child.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .withEndAction(() -> {
                            child.setAlpha(1f);
                            child.setTranslationY(0f);
                        })
                        .setStartDelay(Math.min(260L, i * 28L))
                        .setDuration(280L)
                        .setInterpolator(ease)
                        .start();
            }
        });
    }

    private static int labelFor(TrackAdapter.GroupBy mode) {
        if (mode == TrackAdapter.GroupBy.ALBUM) return R.string.group_by_album;
        if (mode == TrackAdapter.GroupBy.ARTIST) return R.string.group_by_artist;
        return R.string.group_default;
    }

    @Override
    protected void onResume() {
        super.onResume();
        BlurBackground.apply(this, R.color.background);
        // 控制台可能在后台改过浏览方式（按专辑 / 按歌手控制播放时会一并下发），
        // 所以每次显示曲库都跟随一次设置。
        applySavedGroup();
        loadTracks();
        // 曲库页面停留期间换歌（自动下一首、电脑端切歌、多房间接收）也要更新
        // 高亮：以前只有打开 / 回到本页时才刷新。
        StateBus.get().addListener(stateListener);
        refreshCurrentHighlight();
    }

    @Override
    protected void onPause() {
        super.onPause();
        StateBus.get().removeListener(stateListener);
    }

    private final StateBus.Listener stateListener = state -> runOnUiThread(this::refreshCurrentHighlight);
    /** Last highlighted track, so the log stays readable. */
    private String lastHighlight = "";

    /** Updates only the "now playing" highlight; never scrolls (that is the
     *  user's job once the page is open). */
    private void refreshCurrentHighlight() {
        PlaybackService service = PlaybackService.getInstance();
        if (service == null) return;
        if (!service.isShowingLibraryTrack()) {
            if (!lastHighlight.isEmpty()) {
                Log.i("LibraryActivity", "曲库高亮：清空（当前不在本地 / 多房间播放）");
                lastHighlight = "";
            }
            adapter.setCurrent(null, null, null);
            return;
        }
        Track cur = service.getCurrentTrack();
        String title = service.getCurrentDisplayTitle();
        String artist = service.getCurrentDisplayArtist();
        String key = cur != null
                ? (cur.uri == null ? "" : cur.uri.toString())
                : title + "|" + artist;
        if (!key.equals(lastHighlight)) {
            lastHighlight = key;
            Log.i("LibraryActivity", "曲库高亮：" + (cur != null ? cur.displayTitle() : title));
        }
        adapter.setCurrent(cur == null ? null : cur.uri,
                cur == null ? title : null, cur == null ? artist : null);
    }

    /** Applies the remembered NONE / ALBUM / ARTIST mode to the list. */
    private void applySavedGroup() {
        TrackAdapter.GroupBy saved = TrackAdapter.GroupBy.NONE;
        try {
            saved = TrackAdapter.GroupBy.valueOf(new Prefs(this).getLibraryGroup());
        } catch (Throwable ignored) {
        }
        if (saved == adapter.getGroupBy()) return;
        adapter.setGroupBy(saved);
        applyLayoutMode();
    }

    private void loadTracks() {
        PlaybackService service = PlaybackService.getInstance();
        List<Track> tracks = service != null
                ? service.getLibraryTracks()
                : MusicLibrary.getInstance().getCachedTracks();
        if (tracks != null && !tracks.isEmpty()) {
            adapter.setTracks(tracks);
            applyCurrentTrack(tracks);
            locateCurrentInGroup(tracks);
        }
        // Always rescan so newly added files (USB, new downloads) show up;
        // the cached list is shown immediately and replaced when ready.
        MusicLibrary.getInstance().rescan(this, (result, error) -> {
            if (service != null && result != null) {
                service.setTracks(result);
            }
            if (result != null) {
                adapter.setTracks(result);
                applyCurrentTrack(result);
                locateCurrentInGroup(result);
            }
            if (result != null && result.isEmpty() && error != null) {
                BukaNotice.show(this, error, BukaNotice.LONG);
            }
            if (result != null && result.isEmpty()) {
                BukaNotice.show(this, R.string.no_tracks, BukaNotice.LONG);
            }
        });
    }

    private void updateSelectionUi() {
        boolean selecting = adapter.isSelectionMode();
        txtTitle.setVisibility(selecting ? View.GONE : View.VISIBLE);
        btnGroup.setVisibility(selecting ? View.GONE : View.VISIBLE);
        txtSelectCount.setVisibility(selecting ? View.VISIBLE : View.GONE);
        btnDelete.setVisibility(selecting ? View.VISIBLE : View.GONE);
        if (selecting) {
            txtSelectCount.setText(getString(R.string.selected_count, adapter.getSelectedCount()));
        } else {
            String open = adapter.getOpenGroupTitle();
            txtTitle.setText(open == null ? getString(R.string.library) : open);
        }
    }

    /** Chooses how the library is presented: flat, by album or by artist. */
    private void showGroupDialog() {
        final TrackAdapter.GroupBy current = adapter.getGroupBy();
        final TrackAdapter.GroupBy[] modes = {
                TrackAdapter.GroupBy.NONE,
                TrackAdapter.GroupBy.ALBUM,
                TrackAdapter.GroupBy.ARTIST,
        };
        String[] labels = {
                getString(R.string.group_default),
                getString(R.string.group_by_album),
                getString(R.string.group_by_artist),
        };
        int checked = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == current) checked = i;
        }
        BukaDialog.singleChoice(this, getString(R.string.group_title), labels, checked, which -> {
            adapter.setGroupBy(modes[which]);
            new Prefs(this).setLibraryGroup(modes[which].name());
            btnGroup.setText(labels[which]);
            applyLayoutMode();
            scrollToCurrentTrack();
        }).show();
    }

    private void scrollToCurrentTrack() {
        PlaybackService service = PlaybackService.getInstance();
        if (service == null || !service.isShowingLibraryTrack()) {
            return;
        }
        if (adapter.isShowingCards()) return;
        Track cur = service.getCurrentTrack();
        int index = cur != null
                ? adapter.indexOfTrack(cur.uri, null, null)
                : -1;
        if (index < 0) {
            // The player's URI can differ from the scanned one (restored track /
            // MediaStore vs file path), so fall back to the published metadata.
            index = adapter.indexOfTrack(null, service.getCurrentDisplayTitle(),
                    service.getCurrentDisplayArtist());
        }
        if (index < 0) return;
        Log.i("LibraryActivity", "library jumped to the playing track (row " + (index + 1)
                + " of " + adapter.getItemCount() + ")");
        RecyclerView list = findViewById(R.id.track_list);
        final int position = index;
        list.post(() -> {
            RecyclerView.LayoutManager lm = list.getLayoutManager();
            if (lm instanceof LinearLayoutManager) {
                ((LinearLayoutManager) lm).scrollToPositionWithOffset(position, 0);
            }
        });
    }

    /**
     * Album / artist mode: opens the group that contains the song playing right
     * now and scrolls to it, so tapping 曲库 lands on the current music instead
     * of the top of the grid. Runs once per time the library is opened.
     */
    private void locateCurrentInGroup(List<Track> tracks) {
        if (!locateCurrentOnFirstLoad) return;
        if (tracks == null || tracks.isEmpty()) return;
        if (adapter.getGroupBy() == TrackAdapter.GroupBy.NONE) {
            locateCurrentOnFirstLoad = false;
            return;
        }
        PlaybackService service = PlaybackService.getInstance();
        if (service == null || !service.isShowingLibraryTrack()) {
            locateCurrentOnFirstLoad = false;
            return;
        }
        String key = null;
        Track current = service.getCurrentTrack();
        if (current != null) {
            key = groupKeyOf(current);
        } else {
            // Multi-room receiver: match by the metadata the master publishes.
            String title = service.getCurrentDisplayTitle();
            String artist = service.getCurrentDisplayArtist();
            for (Track track : tracks) {
                if (title != null && title.length() > 0 && title.equals(track.displayTitle())
                        && (artist == null || artist.length() == 0
                        || artist.equals(track.displayArtist()))) {
                    key = groupKeyOf(track);
                    break;
                }
            }
        }
        locateCurrentOnFirstLoad = false;
        if (key == null) return;
        adapter.openGroup(key);
        applyLayoutMode();
        scrollToCurrentTrack();
        // The rows are rebuilt (and a rescan can replace them right after), so
        // scroll again once the new list has actually been laid out.
        list.postDelayed(this::scrollToCurrentTrack, 350L);
        list.postDelayed(this::scrollToCurrentTrack, 1200L);
    }

    /** Same key the adapter groups by (unknown album / artist lands in "-"). */
    private String groupKeyOf(Track track) {
        String key = adapter.getGroupBy() == TrackAdapter.GroupBy.ALBUM
                ? track.displayAlbum() : track.displayArtist();
        if (key == null || key.trim().isEmpty()
                || Track.UNKNOWN_ALBUM.equals(key) || Track.UNKNOWN_ARTIST.equals(key)) {
            key = "-";
        }
        return key;
    }

    /**
     * The system back key mirrors the toolbar button: selection mode -> leave
     * selection, inside an album / artist -> back to the tile grid, otherwise
     * leave the library.
     */
    @Override
    public void onBackPressed() {
        if (adapter.isSelectionMode()) {
            exitSelectionMode();
            return;
        }
        if (adapter.getOpenGroupKey() != null) {
            adapter.closeGroup();
            applyLayoutMode();
            return;
        }
        super.onBackPressed();
    }

    private void exitSelectionMode() {
        adapter.setSelectionMode(false);
        updateSelectionUi();
    }

    private void confirmDelete() {
        final List<Track> selected = adapter.getSelectedTracks();
        if (selected.isEmpty()) {
            exitSelectionMode();
            return;
        }
        BukaDialog.confirm(this, getString(R.string.delete),
                getString(R.string.delete_files_confirm, selected.size()),
                getString(android.R.string.ok), () -> deleteTracks(selected)).show();
    }

    private void deleteTracks(List<Track> selected) {
        int ok = 0;
        List<Track> deleted = new java.util.ArrayList<>();
        for (Track t : selected) {
            if (deleteFile(t)) {
                ok++;
                deleted.add(t);
            }
        }
        exitSelectionMode();
        BukaNotice.show(this, getString(R.string.deleted_count, ok));
        MusicLibrary.getInstance().clearCache();
        PlaybackService service = PlaybackService.getInstance();
        if (service != null) {
            // Remove the files from the playlist immediately and switch the
            // player to a surviving track if the current one was deleted.
            service.removeDeletedTracks(deleted);
        }
        loadTracks();
    }

    private boolean deleteFile(Track t) {
        try {
            if (t.filePath != null && !t.filePath.isEmpty()) {
                File f = new File(t.filePath);
                if (f.exists()) {
                    return f.delete();
                }
            }
            if (t.uri != null) {
                return getContentResolver().delete(t.uri, null, null) > 0;
            }
        } catch (Exception e) {
            Log.w("LibraryActivity", "delete failed", e);
        }
        return false;
    }

    /** Highlights the currently playing track and scrolls it to the top of
     *  the visible list (the sort order itself stays unchanged). */
    private void applyCurrentTrack(List<Track> tracks) {
        if (tracks == null || tracks.isEmpty()) return;
        PlaybackService service = PlaybackService.getInstance();
        if (service == null || !service.isShowingLibraryTrack()) {
            adapter.setCurrent(null, null, null);
            return;
        }
        Track cur = service.getCurrentTrack();
        if (cur != null) {
            // 同时带上标题 / 艺术家：URI 不在列表里时（MediaStore 与文件路径不一致）
            // 还能退化成按标题匹配。
            adapter.setCurrent(cur.uri, cur.displayTitle(), cur.displayArtist());
        } else {
            String title = service.getCurrentDisplayTitle();
            String artist = service.getCurrentDisplayArtist();
            adapter.setCurrent(null, title, artist);
        }
        scrollToCurrentTrack();
    }
}