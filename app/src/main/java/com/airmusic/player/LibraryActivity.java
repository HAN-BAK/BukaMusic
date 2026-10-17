package com.airmusic.player;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
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
import com.airmusic.player.ui.ColorTheme;

public class LibraryActivity extends BaseActivity {

    private final TrackAdapter adapter = new TrackAdapter();
    private TextView txtTitle;
    private TextView txtSelectCount;
    private ImageButton btnDelete;
    private ImageButton btnBack;
    private ImageButton btnSearch;
    private android.widget.EditText editSearch;
    private boolean searchMode;
    private com.google.android.material.button.MaterialButton btnSelectAll;
    private com.google.android.material.button.MaterialButton btnGroup;
    private RecyclerView list;
    private CoverArtLoader coverLoader;
    private int cardSpan = 3;
    /** Album / artist mode: jump to the playing song the first time we load. */
    private boolean locateCurrentOnFirstLoad;

    /** 从播放界面点歌手 / 专辑跳进来时带的参数。 */
    public static final String EXTRA_GROUP_BY = "com.airmusic.player.GROUP_BY";
    public static final String EXTRA_GROUP_KEY = "com.airmusic.player.GROUP_KEY";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);
        BlurBackground.apply(this, R.color.background);

        txtTitle = findViewById(R.id.txt_title);
        txtSelectCount = findViewById(R.id.txt_select_count);
        btnDelete = findViewById(R.id.btn_delete);
        btnSelectAll = findViewById(R.id.btn_select_all);
        btnSearch = findViewById(R.id.btn_search);
        editSearch = findViewById(R.id.edit_search);
        btnGroup = findViewById(R.id.btn_group);
        btnGroup.setOnClickListener(v -> showGroupDialog());
        btnSearch.setOnClickListener(v -> toggleSearch());
        editSearch.setBackground(ColorTheme.capsule(this));
        editSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                applySearch(s == null ? "" : s.toString());
            }
        });

        btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> {
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
        // 列数按「盒子的参考宽度」算：布局是按参考尺寸排版再整体缩放的，
        // 用设备自身宽高比会让不同设备出现不同列数和磁贴大小。
        int widthDp = Math.round(com.airmusic.player.view.BoxAspectFrameLayout.REF_WIDTH_DP);
        cardSpan = Math.max(2, Math.min(6, widthDp / 200));
        coverLoader = new CoverArtLoader(this);
        adapter.setCoverLoader(coverLoader);
        adapter.setOnGroupClick(key -> {
            adapter.openGroup(key);
            applyLayoutMode();
        });
        // 统一用 GridLayoutManager（列表模式 1 列、卡片模式多列），见 applyLayoutMode()。
        list.setLayoutManager(new GridLayoutManager(this, 1));
        list.setAdapter(adapter);
        // 勾选某一行时（notifyItemChanged）默认的 item 动画会把这一行连同下面
        // 的行做「移动/交叉淡入」处理，看着就是选中后整排往上挤。曲库有自己的
        // 入场动效，这里直接关掉列表自带的动画。
        list.setItemAnimator(null);
        // 诊断用：数据变化后，列表是否真的重新排布过子视图。
        // 每次数据变更只记一条，方便从日志判断是"没重排"还是"没重绘"。
        list.addOnChildAttachStateChangeListener(
                new RecyclerView.OnChildAttachStateChangeListener() {
                    @Override
                    public void onChildViewAttachedToWindow(android.view.View child) {
                        if (!logNextChildAttach) return;
                        logNextChildAttach = false;
                        Log.i("LibraryActivity", "列表重排完成：首个挂上的子视图="
                                + child.getClass().getSimpleName()
                                + " 当前子视图数=" + list.getChildCount()
                                + " 条目=" + adapter.getItemCount()
                                + " 分组=" + adapter.getGroupBy()
                                + " 展开=" + adapter.getOpenGroupKey());
                    }

                    @Override
                    public void onChildViewDetachedFromWindow(android.view.View child) {
                    }
                });
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

        // 从播放界面点歌手 / 专辑进来：直接切到对应分组并展开那一组
        String wantGroupBy = getIntent().getStringExtra(EXTRA_GROUP_BY);
        String wantGroupKey = getIntent().getStringExtra(EXTRA_GROUP_KEY);
        if (wantGroupBy != null && wantGroupKey != null && wantGroupKey.length() > 0) {
            try {
                TrackAdapter.GroupBy mode = TrackAdapter.GroupBy.valueOf(wantGroupBy);
                adapter.setGroupBy(mode);
                new Prefs(this).setLibraryGroup(mode.name());
                applyLayoutMode();
                String key = wantGroupKey.trim().isEmpty()
                        || Track.UNKNOWN_ALBUM.equals(wantGroupKey)
                        || Track.UNKNOWN_ARTIST.equals(wantGroupKey) ? "-" : wantGroupKey;
                adapter.openGroup(key);
                if (adapter.getItemCount() == 0) {
                    // 这一组里没有歌（比如元数据对不上），回到平铺列表
                    adapter.closeGroup();
                } else {
                    locateCurrentOnFirstLoad = false;
                }
                applyLayoutMode();
            } catch (Throwable ignored) {
            }
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
        btnSelectAll.setOnClickListener(v -> {
            // 全选 / 取消全选（分组视图里就是每个专辑 / 歌手的全部歌曲）
            if (adapter.isAllSelected()) {
                adapter.clearSelection();
            } else {
                adapter.selectAll();
            }
            updateSelectionUi();
        });

        // onResume 紧随其后一定会再 loadTracks()，这里不再重复拉一次
        // （重复调用会多排一轮扫描、多重建一次列表）。
    }

    /** Album / artist browsing uses a grid of tiles, the flat list stays a list. */
    private void applyLayoutMode() {
        // 根因侧防护：setLayoutManager() 会先把子视图全部回收再 requestLayout()，
        // 如果这一刻正好在布局中，那次请求会被 RecyclerView 自己吞掉，列表就再也排不
        // 出来了（适配器有数据、屏幕上却一片空白）。撞上就等这一轮布局结束再做。
        if (list != null && list.isComputingLayout()) {
            Log.w("LibraryActivity", "列表正在布局，延后应用浏览方式");
            list.postDelayed(this::applyLayoutMode, 60L);
            return;
        }
        RecyclerView.LayoutManager manager = list.getLayoutManager();
        list.stopScroll();
        // 列表 / 卡片统一用一个 GridLayoutManager：卡片模式多列、列表模式 1 列。
        //
        // 以前这里在 LinearLayoutManager 与 GridLayoutManager 之间来回换，而
        // setLayoutManager() 会先把所有子视图回收掉（mChildHelper.removeAllViewsUnfiltered），
        // 紧接着那次 requestLayout() 只要被 RecyclerView 自己的"正在布局"判定吞掉，
        // 列表就再也不会排布：适配器里 1547 条、屏幕上却一片空白——实测手机就卡成了
        // 这样，而且因为请求传不到根视图，连重建 RecyclerView 都救不回来。
        // setSpanCount() 只是请求重排，不动子视图，安全得多。
        int span = adapter.isShowingCards() ? cardSpan : 1;
        if (!(manager instanceof GridLayoutManager)) {
            list.setLayoutManager(new GridLayoutManager(this, span));
        } else if (((GridLayoutManager) manager).getSpanCount() != span) {
            ((GridLayoutManager) manager).setSpanCount(span);
        }
        String open = adapter.getOpenGroupTitle();
        txtTitle.setText(open == null ? getString(R.string.library) : open);
        txtTitle.setVisibility(adapter.isSelectionMode() || searchMode ? View.GONE : View.VISIBLE);
        btnGroup.setText(getString(labelFor(adapter.getGroupBy())));
        // 数据 / 分组方式变了就主动要一次布局与重绘，而且必须从根视图要：
        //
        // View.requestLayout() 里有一句 "父级已经在请求布局就不再往上请求"，所以只要
        // 视图树中某一级残留了 PFLAG_FORCE_LAYOUT（那次请求正好落在布局过程中被丢掉
        // 就会这样），它下面所有视图的布局请求都会停在那一级、永远传不到 ViewRootImpl：
        // 表现就是"数据都在、界面就是不排布/不重绘"，实测手机曲库整页空白、卡片还是
        // 旧尺寸，都出在这里。从 DecorView 请求一次，直接落到 ViewRootImpl，整棵树
        // 重新 measure/layout，顺便把残留标记清掉。
        forceRelayout();
        list.invalidate();
        animateListIn();
        healEmptyList();
        list.postDelayed(this::ensureListRendered, 400L);
    }

    /** 列表明明是空的但数据是满的，最多"强制布局"重试几次，再不行就换新视图。 */
    private static final int BLANK_RETRY_LIMIT = 2;
    private int blankRetries;

    /**
     * 兜底：RecyclerView 有数据却一个子视图都不排（屏幕上就是"一片空白、点歌也没反应"）。
     *
     * <p>实测手机上会出现这种状态：适配器里 1547 条（日志有记录），但视图层级里
     * {@code track_list} 一个子视图都没有。RecyclerView 在 {@code setLayoutManager()}
     * 时会先把所有子视图回收，紧跟的那次 {@code requestLayout()} 一旦被"布局中"的
     * 判定吞掉，就再也不会自己排布了。这里做两件事：
     * <ul>
     * <li>先 {@code forceLayout()}+让父容器请求布局——绕过 RecyclerView 自己的请求拦截；
     * <li>还不行就把整个 RecyclerView 换成新的实例（内部状态被卡住时唯一可靠的办法）。
     * </ul>
     */
    private void ensureListRendered() {
        if (list == null || isFinishing()) return;
        if (adapter.getItemCount() <= 0) {
            blankRetries = 0;
            return;
        }
        boolean sized = list.getWidth() > 0 && list.getHeight() > 0;
        if (sized && list.getChildCount() > 0) {
            blankRetries = 0;
            return;
        }
        if (!list.isShown()) return; // 页面不在前面，等回到前面时再说
        blankRetries++;
        Log.w("LibraryActivity", "列表" + (sized
                ? "有 " + adapter.getItemCount() + " 条数据却没有任何子视图"
                : "根本没被排过版（尺寸 0）") + "，第 " + blankRetries + " 次修复");
        if (blankRetries <= BLANK_RETRY_LIMIT) {
            forceListLayout();
            list.postDelayed(this::ensureListRendered, 400L);
            return;
        }
        if (blankRetries == BLANK_RETRY_LIMIT + 1) {
            rebuildList();
            list.postDelayed(this::ensureListRendered, 500L);
            return;
        }
        // 换过新视图还是不行：直接从根视图要一次完整布局——中间任何一环把
        // requestLayout 吞掉都会导致子树永远不排版。
        Log.w("LibraryActivity", "仍然没有排布，直接从根视图强制一次布局");
        forceRelayout();
        if (blankRetries > BLANK_RETRY_LIMIT + 3) {
            blankRetries = 0; // 别无限重试，交给下一次数据变化 / 回到页面再试
        } else {
            list.postDelayed(this::ensureListRendered, 600L);
        }
    }

    /** RecyclerView 自己的 requestLayout 可能被吞，绕到父容器 + 根视图再请求一次。 */
    private void forceListLayout() {
        try {
            list.forceLayout();
            View parent = (View) list.getParent();
            if (parent != null) parent.requestLayout();
            list.invalidate();
            forceRelayout();
        } catch (Throwable t) {
            Log.w("LibraryActivity", "强制列表布局失败", t);
        }
    }

    /**
     * 真正强制列表重新排一次版。
     *
     * <p>只在 DecorView（或任何单个视图）上 requestLayout() 是**没用**的：
     * {@code View.requestLayout()} 只给被调用的那个视图打 PFLAG_FORCE_LAYOUT，再往上逐级
     * notify；窗口尺寸没变时，ViewRootImpl 遍历里中间那几层不会重新 measure，
     * 也就没有 PFLAG_LAYOUT_REQUIRED，{@code onLayout} 会被整段跳过——表现就是
     * "数据变了、画面还是上一种模式的样子，摸一下屏幕（滚动让列表自己重排）才更新"。
     *
     * <p>正确做法：从列表本身往上逐级 {@code forceLayout()}（让这条链上每一级都会重新
     * measure），再从列表的父容器 {@code requestLayout()} 一次，把这次的遍历请求送到
     * ViewRootImpl。这样整条链都会重新 measure + onLayout，列表才会真正重新排布子视图。
     */
    private void forceRelayout() {
        if (list == null) return;
        try {
            // 1) 从列表往上逐级打"必须重新 measure"的标记：否则窗口尺寸没变时，
            //    中间几层会因为 spec 相同直接复用旧测量结果，没有 PFLAG_LAYOUT_REQUIRED，
            //    onLayout 就被整段跳过（数据变了、画面还是旧的）。
            View view = list;
            while (view != null) {
                view.forceLayout();
                android.view.ViewParent parent = view.getParent();
                view = parent instanceof View ? (View) parent : null;
            }
            // 2) 关键：必须再从**最外层**请求一次布局。
            //    View.requestLayout() 里有一句 "父级已经在请求布局就不再往上请求"，
            //    而第 1 步正好给每一级都打了标记——如果从列表或它的父容器请求，这次请求
            //    会在中途停下、传不到 ViewRootImpl，于是不会调度遍历，标记白打（上一次
            //    修好一半就是这个原因）。DecorView 的直接父级就是 ViewRootImpl，
            //    从它请求一定能落到 ViewRootImpl 上。
            View root = getWindow() != null ? getWindow().getDecorView() : list.getRootView();
            if (root != null) {
                root.requestLayout();
                root.invalidate();
            }
            list.invalidate();
        } catch (Throwable t) {
            Log.w("LibraryActivity", "强制列表重排失败", t);
        }
    }

    /** 换一个全新的 RecyclerView（保留位置、内边距、布局参数），彻底摆脱卡住的状态。 */
    private void rebuildList() {
        try {
            ViewGroup parent = (ViewGroup) list.getParent();
            if (parent == null) return;
            int index = parent.indexOfChild(list);
            ViewGroup.LayoutParams lp = list.getLayoutParams();
            int padBottom = list.getPaddingBottom();
            // XML 里 clipToPadding="false"：保持和原列表一致。
            boolean clipToPadding = false;
            int overScroll = list.getOverScrollMode();
            RecyclerView fresh = new RecyclerView(this);
            fresh.setId(R.id.track_list);
            fresh.setLayoutParams(lp);
            fresh.setClipToPadding(clipToPadding);
            fresh.setPadding(list.getPaddingLeft(), list.getPaddingTop(),
                    list.getPaddingRight(), padBottom);
            fresh.setOverScrollMode(overScroll);
            fresh.setItemAnimator(null);
            fresh.setLayoutManager(new GridLayoutManager(this,
                    adapter.isShowingCards() ? cardSpan : 1));
            fresh.setAdapter(adapter);
            parent.removeView(list);
            parent.addView(fresh, Math.max(0, index));
            list = fresh;
            Log.w("LibraryActivity", "已重建曲库列表视图");
            forceRelayout();
            PlaybackService service = PlaybackService.getInstance();
            List<Track> tracks = service != null
                    ? service.getLibraryTracks()
                    : MusicLibrary.getInstance().getCachedTracks();
            if (tracks != null && !tracks.isEmpty()) {
                adapter.setTracks(tracks);
            }
        } catch (Throwable t) {
            Log.w("LibraryActivity", "重建列表视图失败", t);
        }
    }

    /**
     * 自愈：列表一条都没有、曲库却明明有歌时，再塞一次数据。
     *
     * <p>实测出现过"打开曲库一片空白、点歌也没反应"：服务被停掉、或扫描回调正好
     * 撞上 RecyclerView 布局而没进去，列表就一直是空的。这里兜一层，并且把
     * 数据量打进日志，方便下次直接从日志看出是哪一步丢了。
     */
    private void healEmptyList() {
        if (adapter.getItemCount() > 0) {
            return;
        }
        PlaybackService service = PlaybackService.getInstance();
        List<Track> tracks = service != null
                ? service.getLibraryTracks()
                : MusicLibrary.getInstance().getCachedTracks();
        int count = tracks == null ? 0 : tracks.size();
        Log.w("LibraryActivity", "列表为空：service=" + (service != null)
                + " tracks=" + count + " group=" + adapter.getGroupBy()
                + " open=" + adapter.getOpenGroupKey()
                + " 搜索中=" + adapter.hasQuery());
        if (adapter.hasQuery()) {
            return; // 正常的"搜索无结果"，不能拿全部曲目盖掉
        }
        if (count > 0) {
            Log.w("LibraryActivity", "重填一次列表数据：" + count + " 首");
            adapter.setTracks(tracks);
        }
    }

    /**
     * Staggered fade / rise for the rows or tiles that just appeared, so
     * switching between the tile grid and one album's songs animates instead of
     * snapping.
     */
    private void animateListIn() {
        // 关键：必须等这一帧的排版完成之后再做淡入。
        //
        // 原来用 list.post 直接跑：打开专辑/歌手分组时「notifyDataSetChanged 的排版」
        // 还没发生，这里拿到的还是即将被回收重用的旧视图；设成 alpha 0 后动画又因为
        // 视图被重绑而取消，于是新行继承 alpha 0——列表看着是空的（文字不见但可点），
        // 碰一下屏幕（按压缩放效果复位了 alpha）才显示。手机上跑得快，正好踩中这个
        // 顺序；盒子上因为排版先完成，所以一直没暴露。
        // 用 onPreDraw 一次性回调：它在排版之后、绘制之前，此时拿到的一定是新行。
        list.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        if (list.getViewTreeObserver().isAlive()) {
                            list.getViewTreeObserver().removeOnPreDrawListener(this);
                        }
                        fadeChildrenIn();
                        return true;
                    }
                });
    }

    private void fadeChildrenIn() {
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
        // 兜底：不管上面动画因为什么没跑完/被取消，都保证行最终可见
        // （视图被回收重绑、动画被取消等情况都覆盖得到）。
        list.postDelayed(this::forceRowsVisible, 700L);
    }

    /** 把任何还停在透明/位移状态的行复位成完全可见。 */
    private void forceRowsVisible() {
        if (isFinishing()) return;
        for (int i = 0; i < list.getChildCount(); i++) {
            android.view.View child = list.getChildAt(i);
            if (child == null) continue;
            if (child.getAlpha() < 1f) child.setAlpha(1f);
            if (child.getTranslationY() != 0f) child.setTranslationY(0f);
        }
    }

    /**
     * 在 RecyclerView 空闲（不在布局/滚动中）时执行列表变更。
     *
     * <p>RecyclerView 在计算布局或滚动时禁止改数据：notifyDataSetChanged /
     * setLayoutManager / scrollToPosition 都会抛 IllegalStateException
     * （"Cannot call this method while RecyclerView is computing a layout or scrolling"），
     * 未捕获就是闪退 + "Force finishing activity"，用户在手机上看到的就是"曲库直接卡死"。
     * 扫描结束、按歌手/专辑切换、定位当前歌曲这些时机都可能撞上，统一走这里排队。
     *
     * <p>但排队不能无限等：RecyclerView 的 mLayoutOrScrollCounter 一旦因为某次布局里
     * 抛异常没有归零，isComputingLayout() 会一直是 true，列表就永远填不进去（表现就是
     * "打开曲库一片空白、点歌也没反应"）。所以最多等 MAX_LIST_IDLE_RETRIES 次，之后
     * 强行走一次，并记录异常；动作本身也包了 try/catch，宁可少刷一次列表也不要闪退。
     */
    private static final int MAX_LIST_IDLE_RETRIES = 20;
    private int listIdleRetries;
    /** 数据刚变更过，等下一次有子视图挂上来时记一条诊断日志。 */
    private boolean logNextChildAttach;

    private void onListIdle(Runnable action) {
        if (list == null || isFinishing()) return;
        if (list.isComputingLayout()) {
            if (++listIdleRetries > MAX_LIST_IDLE_RETRIES) {
                listIdleRetries = 0;
                Log.w("LibraryActivity", "列表长时间处于布局中，强制执行列表变更");
                runListChange(action);
                return;
            }
            list.postDelayed(() -> onListIdle(action), 50L);
            return;
        }
        listIdleRetries = 0;
        runListChange(action);
    }

    private void runListChange(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            Log.w("LibraryActivity", "列表变更失败，已跳过本次刷新", t);
        }
        logNextChildAttach = true;
        // 数据变了就顺带从根视图要一次布局：中间任何一级残留的布局标记都可能把
        // 列表自己的 requestLayout 挡住（见 applyLayoutMode 的注释）。
        forceRelayout();
        // 每次改完数据都顺手检查一次"有数据但没排布"的卡住状态。
        if (list != null) {
            list.postDelayed(this::ensureListRendered, 300L);
        }
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
        // 隐藏的搜索框里可能还留着上次恢复出来的过滤词（窗口重建会把 EditText 的内容
        // 一起恢复，而搜索状态没恢复），列表就会被悄悄过滤成空：看着"没有内容"、点歌
        // 也没反应。没处于搜索状态就把它清掉。
        if (!searchMode && adapter.hasQuery()) {
            Log.w("LibraryActivity", "清掉隐藏的搜索词，避免列表被过滤成空");
            editSearch.setText("");
        }
        loadTracks();
        // 回到本页时再要一次重绘：手机上出现过「离开时数据变了、回来画面还是旧的，
        // 碰一下屏幕才刷新」（dumpsys gfxinfo 显示期间一帧都没画）。主动 invalidate
        // 一次即可自愈，代价可忽略。
        list.post(() -> {
            if (isFinishing()) return;
            list.requestLayout();
            list.invalidate();
            forceRelayout();
        });
        // 回到本页时也检查一次"有数据但没排布"的卡住状态。
        list.postDelayed(this::ensureListRendered, 600L);
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

    @Override
    protected void onDestroy() {
        // 封面加载器的线程池与缓存要跟着页面一起关掉（否则每次进曲库都会
        // 留下常驻线程 + 最多 32MB 封面位图，反复进出后内存积起来就卡死）。
        if (coverLoader != null) {
            coverLoader.close();
            coverLoader = null;
        }
        super.onDestroy();
    }

    private final StateBus.Listener stateListener = state -> runOnUiThread(() -> {
        // 停在曲库页时突然进入 AirPlay / 多房间接收：自动回到播放界面
        if (state.source == com.airmusic.player.util.PlayerUiState.Source.AIRPLAY
                || state.source == com.airmusic.player.util.PlayerUiState.Source.REMOTE) {
            if (!isFinishing()) finish();
            return;
        }
        // 高亮刷新内部会 notifyItemChanged，同样不能在 RecyclerView 布局/滚动中执行
        onListIdle(this::refreshCurrentHighlight);
    });
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
        // 服务还没起来时也先把上次的扫描结果读回来，界面不用等扫描
        MusicLibrary.getInstance().loadFromDisk(this);
        PlaybackService service = PlaybackService.getInstance();
        List<Track> tracks = service != null
                ? service.getLibraryTracks()
                : MusicLibrary.getInstance().getCachedTracks();
        Log.i("LibraryActivity", "loadTracks: service=" + (service != null)
                + " tracks=" + (tracks == null ? -1 : tracks.size()));
        if (tracks != null && !tracks.isEmpty()) {
            onListIdle(() -> {
                adapter.setTracks(tracks);
                applyCurrentTrack(tracks);
                locateCurrentInGroup(tracks);
            });
        }
        // 新建的文件仍会出现：15 秒内的重复进入直接用上一轮结果，
        // 更久的、手动「重新扫描」、上传完成都会走真正的扫描。
        if (MusicLibrary.getInstance().scanIsFresh(15_000L)) {
            return;
        }
        MusicLibrary.getInstance().rescan(this, (result, error) -> {
            if (service != null && result != null) {
                service.setTracks(result);
            }
            if (result != null) {
                // 扫描完成的回调是 post 到主线程的，仍可能正好落在 RecyclerView 的
                // 布局/滚动中，这时改数据会抛 IllegalStateException 并被系统杀掉
                // （手机上"曲库直接卡死"就是这么来的），所以统一排到空闲时执行。
                onListIdle(() -> {
                    adapter.setTracks(result);
                    applyCurrentTrack(result);
                    locateCurrentInGroup(result);
                });
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
        // 进出多选会让列表重新测量一次，RecyclerView 会把锚点算偏（看起来就是
        // 选中时整排往上跳）。这里先记下第一可见项和偏移，换完再恢复。
        boolean modeChanged = lastSelectionMode == null || lastSelectionMode != selecting;
        if (modeChanged) snapshotScrollAnchor();
        lastSelectionMode = selecting;
        txtTitle.setVisibility(selecting || searchMode ? View.GONE : View.VISIBLE);
        editSearch.setVisibility(!selecting && searchMode ? View.VISIBLE : View.GONE);
        btnSearch.setVisibility(selecting ? View.GONE : View.VISIBLE);
        btnGroup.setVisibility(selecting ? View.GONE : View.VISIBLE);
        txtSelectCount.setVisibility(selecting ? View.VISIBLE : View.GONE);
        btnSelectAll.setVisibility(selecting ? View.VISIBLE : View.GONE);
        btnDelete.setVisibility(selecting ? View.VISIBLE : View.GONE);
        // 没勾任何东西时删除按钮变灰，避免点了没反应
        boolean canDelete = selecting && adapter.getSelectedCount() > 0;
        btnDelete.setEnabled(canDelete);
        btnDelete.setAlpha(canDelete ? 1f : 0.35f);
        // 多选模式下返回键换成关闭 X（同一个位置，不额外占宽度）
        btnBack.setImageResource(selecting ? R.drawable.ic_close : R.drawable.ic_back);
        btnBack.setContentDescription(getString(
                selecting ? R.string.exit_selection : R.string.previous));
        if (selecting) {
            txtSelectCount.setText(selectionSummary());
            btnSelectAll.setText(getString(adapter.isAllSelected()
                    ? R.string.deselect_all : R.string.select_all));
        } else {
            String open = adapter.getOpenGroupTitle();
            txtTitle.setText(open == null ? getString(R.string.library) : open);
        }
        if (modeChanged) restoreScrollAnchor();
    }

    /** 进出多选时用到的滚动位置（像素）。 */
    private Boolean lastSelectionMode;
    private int anchorScrollOffset;

    private void snapshotScrollAnchor() {
        anchorScrollOffset = list.computeVerticalScrollOffset();
    }

    private void restoreScrollAnchor() {
        // 两种模式下每行高度已经一致，这里只把切换前后差出来的滚动像素补回去，
        // 列表就不会有那一下跳动。等 layout 落定再补。
        final int before = anchorScrollOffset;
        list.postDelayed(() -> {
            if (isFinishing()) return;
            int now = list.computeVerticalScrollOffset();
            if (now != before) list.scrollBy(0, now - before);
        }, 60L);
    }

    /** 多选标题：平铺列表只报首数，按专辑 / 按歌手还会报选中了几个分组。 */
    private CharSequence selectionSummary() {
        int songs = adapter.getSelectedCount();
        int groups = adapter.getSelectedGroupCount();
        if (groups <= 0 || adapter.getGroupBy() == TrackAdapter.GroupBy.NONE) {
            return getString(R.string.selected_count, songs);
        }
        return getString(adapter.getGroupBy() == TrackAdapter.GroupBy.ALBUM
                        ? R.string.selected_count_album : R.string.selected_count_artist,
                songs, groups);
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
        // 对话框外观保持原样（当前项照旧打勾、照样可以点，只是点了不会做任何事）。
        BukaDialog.singleChoice(this, getString(R.string.group_title), labels, checked, which -> {
            if (modes[which] == current) {
                // 已经是这个模式了：再选一次没有任何意义，而且实测这一下会重新应用一次
                // 分类（重排 + 重绑），盒子上 janky 帧直接涨到 40%，手机上页面还会残留
                // 上一种模式的视图、显得又卡又异常。直接忽略这次选择（对话框照常关闭）。
                Log.i("LibraryActivity", "当前已是该分类，忽略重复选择：" + labels[which]);
                return;
            }
            adapter.setGroupBy(modes[which]);
            new Prefs(this).setLibraryGroup(modes[which].name());
            btnGroup.setText(labels[which]);
            applyLayoutMode();
            // 对话框还是独立窗口，关掉它的前后会各发生一次窗口焦点/可见性变化：这里
            // 等它真正消失后再来一遍排版与定位，否则列表会停在旧模式的画面上
            // （要点一下屏幕才刷新）。
            list.postDelayed(() -> {
                if (isFinishing()) return;
                applyLayoutMode();
                onListIdle(() -> {
                    adapter.notifyDataSetChanged();
                    scrollToCurrentTrack();
                    forceRelayout();
                });
            }, 160L);
        }).show();
    }

    /**
     * 别的窗口（对话框等）让开、本页面重新拿到焦点时，确认列表是最新的。
     *
     * <p>实测：切换"曲库分类"后页面仍旧显示上一种模式的画面（文字还会溢出色块），
     * 摸一下屏幕才刷新——弹出对话框期间发出的排版请求有时不会真的走一遍 measure/layout，
     * 焦点回来时补一次完整的重新绑定 + 从根视图请求布局即可。
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || list == null || isFinishing()) return;
        if (adapter.getItemCount() <= 0) return;
        onListIdle(() -> {
            adapter.notifyDataSetChanged();
            list.requestLayout();
            forceRelayout();
            list.postDelayed(this::ensureListRendered, 400L);
        });
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
        if (searchMode) {
            // 先退出搜索，再退出分组 / 页面
            toggleSearch();
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

    /** 放大镜：展开 / 收起搜索框。 */
    private void toggleSearch() {
        searchMode = !searchMode;
        if (searchMode) {
            editSearch.setVisibility(View.VISIBLE);
            editSearch.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(editSearch, 0);
        } else {
            editSearch.setText("");
            editSearch.setVisibility(View.GONE);
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(editSearch.getWindowToken(), 0);
            applySearch("");
        }
        updateSelectionUi();
    }

    /** 过滤当前视图（打开专辑 / 歌手时只在该范围内搜）。 */
    private void applySearch(String text) {
        adapter.setQuery(text);
        applyLayoutMode();
        if (adapter.hasQuery() && adapter.getItemCount() == 0) {
            BukaNotice.show(this, R.string.search_no_result);
        }
    }

    private void confirmDelete() {
        final List<Track> selected = adapter.getSelectedTracks();
        if (selected.isEmpty()) {
            exitSelectionMode();
            return;
        }
        // 整张专辑 / 整个歌手被选中时，提示里说清楚删掉的是哪些分组
        int groups = adapter.getSelectedGroupCount();
        CharSequence message;
        if (groups > 0 && adapter.getGroupBy() == TrackAdapter.GroupBy.ALBUM) {
            message = getString(R.string.delete_groups_confirm_album, groups, selected.size());
        } else if (groups > 0 && adapter.getGroupBy() == TrackAdapter.GroupBy.ARTIST) {
            message = getString(R.string.delete_groups_confirm_artist, groups, selected.size());
        } else {
            message = getString(R.string.delete_files_confirm, selected.size());
        }
        BukaDialog.confirm(this, getString(R.string.delete), message,
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
