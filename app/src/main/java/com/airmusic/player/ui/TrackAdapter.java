package com.airmusic.player.ui;

import android.graphics.Color;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.airmusic.player.R;
import com.airmusic.player.library.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Library list. The same list can be shown flat, grouped by album or grouped
 * by artist - grouping only changes the presentation, the track order (and the
 * selection, which is keyed by track) stays intact.
 */
public class TrackAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public enum GroupBy { NONE, ALBUM, ARTIST }

    public interface OnTrackClick {
        void onTrackClick(Track track);
    }

    public interface OnTrackLongClick {
        void onTrackLongClick(Track track);
    }

    public interface OnSelectionChanged {
        void onSelectionChanged(int count);
    }

    public interface OnGroupClick {
        void onGroupClick(String groupKey);
    }

    private static final int TYPE_TRACK = 0;
    private static final int TYPE_HEADER = 1;
    private static final int TYPE_CARD = 2;

    /** One album / artist tile in the browse grid. */
    public static final class GroupCard {
        public final String key;
        public final String title;
        public final int count;
        public final Track first;
        /** Files of this album / artist, used to find one that carries art. */
        public final List<Track> tracks;

        GroupCard(String key, String title, int count, Track first, List<Track> tracks) {
            this.key = key;
            this.title = title;
            this.count = count;
            this.first = first;
            this.tracks = tracks;
        }
    }

    /** Group header row. */
    private static final class Header {
        final String title;
        final int count;

        Header(String title, int count) {
            this.title = title;
            this.count = count;
        }
    }

    private final List<Track> source = new ArrayList<>();
    private final List<Object> rows = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private OnTrackClick listener;
    private OnTrackLongClick longListener;
    private OnSelectionChanged selectionListener;
    private Uri currentUri;
    private String currentTitle;
    private String currentArtist;
    private boolean selectionMode;
    private GroupBy groupBy = GroupBy.NONE;
    /** Non-null while the tracks of one album / artist are open. */
    private String openGroup;
    private boolean showingCards;
    /** 当前搜索词（空 = 不搜索）；只在当前视图范围内过滤。 */
    private String query = "";
    private OnGroupClick groupListener;
    private CoverArtLoader coverLoader;

    public void setTracks(List<Track> newTracks) {
        source.clear();
        if (newTracks != null) source.addAll(newTracks);
        selected.clear();
        rebuild();
    }

    /** Switches between flat / by-album / by-artist presentation. */
    public void setGroupBy(GroupBy mode) {
        GroupBy next = mode == null ? GroupBy.NONE : mode;
        if (groupBy == next && openGroup == null) return;
        groupBy = next;
        openGroup = null;
        rebuild();
    }

    /** 设置搜索词：空字符串恢复原来的视图。 */
    public void setQuery(String text) {
        String next = text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT);
        if (next.equals(query)) return;
        query = next;
        rebuild();
    }

    public boolean hasQuery() {
        return !query.isEmpty();
    }

    private boolean matches(Track track) {
        if (query.isEmpty()) return true;
        return contains(track.displayTitle()) || contains(track.displayArtist())
                || contains(track.displayAlbum());
    }

    private boolean contains(String value) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(query);
    }

    public GroupBy getGroupBy() {
        return groupBy;
    }

    /** True while the album / artist tiles are on screen. */
    public boolean isShowingCards() {
        return showingCards;
    }

    public String getOpenGroupKey() {
        return openGroup;
    }

    public String getOpenGroupTitle() {
        if (openGroup == null) return null;
        return "-".equals(openGroup) ? null : openGroup;
    }

    /**
     * The tracks of the album / artist currently open, in list order. Playback
     * started here keeps this list as its queue, so the play modes operate
     * inside the album instead of the whole library.
     */
    public List<Track> getOpenGroupTracks() {
        if (openGroup == null) return null;
        List<Track> out = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Track) out.add((Track) row);
        }
        return out;
    }

    /** Opens one album / artist: the list then shows only its tracks. */
    public void openGroup(String key) {
        openGroup = key;
        rebuild();
    }

    /** Goes back to the album / artist tiles. */
    public void closeGroup() {
        if (openGroup == null) return;
        openGroup = null;
        rebuild();
    }

    public void setOnGroupClick(OnGroupClick listener) {
        this.groupListener = listener;
    }

    public void setCoverLoader(CoverArtLoader loader) {
        this.coverLoader = loader;
    }

    private void rebuild() {
        rows.clear();
        showingCards = false;
        if (!query.isEmpty()) {
            // 搜索：只显示匹配的曲目；已经打开某个专辑 / 歌手时只在该范围内搜
            List<Track> scope = source;
            if (openGroup != null) {
                List<Track> bucket = group(source).get(openGroup);
                scope = bucket == null ? java.util.Collections.<Track>emptyList() : bucket;
            }
            for (Track track : scope) {
                if (matches(track)) rows.add(track);
            }
            notifyDataSetChanged();
            return;
        }
        if (groupBy == GroupBy.NONE) {
            openGroup = null;
            rows.addAll(source);
        } else {
            Map<String, List<Track>> groups = group(source);
            if (openGroup != null && !groups.containsKey(openGroup)) {
                // The album / artist disappeared (files deleted or rescanned).
                openGroup = null;
            }
            if (openGroup == null) {
                showingCards = true;
                for (Map.Entry<String, List<Track>> entry : groups.entrySet()) {
                    List<Track> bucket = entry.getValue();
                    rows.add(new GroupCard(entry.getKey(), entry.getKey(),
                            bucket.size(), bucket.isEmpty() ? null : bucket.get(0), bucket));
                }
            } else {
                List<Track> bucket = groups.get(openGroup);
                if (bucket != null) rows.addAll(bucket);
            }
        }
        notifyDataSetChanged();
    }

    /** Groups the library by the current key, preserving the track order. */
    private Map<String, List<Track>> group(List<Track> tracks) {
        Map<String, List<Track>> groups = new LinkedHashMap<>();
        for (Track track : tracks) {
            String key = groupBy == GroupBy.ALBUM
                    ? track.displayAlbum() : track.displayArtist();
            if (key == null || key.trim().isEmpty()
                    || Track.UNKNOWN_ALBUM.equals(key) || Track.UNKNOWN_ARTIST.equals(key)) {
                key = "-";
            }
            List<Track> bucket = groups.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>();
                groups.put(key, bucket);
            }
            bucket.add(track);
        }
        if (groupBy == GroupBy.ALBUM) {
            // 专辑视图里按音轨号排；没有音轨号的排到最后，再按标题。
            // 不分组的平铺列表与按歌手视图保持原顺序。
            for (List<Track> bucket : groups.values()) {
                Collections.sort(bucket, new java.util.Comparator<Track>() {
                    @Override
                    public int compare(Track a, Track b) {
                        int ta = a.trackNo > 0 ? a.trackNo : Integer.MAX_VALUE;
                        int tb = b.trackNo > 0 ? b.trackNo : Integer.MAX_VALUE;
                        if (ta != tb) return ta < tb ? -1 : 1;
                        String x = a.displayTitle();
                        String y = b.displayTitle();
                        return x.compareToIgnoreCase(y);
                    }
                });
            }
        }
        return groups;
    }

    /** Highlights the track with this URI (local playback). */
    public void setCurrentUri(Uri uri) {
        setCurrent(uri, null, null);
    }

    /** Highlights the track matching this title/artist (multi-room receiver). */
    public void setCurrentTitleArtist(String title, String artist) {
        setCurrent(null, title, artist);
    }

    /**
     * Highlights one track, matching by URI first and by title / artist when the
     * URI is not in the list (MediaStore entries and file paths differ).
     *
     * <p>State updates arrive every few hundred milliseconds, so an unchanged
     * track must not trigger a full {@code notifyDataSetChanged}: that would
     * rebind every row and fight the user's scrolling.
     */
    public void setCurrent(Uri uri, String title, String artist) {
        boolean sameUri = uri == null ? currentUri == null : uri.equals(currentUri);
        boolean sameTitle = title == null ? currentTitle == null : title.equals(currentTitle);
        boolean sameArtist = artist == null ? currentArtist == null : artist.equals(currentArtist);
        if (sameUri && sameTitle && sameArtist) return;
        this.currentUri = uri;
        this.currentTitle = title;
        this.currentArtist = artist;
        notifyDataSetChanged();
    }

    private boolean isCurrent(Track track) {
        if (currentUri != null && track.uri != null && currentUri.equals(track.uri)) {
            return true;
        }
        return currentTitle != null && currentTitle.length() > 0
                && currentTitle.equals(track.displayTitle())
                && (currentArtist == null || currentArtist.length() == 0
                || currentArtist.equals(track.displayArtist()));
    }

    public void setOnTrackClick(OnTrackClick listener) {
        this.listener = listener;
    }

    public void setOnTrackLongClick(OnTrackLongClick listener) {
        this.longListener = listener;
    }

    public void setOnSelectionChanged(OnSelectionChanged listener) {
        this.selectionListener = listener;
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    /** Enters or leaves multi-select mode; clears the selection when leaving. */
    public void setSelectionMode(boolean mode) {
        if (selectionMode == mode) return;
        selectionMode = mode;
        if (!mode) {
            selected.clear();
        }
        notifyDataSetChanged();
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(mode ? selected.size() : -1);
        }
    }

    /** Row index of a track (accounts for group headers), or -1. */
    public int indexOfTrack(Uri uri, String title, String artist) {
        for (int i = 0; i < rows.size(); i++) {
            Object row = rows.get(i);
            if (!(row instanceof Track)) continue;
            Track track = (Track) row;
            if (uri != null && track.uri != null && uri.equals(track.uri)) return i;
            if (uri == null && title != null && title.length() > 0
                    && title.equals(track.displayTitle())
                    && (artist == null || artist.length() == 0
                    || artist.equals(track.displayArtist()))) {
                return i;
            }
        }
        return -1;
    }

    public int getSelectedCount() {
        return selected.size();
    }

    /**
     * 当前视图里被完整选中的分组数量（按专辑 / 按歌手时用于标题里的
     * "已选 12 首 · 2 个专辑"）；平铺列表返回 0。
     */
    public int getSelectedGroupCount() {
        int groups = 0;
        boolean anyCard = false;
        for (Object row : rows) {
            if (row instanceof GroupCard) {
                anyCard = true;
                if (isGroupSelected((GroupCard) row)) groups++;
            }
        }
        if (!anyCard && openGroup != null && isAllSelected()) return 1;
        return groups;
    }

    /** 当前视图里的歌是否已全部选中（分组视图按所有分组的歌算）。 */
    public boolean isAllSelected() {
        int total = 0;
        int picked = 0;
        for (Object row : rows) {
            if (row instanceof Track) {
                total++;
                if (selected.contains(key((Track) row))) picked++;
            } else if (row instanceof GroupCard) {
                for (Track track : ((GroupCard) row).tracks) {
                    total++;
                    if (selected.contains(key(track))) picked++;
                }
            }
        }
        return total > 0 && picked == total;
    }

    /** 全选当前视图（分组视图 = 每个分组里的全部歌曲）。 */
    public void selectAll() {
        for (Object row : rows) {
            if (row instanceof Track) {
                selected.add(key((Track) row));
            } else if (row instanceof GroupCard) {
                for (Track track : ((GroupCard) row).tracks) {
                    selected.add(key(track));
                }
            }
        }
        notifyDataSetChanged();
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(selected.size());
        }
    }

    /** 清空选择（仍留在多选模式，方便重新勾）。 */
    public void clearSelection() {
        if (selected.isEmpty()) return;
        selected.clear();
        notifyDataSetChanged();
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(0);
        }
    }

    /** 一个分组是否整组选中（用于卡片上的勾选框）。 */
    private boolean isGroupSelected(GroupCard card) {
        if (card == null || card.tracks.isEmpty()) return false;
        for (Track track : card.tracks) {
            if (!selected.contains(key(track))) return false;
        }
        return true;
    }

    /** Returns the selected tracks in library order (groups included). */
    public List<Track> getSelectedTracks() {
        List<Track> out = new ArrayList<>();
        for (Track track : source) {
            if (selected.contains(key(track))) out.add(track);
        }
        return out;
    }

    private static String key(Track track) {
        if (track == null) return "";
        if (track.uri != null) return track.uri.toString();
        return track.filePath == null ? "" : track.filePath;
    }

    @Override
    public int getItemViewType(int position) {
        Object row = rows.get(position);
        if (row instanceof GroupCard) return TYPE_CARD;
        return row instanceof Header ? TYPE_HEADER : TYPE_TRACK;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderHolder(inflater.inflate(R.layout.item_group_header, parent, false));
        }
        if (viewType == TYPE_CARD) {
            return new CardHolder(inflater.inflate(R.layout.item_group_card, parent, false));
        }
        return new Holder(inflater.inflate(R.layout.item_track, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        // The list fades its rows in (LibraryActivity.animateListIn sets alpha 0
        // and animates back). A row that gets recycled or rebound while that
        // animation is still pending keeps alpha 0 - the text disappears while
        // the row stays clickable. Every bind therefore starts from a clean
        // state.
        holder.itemView.animate().cancel();
        holder.itemView.setAlpha(1f);
        holder.itemView.setTranslationY(0f);
        // 多选里点一行会触发 notifyItemChanged→重新绑定，如果这里不把按压缩放
        // 复位，松手回弹的动画会被取消，那一行就一直是缩小的（看起来像变小了）。
        holder.itemView.setScaleX(1f);
        holder.itemView.setScaleY(1f);
        Object row = rows.get(position);
        if (row instanceof GroupCard) {
            GroupCard card = (GroupCard) row;
            CardHolder h = (CardHolder) holder;
            android.content.Context ctx = holder.itemView.getContext();
            h.title.setText("-".equals(card.title)
                    ? ctx.getString(groupBy == GroupBy.ARTIST
                    ? R.string.unknown_artist : R.string.unknown_album)
                    : card.title);
            String count = ctx.getString(R.string.group_track_count, card.count);
            String subtitle = card.first == null ? count
                    : (groupBy == GroupBy.ALBUM
                    ? card.first.displayArtist() : card.first.displayAlbum()) + " · " + count;
            h.subtitle.setText(subtitle);
            String artKey = CoverArtLoader.keyOf(card.key, card.first);
            if (coverLoader != null) {
                coverLoader.load(artKey, card.tracks, h.art, R.drawable.ic_airplay);
            } else {
                h.art.setImageResource(R.drawable.ic_airplay);
            }
            h.itemView.setOnClickListener(v -> {
                if (selectionMode) {
                    toggleSelection(position);
                } else if (groupListener != null) {
                    groupListener.onGroupClick(card.key);
                }
            });
            // 长按整张卡片 = 选中这个专辑 / 歌手的全部歌曲（多选里也能整组取消）
            h.itemView.setOnLongClickListener(v -> {
                if (selectionMode) return false;
                for (Track track : card.tracks) {
                    selected.add(key(track));
                }
                setSelectionMode(true);
                notifyDataSetChanged();
                if (selectionListener != null) {
                    selectionListener.onSelectionChanged(selected.size());
                }
                return true;
            });
            h.check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            h.check.setChecked(isGroupSelected(card));
            h.check.setAccentColor(ColorTheme.accent());
            // 选中只靠勾选框表示，行/卡片不再套淡色框
            h.itemView.setBackground(null);
            // A recycled tile must never keep the pressed-down scale.
            h.itemView.setScaleX(1f);
            h.itemView.setScaleY(1f);
            // Press feedback: the tile dips slightly while the finger is down.
            h.itemView.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90L).start();
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        v.animate().scaleX(1f).scaleY(1f).setDuration(140L).start();
                        break;
                    default:
                        break;
                }
                return false;
            });
            return;
        }
        if (row instanceof Header) {
            Header header = (Header) row;
            HeaderHolder h = (HeaderHolder) holder;
            h.title.setText("-".equals(header.title)
                    ? h.itemView.getContext().getString(R.string.unknown_album) : header.title);
            h.count.setText(h.itemView.getContext()
                    .getString(R.string.group_track_count, header.count));
            return;
        }

        Track track = (Track) row;
        Holder h = (Holder) holder;
        android.content.Context ctx = holder.itemView.getContext();
        String title = track.displayTitle();
        String artist = track.displayArtist();
        String album = track.displayAlbum();
        h.title.setText(Track.UNKNOWN_TITLE.equals(title)
                ? ctx.getString(R.string.unknown_title) : title);
        h.subtitle.setText(
                (Track.UNKNOWN_ARTIST.equals(artist)
                        ? ctx.getString(R.string.unknown_artist) : artist)
                        + " · "
                        + (Track.UNKNOWN_ALBUM.equals(album)
                        ? ctx.getString(R.string.unknown_album) : album));
        boolean isSelected = selected.contains(key(track));
        // 曲目列表每次绑定都会重设颜色：这里也必须用动态配色，否则换歌 / 滚动
        // 回来后正在播放的那首又变回固定的天蓝，其余行回到纯白。
        h.title.setTextColor(isCurrent(track)
                ? ColorTheme.textAccent() : ColorTheme.tooltipText());
        h.check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        h.check.setChecked(isSelected);
        h.check.setAccentColor(ColorTheme.accent());
        h.itemView.setBackground(null);
        h.itemView.setOnLongClickListener(v -> {
            if (!selectionMode) {
                setSelectionMode(true);
                selected.add(key(track));
                notifyItemChanged(position);
                if (selectionListener != null) {
                    selectionListener.onSelectionChanged(selected.size());
                }
                if (longListener != null) longListener.onTrackLongClick(track);
                return true;
            }
            return false;
        });
        h.itemView.setOnClickListener(v -> {
            if (selectionMode) {
                toggleSelection(position);
            } else if (listener != null) {
                listener.onTrackClick(track);
            }
        });
    }

    /** Toggles a row's selection (selection mode only). */
    public void toggleSelection(int position) {
        if (!selectionMode || position < 0 || position >= rows.size()) return;
        Object row = rows.get(position);
        if (row instanceof Track) {
            String trackKey = key((Track) row);
            if (!selected.remove(trackKey)) {
                selected.add(trackKey);
            }
        } else if (row instanceof GroupCard) {
            // 整张卡片：整组选中 / 整组取消
            GroupCard card = (GroupCard) row;
            boolean select = !isGroupSelected(card);
            for (Track track : card.tracks) {
                if (select) {
                    selected.add(key(track));
                } else {
                    selected.remove(key(track));
                }
            }
        } else {
            return;
        }
        notifyItemChanged(position);
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(selected.size());
        }
    }

    /** 勾选框颜色：选中=动态主色，未选=次级色。 */
    private static android.content.res.ColorStateList checkColors() {
        return new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{ColorTheme.accent(), ColorTheme.textSecondary()});
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final BukaCheckBox check;
        final TextView title;
        final TextView subtitle;

        Holder(@NonNull View itemView) {
            super(itemView);
            check = itemView.findViewById(R.id.track_check);
            title = itemView.findViewById(R.id.track_title);
            subtitle = itemView.findViewById(R.id.track_subtitle);
        }
    }

    static class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView count;

        HeaderHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.group_title);
            count = itemView.findViewById(R.id.group_count);
        }
    }

    static class CardHolder extends RecyclerView.ViewHolder {
        final android.widget.ImageView art;
        final TextView title;
        final TextView subtitle;
        final BukaCheckBox check;

        CardHolder(@NonNull View itemView) {
            super(itemView);
            art = itemView.findViewById(R.id.card_art);
            title = itemView.findViewById(R.id.card_title);
            subtitle = itemView.findViewById(R.id.card_subtitle);
            check = itemView.findViewById(R.id.card_check);
        }
    }
}
