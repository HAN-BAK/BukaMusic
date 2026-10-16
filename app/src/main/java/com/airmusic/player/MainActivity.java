package com.airmusic.player;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.airmusic.player.multicast.MultiRoomDiscovery;
import com.airmusic.player.multicast.MultiRoomAudioPlayer;
import com.airmusic.player.multicast.MultiRoomManager;
import com.airmusic.player.playback.EqAudioProcessor;
import com.airmusic.player.service.PlaybackService;
import com.airmusic.player.util.PlayerUiState;
import com.airmusic.player.util.BlurBackground;
import com.airmusic.player.util.Prefs;
import com.airmusic.player.util.StateBus;

import nz.co.iswe.android.airplay.audio.AudioOutputQueue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.airmusic.player.ui.BukaDialog;
import com.airmusic.player.ui.BukaTheme;
import com.airmusic.player.ui.ColorTheme;
import com.airmusic.player.ui.BukaIcons;
import com.airmusic.player.ui.TrackAdapter;

public class MainActivity extends BaseActivity {

    /** Set by the settings screen to replay the first-run feature tour. */
    public static final String EXTRA_SHOW_TOUR = "com.airmusic.player.SHOW_TOUR";

    private static final String TAG = "MainActivity";
    private static final String ACTION_CAPTURE_START = "com.airmusic.player.CAPTURE_START";
    private static final String ACTION_CAPTURE_STOP = "com.airmusic.player.CAPTURE_STOP";

    private ImageView albumArt;
    private TextView trackTitle;
    /** 本次手势是否已经判定为上滑进屏保（用来吃掉松手的点击）。 */
    private boolean swipeOpened;
    private boolean sawScreensaverSwipe;
    private float swipeDownX, swipeDownY;
    private long swipeDownAt;

    /**
     * 屏保入口：整页「上滑」。统一在这里判定，不再依赖子视图的触摸监听
     * （封面等可点击子视图会先消费触摸，挂在上面的监听收不到滑动）。
     *
     * <p>只在落点不在底栏（进度条 / 音量条 / 底栏按钮）上时才参与，
     * 判定成功后不消费事件、也不影响原有交互，只是让封面的点击忽略这一次。
     */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (screensaverPrefs == null) screensaverPrefs = new Prefs(this);
        if (screensaverPrefs.isScreensaverSwipe()) {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    sawScreensaverSwipe = false;
                    swipeDownX = ev.getX();
                    swipeDownY = ev.getY();
                    swipeDownAt = android.os.SystemClock.elapsedRealtime();
                    swipeStartedInBottomBar = inBottomBar(ev.getX(), ev.getY());
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (!sawScreensaverSwipe && !swipeStartedInBottomBar) {
                        float up = swipeDownY - ev.getY();
                        float sideways = Math.abs(ev.getX() - swipeDownX);
                        long elapsed = Math.max(1L,
                                android.os.SystemClock.elapsedRealtime() - swipeDownAt);
                        float speed = up / elapsed;                 // px/ms
                        boolean vertical = up > 0 && up > sideways * 1.5f;
                        if (vertical) {
                            // 跟手：整页跟着手指上移（最多 40%，超出部分有阻尼感）
                            View content = findViewById(android.R.id.content);
                            if (content != null) {
                                content.setTranslationY(-Math.min(up * 0.4f, up));
                            }
                        }
                        if (up > 24 * getResources().getDisplayMetrics().density
                                && vertical && speed > 0.4f) {
                            sawScreensaverSwipe = true;
                            // 提交前先复位位移，否则返回播放页时整页会往上偏
                            View dragContent = findViewById(android.R.id.content);
                            if (dragContent != null) dragContent.setTranslationY(0f);
                            startActivity(new Intent(this, ScreensaverActivity.class));
                            overridePendingTransition(R.anim.ss_enter, R.anim.ss_stay);
                        }
                    }
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    View content = findViewById(android.R.id.content);
                    if (content != null && content.getTranslationY() != 0f) {
                        // 已经提交（进屏保）就立刻复位，否则弹回
                        if (sawScreensaverSwipe) {
                            content.setTranslationY(0f);
                        } else {
                            content.animate().translationY(0f).setDuration(180L).start();
                        }
                    }
                    break;
                default:
                    break;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private boolean swipeStartedInBottomBar;
    private Prefs screensaverPrefs;

    /** 落点是否在底栏（进度条 / 音量条 / 底栏按钮）范围内。 */
    private boolean inBottomBar(float x, float y) {
        View bar = findViewById(R.id.bottom_bar);
        if (bar == null) return false;
        int[] location = new int[2];
        bar.getLocationOnScreen(location);
        return y >= location[1] - 8
                && x >= location[0] && x <= location[0] + bar.getWidth();
    }
    private TextView trackArtist;
    private TextView trackAlbum;
    private TextView sourceBadge;
    private TextView positionText;
    private TextView durationText;
    private ImageButton btnPlay;
    private ImageButton btnNext;
    private ImageButton btnPrev;
    private ImageButton btnMulticast;
    private ImageButton btnLibrary;
    private ImageButton btnApps;
    private com.airmusic.player.ui.BukaSpinner multicastProgress;
    private com.airmusic.player.ui.BukaSpinner playProgress;
    private com.airmusic.player.ui.BukaSpinner nextProgress;
    private com.airmusic.player.ui.BukaSpinner prevProgress;
    private ImageView volumeIcon;
    private com.google.android.material.slider.Slider seekBar;
    private com.google.android.material.slider.Slider volumeSeek;
    private com.airmusic.player.ui.SliderTrackView volumeTrack;
    private com.airmusic.player.ui.SliderTrackView seekTrack;
    /** 音量图标当前档位（0 静音 / 1 低 / 2 中 / 3 高）。 */
    private int volumeIconLevel = -1;
    /** 音量条几何（dp）：轨道 44dp（和底栏按钮的圆形背景差不多粗）。 */
    private static final float VOLUME_TRACK_HEIGHT_DP = 44f;
    private static final float VOLUME_THUMB_WIDTH_DP = 8f;
    private static final float VOLUME_THUMB_HEIGHT_DP = 52f;
    /** 断口宽度和进度条保持一致（8dp）。 */
    private static final float VOLUME_THUMB_GAP_DP = 8f;
    /** 进度条几何（dp）。 */
    private static final float SEEK_TRACK_HEIGHT_DP = 24f;
    private static final float SEEK_THUMB_WIDTH_DP = 7f;
    private static final float SEEK_THUMB_HEIGHT_DP = 36f;
    private static final float SEEK_THUMB_GAP_DP = 8f;
    /** 四档音量图标（静音 / 低 / 中 / 高）。 */
    private static final int[] VOLUME_ICONS = {
            R.drawable.ic_volume_level0,
            R.drawable.ic_volume_level1,
            R.drawable.ic_volume_level2,
            R.drawable.ic_volume_level3,
    };
    private View seekRow;

    private boolean seeking;
    /** The play/pause glyph state currently shown on the button. */
    private Boolean playGlyphPlaying;
    /** False while a receiver transport spinner replaces the play glyph. */
    private boolean playGlyphVisible = true;
    private PlayerUiState lastState;
    /** True while a receiver transport command is waiting on the master. */
    private boolean controlPending;
    private AudioManager audioManager;
    private ContentObserver volumeObserver;
    private final Handler volumePollHandler = new Handler(Looper.getMainLooper());
    private final Runnable volumePoll = new Runnable() {
        @Override
        public void run() {
            syncVolumeSlider();
            volumePollHandler.postDelayed(this, 400);
        }
    };

    /** Restores the disconnect button if the remote session never ends. */
    private final Runnable restoreMulticastUi = new Runnable() {
        @Override
        public void run() {
            multicastProgress.setVisibility(View.GONE);
            btnMulticast.setVisibility(View.VISIBLE);
        }
    };

    /** Safety net: drop the loading spinner if the master never replies. */
    private final Runnable controlTimeoutRunnable = this::hideControlSpinners;

    private final PlaybackService.ControlAckListener controlAckListener =
            this::hideControlSpinners;

    private final StateBus.Listener stateListener = state -> {
        lastState = state;
        render(state);
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                PlaybackService service = PlaybackService.getInstance();
                if (service != null) service.rescanLibrary();
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        BlurBackground.apply(this, R.drawable.bg_main_gradient);
        handleCaptureIntent(getIntent());
        // First launch: introduce the features that are easy to miss.
        OnboardingOverlay.showIfNeeded(this);
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_SHOW_TOUR, false)) {
            getIntent().removeExtra(EXTRA_SHOW_TOUR);
            findViewById(android.R.id.content).post(() -> OnboardingOverlay.show(this));
        }

        albumArt = findViewById(R.id.album_art);
        trackTitle = findViewById(R.id.track_title);
        trackArtist = findViewById(R.id.track_artist);
        trackAlbum = findViewById(R.id.track_album);
        sourceBadge = findViewById(R.id.source_badge);
        positionText = findViewById(R.id.position_text);
        durationText = findViewById(R.id.duration_text);
        btnPlay = findViewById(R.id.btn_play);
        btnNext = findViewById(R.id.btn_next);
        btnPrev = findViewById(R.id.btn_prev);
        btnMulticast = findViewById(R.id.btn_multicast);
        btnLibrary = findViewById(R.id.btn_library);
        multicastProgress = findViewById(R.id.multicast_progress);
        playProgress = findViewById(R.id.play_progress);
        nextProgress = findViewById(R.id.next_progress);
        prevProgress = findViewById(R.id.prev_progress);
        volumeIcon = findViewById(R.id.volume_icon);
        btnApps = findViewById(R.id.btn_apps);
        trackTitle.setOnClickListener(null);
        trackArtist.setOnClickListener(v ->
                openLibraryFor(TrackAdapter.GroupBy.ARTIST, trackArtist.getText()));
        trackAlbum.setOnClickListener(v ->
                openLibraryFor(TrackAdapter.GroupBy.ALBUM, trackAlbum.getText()));
        seekBar = findViewById(R.id.seek_bar);
        volumeSeek = findViewById(R.id.volume_seek);
        volumeTrack = findViewById(R.id.volume_track);
        seekTrack = findViewById(R.id.seek_track);
        // 音量图标从一开始就交给自绘层画（原 ImageView 只负责占位与量尺寸），
        // 这样不会出现「先由 ImageView 画一帧、再换自绘层」的闪烁。
        if (volumeTrack != null && volumeIcon != null) {
            volumeTrack.setIconSource(volumeIcon);
            volumeIcon.setVisibility(View.INVISIBLE);
        }
        seekRow = findViewById(R.id.seek_row);
        // 播放界面的按钮统一用圆形边框（其它页面是圆角方形）。
        // 播放键保持原来的实心主色圆，不加描边
        BukaTheme.circleButtons(this, R.id.btn_prev, R.id.btn_next,
                R.id.btn_multicast, R.id.btn_library, R.id.btn_settings, R.id.btn_apps);
        // 底栏换成新的线性图标，并作为动效图标（按下时播放一次动画）
        BukaIcons.view(findViewById(R.id.btn_library), R.drawable.ic_anim_nav_library, false);
        BukaIcons.view(findViewById(R.id.btn_settings), R.drawable.ic_anim_gear, false);
        BukaIcons.view(findViewById(R.id.btn_apps), R.drawable.ic_anim_nav_apps, false);
        BukaIcons.view(findViewById(R.id.btn_multicast), R.drawable.ic_anim_nav_cast, false);
        View leftPanel = findViewById(R.id.left_panel);
        // 屏保入口：播放页「上滑」。只挂在封面区和右侧信息区（不碰底栏滑条），
        // 阈值同时要求位移和速度，避免正常点击 / 拖动进度被误判。
        final android.view.GestureDetector screensaverSwipe =
                new android.view.GestureDetector(this,
                        new android.view.GestureDetector.SimpleOnGestureListener() {
                            @Override
                            public boolean onFling(android.view.MotionEvent e1,
                                                   android.view.MotionEvent e2,
                                                   float velocityX, float velocityY) {
                                return false;   // 由 dispatchTouchEvent 的跟手拖动逻辑处理
                            }
                        });
        android.view.View.OnTouchListener swipeListener = (v, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                swipeOpened = false;
            }
            if (screensaverSwipe.onTouchEvent(event)) {
                // 判定为「上滑进屏保」：吃掉后续事件，避免松手时又触发封面点击(进歌词页)
                swipeOpened = true;
            }
            return swipeOpened;
        };
        leftPanel.setOnTouchListener(swipeListener);
        // 封面本身是可点击的子视图，会先吃掉触摸：必须挂在封面自己身上，
        // 否则上滑手势被它消费，永远触发不到（表现就是只进了歌词页）。
        albumArt.setOnTouchListener(swipeListener);
        View rightPanelSwipe = findViewById(R.id.right_panel);
        if (rightPanelSwipe != null) rightPanelSwipe.setOnTouchListener(swipeListener);

        setupVolumeSlider();
        // 按住进度条 / 音量条时，整条（轨道+手柄）轻微缩放，和按钮同一套按压反馈
        View seekHost = findViewById(R.id.seek_track);
        View volumeBarForScale = findViewById(R.id.volume_bar);
        if (seekHost != null && seekBar != null && seekHost.getParent() instanceof View) {
            com.airmusic.player.ui.PressFx.attachScaled(
                    seekBar, (View) seekHost.getParent(), 0.97f);
        }
        if (volumeBarForScale != null && volumeSeek != null) {
            com.airmusic.player.ui.PressFx.attachScaled(volumeSeek, volumeBarForScale, 0.97f);
        }
        // 音量轨道的两端要贴着「图标右侧」和「胶囊右端」，等布局完成后再对齐。
        View volumeBar = findViewById(R.id.volume_bar);
        if (volumeBar != null) {
            volumeBar.post(() -> {
                layoutSliderTracks();
                updateVolumeIcon(audioManager == null ? 0
                        : audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
            });
        }

        // Size the album art square and center it in the left panel (same
        // vertical position as the playback controls). The panel wraps around
        // the cover; a safety cap keeps it from crowding out the right panel.
        // 用全局布局监听而不是 post：post 有时会在布局完成前跑（或根本没跑到），
        // 这里每次布局都执行，并且把关键数字打进日志，任何设备上一眼能核对。
        leftPanel.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            float density = getResources().getDisplayMetrics().density;
            int panelW = leftPanel.getWidth();
            int panelH = leftPanel.getHeight();
            int innerW = panelW - leftPanel.getPaddingStart() - leftPanel.getPaddingEnd();
            int innerH = panelH - leftPanel.getPaddingTop() - leftPanel.getPaddingBottom();
            int size = Math.min(innerW, innerH);
            android.util.Log.i("ArtSize", "density=" + density + " panel=" + panelW + "x" + panelH
                    + " inner=" + innerW + "x" + innerH + " art=" + albumArt.getWidth()
                    + "x" + albumArt.getHeight() + " -> " + size
                    + " refPx=" + Math.round(com.airmusic.player.view.BoxAspectFrameLayout.REF_WIDTH_DP * density)
                    + "x" + Math.round(com.airmusic.player.view.BoxAspectFrameLayout.REF_HEIGHT_DP * density));
            if (size > 0 && albumArt.getWidth() != size) {
                ViewGroup.LayoutParams lp = albumArt.getLayoutParams();
                lp.width = size;
                lp.height = size;
                albumArt.setLayoutParams(lp);
            }
        });
        albumArt.post(() -> {
            // 布局固定按盒子的参考尺寸排版（877.7 x 493.7dp）再整体缩放，所以封面
            // 也必须用同一个基准算：以前按「设备自己的 dp 高度」算，手机上（640x360dp）
            // 会算出很小的封面，再被整体缩放，看起来就是"组件大小位置都不对"。
            float density = getResources().getDisplayMetrics().density;
            View bottomBarView = findViewById(R.id.bottom_bar);
            int barDp = bottomBarView == null ? 62
                    : Math.round(bottomBarView.getHeight() / density);
            float refHeightDp = com.airmusic.player.view.BoxAspectFrameLayout.REF_HEIGHT_DP;
            float refWidthDp = com.airmusic.player.view.BoxAspectFrameLayout.REF_WIDTH_DP;
            // 高度方向：参考高度 − 底栏 − 边距；宽度方向：参考宽度的 43.5%
            //（与盒子上左侧面板实际占到的比例一致），取小。这样盒子上算出来仍是
            // ≈382dp（和改动前一致），其他设备也是同一比例。
            int sizeDp = (int) Math.min(refHeightDp - barDp - 36,
                    Math.round(refWidthDp * 0.435f));
            if (sizeDp > 0) {
                // 左侧面板宽度必须显式定死：它本身是 wrap_content（由封面撑开），
                // 而横向 LinearLayout 又会按权重挤压它，形成「封面撑面板、面板又限制
                // 封面」的循环约束——结果封面被压到可用宽度（小米 6 上就只有 23% 屏宽）。
                ViewGroup.LayoutParams panelParams = leftPanel.getLayoutParams();
                panelParams.width = Math.round(sizeDp * density)
                        + leftPanel.getPaddingStart() + leftPanel.getPaddingEnd();
                leftPanel.setLayoutParams(panelParams);
            }
            int size = Math.max(0, Math.round(sizeDp * density));
            if (size <= 0) {
                size = leftPanel.getHeight() - leftPanel.getPaddingTop() - leftPanel.getPaddingBottom();
            }
            if (size > 0) {
                ViewGroup.LayoutParams lp = albumArt.getLayoutParams();
                lp.width = size;
                lp.height = size;
                albumArt.setLayoutParams(lp);
                android.util.Log.i("ArtSize", "density=" + density + " refW=" + refWidthDp
                        + " refH=" + refHeightDp + " barDp=" + barDp + " sizeDp=" + sizeDp
                        + " sizePx=" + size
                        + " panelW=" + leftPanel.getWidth() + " panelH=" + leftPanel.getHeight()
                        + " artW=" + albumArt.getWidth() + " artH=" + albumArt.getHeight());
            }
            compactForSmallScreens();
        });

        findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        albumArt.setOnClickListener(v -> {
            if (sawScreensaverSwipe) return;   // 刚才是上滑进屏保，别顺手又进歌词页
            startActivity(new Intent(this, LyricsActivity.class));
        });
        findViewById(R.id.btn_library).setOnClickListener(v ->
                startActivity(new Intent(this, LibraryActivity.class)));
        findViewById(R.id.btn_apps).setOnClickListener(v ->
                startActivity(new Intent(this, AppsActivity.class)));
        findViewById(R.id.btn_multicast).setOnClickListener(v -> {
            if (lastState != null && lastState.source == PlayerUiState.Source.REMOTE) {
                // Receiver UI: ask the master to disconnect this device.
                PlaybackService service = PlaybackService.getInstance();
                if (service != null) {
                    // Show a loading indicator while the fade-out + network
                    // disconnect runs, so the tap does not look stuck.
                    multicastProgress.setVisibility(View.VISIBLE);
                    btnMulticast.setVisibility(View.INVISIBLE);
                    volumePollHandler.removeCallbacks(restoreMulticastUi);
                    volumePollHandler.postDelayed(restoreMulticastUi, 8000);
                    service.disconnectFromMaster();
                }
            } else {
                openMultiRoomDialog();
            }
        });
        findViewById(R.id.btn_prev).setOnClickListener(v -> {
            PlaybackService service = PlaybackService.getInstance();
            if (service != null) {
                service.addControlAckListener(controlAckListener);
                if (isReceiverMode()) showControlSpinner(btnPrev, prevProgress);
                service.previous();
            }
        });
        findViewById(R.id.btn_next).setOnClickListener(v -> {
            PlaybackService service = PlaybackService.getInstance();
            if (service != null) {
                service.addControlAckListener(controlAckListener);
                if (isReceiverMode()) showControlSpinner(btnNext, nextProgress);
                service.next();
            }
        });
        btnPlay.setOnClickListener(v -> {
            PlaybackService service = PlaybackService.getInstance();
            if (service != null) {
                service.addControlAckListener(controlAckListener);
                if (isReceiverMode()) showControlSpinner(btnPlay, playProgress);
                service.togglePlay();
            }
        });
        // 拖动气泡里显示播放时间（滑条内部是 0~1000 的千分比，要换算成 mm:ss）。
        seekBar.setLabelFormatter(value -> {
            long duration = lastState == null ? 0 : lastState.durationMs;
            if (duration <= 0) return "--:--";
            return formatTime(Math.round((double) value * duration / 1000d));
        });
        seekBar.addOnChangeListener((slider, value, fromUser) -> {
            if (seekTrack != null) seekTrack.setFraction(value / 1000f);
            if (fromUser && lastState != null && lastState.durationMs > 0) {
                positionText.setText(formatTime((long) value * lastState.durationMs / 1000));
            }
        });
        seekBar.addOnSliderTouchListener(
                new com.google.android.material.slider.Slider.OnSliderTouchListener() {
                    @Override
                    public void onStartTrackingTouch(
                            com.google.android.material.slider.Slider slider) {
                        seeking = true;
                    }

                    @Override
                    public void onStopTrackingTouch(
                            com.google.android.material.slider.Slider slider) {
                        seeking = false;
                        PlaybackService service = PlaybackService.getInstance();
                        if (service != null && lastState != null && lastState.durationMs > 0) {
                            service.seekTo((int) ((int) slider.getValue() * lastState.durationMs / 1000));
                        }
                    }
                });

        PlaybackService.start(this);
        PlaybackService service = PlaybackService.getInstance();
        if (service != null) {
            service.addControlAckListener(controlAckListener);
        }
        requestPermissionsIfNeeded();
    }

    /** Compacts the right panel on very short screens (e.g. 4:3 mini boxes)
     *  so the transport controls and the progress bar stay visible. Screens
     *  as tall as the reference TV box keep the original layout untouched. */
    private void compactForSmallScreens() {
        View rightPanel = findViewById(R.id.right_panel);
        if (rightPanel == null) return;
        float d = getResources().getDisplayMetrics().density;
        int heightDp = Math.round(rightPanel.getHeight() / d);
        if (heightDp >= 360) return; // box-sized UI unchanged
        boolean tiny = heightDp < 250; // very short screens (PA03 class)
        rightPanel.setPadding(Math.round(36 * d),
                Math.round((tiny ? 18 : 32) * d),
                Math.round(24 * d), 0);
        trackTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, tiny ? 10 : 15);
        trackTitle.setMaxLines(1);
        trackArtist.setTextSize(TypedValue.COMPLEX_UNIT_SP, tiny ? 9 : 12);
        trackAlbum.setTextSize(TypedValue.COMPLEX_UNIT_SP, tiny ? 8 : 11);
        int badgePad = Math.round((tiny ? 6 : 10) * d);
        int badgePadV = Math.round((tiny ? 0 : 2) * d);
        sourceBadge.setPadding(badgePad, badgePadV, badgePad, badgePadV);
        if (tiny) {
            ViewGroup.MarginLayoutParams mlp =
                    (ViewGroup.MarginLayoutParams) sourceBadge.getLayoutParams();
            mlp.topMargin = Math.round(2 * d);
            sourceBadge.setLayoutParams(mlp);
        }
        seekBar.getLayoutParams().height =
                Math.round((tiny ? 18 : 34) * d);
        // 粗轨道 / 大手柄在矮屏上要按比例收回来，否则会被裁掉。
        int seekTrack = Math.round((tiny ? 12 : 18) * d);
        int seekThumb = Math.round((tiny ? 20 : 28) * d);
        seekBar.setTrackHeight(seekTrack);
        seekBar.setThumbHeight(seekThumb);
        seekBar.setThumbWidth(Math.round((tiny ? 6 : 7) * d));
        seekBar.setThumbTrackGapSize(Math.round((tiny ? 5 : 6) * d));
        seekBar.setTrackStopIndicatorSize(seekTrack);
        if (this.seekTrack != null) {
            // 自绘的进度条跟着一起收（它是独立一层，高度要够放手柄）
            this.seekTrack.getLayoutParams().height = seekThumb;
            this.seekTrack.requestLayout();
            this.seekTrack.setThumb(SEEK_THUMB_WIDTH_DP, tiny ? 20f : 28f, tiny ? 5f : 6f);
            this.seekTrack.setTrackHeightDp(tiny ? 12f : 18f);
        }
        layoutSliderTracks();
        layoutSliderTracks();
        if (tiny) {
            positionText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
            durationText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        }
        int play = Math.round((tiny ? 36 : 50) * d);
        int side = Math.round((tiny ? 30 : 40) * d);
        btnPlay.getLayoutParams().width = play;
        btnPlay.getLayoutParams().height = play;
        btnPlay.setPadding(Math.round(8 * d), Math.round(8 * d),
                Math.round(8 * d), Math.round(8 * d));
        btnPrev.getLayoutParams().width = side;
        btnPrev.getLayoutParams().height = side;
        btnNext.getLayoutParams().width = side;
        btnNext.getLayoutParams().height = side;
        if (tiny) {
            View playWrap = (View) btnPlay.getParent();
            if (playWrap != null) {
                ViewGroup.MarginLayoutParams mlp =
                        (ViewGroup.MarginLayoutParams) playWrap.getLayoutParams();
                mlp.setMargins(Math.round(8 * d), 0, Math.round(8 * d), 0);
                playWrap.setLayoutParams(mlp);
            }
        }
    }

    /** True while this device is receiving a multi-room stream. */
    private boolean isReceiverMode() {
        return lastState != null
                && lastState.source == PlayerUiState.Source.REMOTE;
    }

    /** Shows a loading spinner on a transport button while the receiver's
     *  command is waiting for the master to execute and broadcast back. */
    private void showControlSpinner(View icon, com.airmusic.player.ui.BukaSpinner spinner) {
        controlPending = true;
        if (icon == btnPlay) {
            // Keep the circular sky-blue frame visible; hide only the glyph.
            btnPlay.setImageResource(android.R.color.transparent);
            playGlyphVisible = false;
        } else {
            icon.setVisibility(View.INVISIBLE);
        }
        spinner.setVisibility(View.VISIBLE);
        volumePollHandler.removeCallbacks(controlTimeoutRunnable);
        volumePollHandler.postDelayed(controlTimeoutRunnable, 4000);
    }

    private void hideControlSpinners() {
        if (!controlPending) return;
        controlPending = false;
        volumePollHandler.removeCallbacks(controlTimeoutRunnable);
        if (playProgress != null) playProgress.setVisibility(View.GONE);
        if (nextProgress != null) nextProgress.setVisibility(View.GONE);
        if (prevProgress != null) prevProgress.setVisibility(View.GONE);
        if (btnPlay != null) {
            btnPlay.setVisibility(View.VISIBLE);
            if (lastState != null) {
                updatePlayIcon(lastState.playing, true);
            }
        }
        if (btnNext != null) btnNext.setVisibility(View.VISIBLE);
        if (btnPrev != null) btnPrev.setVisibility(View.VISIBLE);
    }

    /**
     * Shows the play/pause glyph, morphing between the two icon shapes when
     * the transport state changes (morphicons-style animation).
     */
    private void updatePlayIcon(boolean playing, boolean animate) {
        boolean changed = playGlyphPlaying == null || playGlyphPlaying != playing;
        if (playGlyphPlaying == null) {
            btnPlay.setImageResource(playing ? R.drawable.vd_pause_morph : R.drawable.vd_play_morph);
            playGlyphPlaying = playing;
            playGlyphVisible = true;
            return;
        }
        if (!changed) {
            if (!playGlyphVisible) {
                btnPlay.setImageResource(playing ? R.drawable.vd_pause_morph : R.drawable.vd_play_morph);
                playGlyphVisible = true;
            }
            return;
        }
        if (!animate) {
            btnPlay.setImageResource(playing ? R.drawable.vd_pause_morph : R.drawable.vd_play_morph);
            playGlyphPlaying = playing;
            playGlyphVisible = true;
            return;
        }
        int morphRes = playing ? R.drawable.avd_play_to_pause : R.drawable.avd_pause_to_play;
        // 关键：只把 morph 的 drawable 挂上去，**不要再显式 start()**。
        //
        // AnimatedVectorDrawable 只有在"可见"时才跑动画，ImageView 挂上新 drawable
        // 时自己就会 setVisible(...) 把它启动起来；再补一次 start() 等于把刚起步的
        // 动画重启一遍，而重启那一帧会按里面残留的几何（上一次变形结束时的目标形状）
        // 画出来——实测就是「点下去先闪成目标形状 → 回到起始形状 → 再正常变形」。
        // mutate() 再给这份 drawable 一份自己的矢量状态，避免和别的实例共享。
        btnPlay.setImageResource(morphRes);
        Drawable attached = btnPlay.getDrawable();
        if (attached != null) attached.mutate();
        if (attached instanceof AnimatedVectorDrawable) {
            attached.setVisible(true, false);   // 触发/继续动画，但不重启
        }
        playGlyphPlaying = playing;
        playGlyphVisible = true;
    }

    /** Binds the bottom-bar slider to the system media volume. */
    private void setupVolumeSlider() {
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager == null || volumeSeek == null) return;

        volumeSeek.setValueTo(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        volumeSeek.setValue(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
        updateVolumeIcon(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
        // 气泡里显示音量百分比（系统音量档数各机型不同，换算成百分比更直观）。
        volumeSeek.setLabelFormatter(value -> {
            float max = volumeSeek.getValueTo();
            int percent = max <= 0f ? 0 : Math.round(value * 100f / max);
            return percent + "%";
        });
        volumeSeek.addOnChangeListener((slider, value, fromUser) -> {
            int volume = Math.round(value);
            if (fromUser) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
            }
            if (volumeTrack != null) {
                float max = volumeSeek.getValueTo();
                volumeTrack.setFraction(max <= 0f ? 0f : value / max);
            }
            updateVolumeIcon(volume);
        });

        // Keep the slider in sync when the volume is changed elsewhere
        // (e.g. the device volume keys).
        volumeObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (Math.round(volumeSeek.getValue()) != current) {
                    volumeSeek.setValue(current);
                }
                updateVolumeIcon(current);
            }
        };
        getContentResolver().registerContentObserver(
                Settings.System.getUriFor("volume_music"), false, volumeObserver);
        if (volumeTrack != null) {
            float max = volumeSeek.getValueTo();
            volumeTrack.setFraction(max <= 0f ? 0f
                    : audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / max);
        }
    }

    private void syncVolumeSlider() {
        if (audioManager != null && volumeSeek != null) {
            int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (Math.round(volumeSeek.getValue()) != current) {
                volumeSeek.setValue(current);
            }
            updateVolumeIcon(current);
        }
    }

    /**
     * 音量条几何：
     * <ul>
     *   <li>自绘的整根条铺满音量区，**整条（含音量图标那一段）都能触摸**；</li>
     *   <li>Material 的滑条用负外边距撑到同样宽，让它的手柄行程和自绘条完全重合——</li>
     *   <li>Material 自己的手柄会藏起来（自绘条里画了手柄），这样轨道/手柄/图标永远同心。</li>
     * </ul>
     */
    private void layoutSliderTracks() {
        if (volumeSeek == null) return;
        float d = getResources().getDisplayMetrics().density;
        int iconMargin = Math.round(14 * d);
        if (volumeIcon != null && volumeIcon.getLayoutParams()
                instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) volumeIcon.getLayoutParams();
            if (lp.getMarginStart() != iconMargin) {
                lp.setMarginStart(iconMargin);
                volumeIcon.setLayoutParams(lp);
            }
        }
        // 自绘条左右各留半个手柄，Material 的轨道要正好落在这段上：
        // Slider 的轨道起点 = 滑条左端 + trackSidePadding，所以用负外边距抵消。
        int thumbHalf = Math.round(VOLUME_THUMB_WIDTH_DP * d / 2f);
        int pad = trackSidePad(volumeSeek);
        // 自绘条的手柄行程在条内各让出「半个手柄 + 一个断口」，Material 的轨道同步内缩
        int margin = -(pad - thumbHalf * 2 - Math.round(VOLUME_THUMB_GAP_DP * d));
        setSliderMargins(volumeSeek, margin, margin);
        // Material 的轨道是按它自己的 widgetHeight 排的，比整条居中位置低几像素，
        // 用它拖动气泡的锚点会跟着偏，所以把滑条整体下移对齐（触摸坐标会一起变换）。
        View bar = findViewById(R.id.volume_bar);
        if (bar != null && bar.getHeight() > 0 && volumeSeek.getHeight() > 0) {
            volumeSeek.setTranslationY((bar.getHeight() - volumeSeek.getHeight()) / 2f);
        }
        if (volumeTrack != null) {
            volumeTrack.setThumb(VOLUME_THUMB_WIDTH_DP, VOLUME_THUMB_HEIGHT_DP,
                    VOLUME_THUMB_GAP_DP);
            volumeTrack.setTrackHeightDp(VOLUME_TRACK_HEIGHT_DP);
            // Material 的手柄尺寸要和自绘手柄一致，拖动气泡才会正好落在竖条正上方
            volumeSeek.setThumbWidth(Math.round(VOLUME_THUMB_WIDTH_DP * d));
            volumeSeek.setThumbHeight(Math.round(VOLUME_THUMB_HEIGHT_DP * d));
            // 图标交给自绘层代画：这样它会被手柄两侧的切口一起切掉，
            // 不会浮在断口上面。原 ImageView 留在原位（只负责占位与量尺寸）。
            volumeTrack.setIconSource(volumeIcon);
            if (volumeIcon != null && volumeIcon.getVisibility() != View.INVISIBLE) {
                volumeIcon.setVisibility(View.INVISIBLE);
            }
        }
        if (seekTrack != null) {
            seekTrack.setThumb(SEEK_THUMB_WIDTH_DP, SEEK_THUMB_HEIGHT_DP, SEEK_THUMB_GAP_DP);
            seekTrack.setTrackHeightDp(SEEK_TRACK_HEIGHT_DP);
        }
        if (seekBar != null) {
            // 进度条同理：让 Material 的轨道行程落在自绘轨道那一段上
            int seekThumbHalf = Math.round(SEEK_THUMB_WIDTH_DP * d / 2f);
            int seekMargin = -(trackSidePad(seekBar) - seekThumbHalf * 2
                    - Math.round(SEEK_THUMB_GAP_DP * d));
            setSliderMargins(seekBar, seekMargin, seekMargin);
        }
    }

    /** 改滑条左右外边距（音量条用负值把 Material 的轨道撑到整条宽）。 */
    private void setSliderMargins(View slider, int start, int end) {
        if (slider == null) return;
        ViewGroup.LayoutParams raw = slider.getLayoutParams();
        if (!(raw instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) raw;
        if (mlp.getMarginStart() == start && mlp.getMarginEnd() == end) return;
        mlp.setMarginStart(start);
        mlp.setMarginEnd(end);
        slider.setLayoutParams(mlp);
    }

    /**
     * 轨道在滑条内部的左右留白。Material 会按手柄 / 气泡宽度自己算，
     * {@code getTrackSidePadding()} 并不总等于实际留白，能用实测宽度差就用它。
     */
    private int trackSidePad(View slider) {
        if (slider == null || !(slider instanceof com.google.android.material.slider.Slider)) {
            return 0;
        }
        com.google.android.material.slider.Slider s =
                (com.google.android.material.slider.Slider) slider;
        int trackWidth = s.getTrackWidth();
        if (trackWidth > 0 && s.getWidth() > trackWidth) {
            return (s.getWidth() - trackWidth) / 2;
        }
        return s.getTrackSidePadding();
    }

    /**
     * 音量图标随音量换档：静音 / 低 / 中 / 高（0、1~33%、34~66%、67~100%），
     * 换档时弹一下，拖音量条能直接看出档位在动。
     */
    private void updateVolumeIcon(int volume) {
        if (volumeIcon == null) return;
        int max = audioManager == null
                ? 0 : audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int level = 0;
        if (volume > 0 && max > 0) {
            int percent = Math.round(volume * 100f / max);
            level = percent <= 33 ? 1 : (percent <= 66 ? 2 : 3);
        }
        if (level != volumeIconLevel) {
            volumeIconLevel = level;
            volumeIcon.setImageResource(VOLUME_ICONS[level]);
            // 弹一下的动效交给自绘层做（做在 ImageView 上会被缓存成缩小帧卡住）
            if (volumeTrack != null) volumeTrack.popIcon();
        }
        // 图标周围被深色段盖住时改用同色系近白，避免看不清；
        // 否则用已播放段的深色，压在浅色轨道上。
        boolean overFill = volumeTrack != null && volumeTrack.covers(
                volumeIcon.getLeft() + volumeIcon.getWidth() / 2f);
        volumeIcon.setImageTintList(android.content.res.ColorStateList.valueOf(
                overFill ? ColorTheme.tooltipText() : ColorTheme.sliderActive()));
        // 图标是自绘层代画的，换档 / 换色后让它重画一次
        if (volumeTrack != null) volumeTrack.invalidate();
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            boolean audio = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            boolean notif = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
            if (!audio || !notif) {
                permissionLauncher.launch(new String[]{
                        Manifest.permission.READ_MEDIA_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS});
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            // Android 10 及以下还要写权限：上传音乐、删除文件都得写，
            // 而且没有它系统给的是只读挂载视图。
            boolean read = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
            boolean write = Build.VERSION.SDK_INT > 29
                    || ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
            if (!read || !write) {
                String[] wanted = Build.VERSION.SDK_INT > 29
                        ? new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}
                        : new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE};
                permissionLauncher.launch(wanted);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        StateBus.get().addListener(stateListener);
        btnApps.setVisibility(new Prefs(this).isShowAppsButton()
                ? View.VISIBLE : View.GONE);
        syncVolumeSlider();
        volumePollHandler.removeCallbacks(volumePoll);
        volumePollHandler.postDelayed(volumePoll, 400);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleCaptureIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_SHOW_TOUR, false)) {
            intent.removeExtra(EXTRA_SHOW_TOUR);
            findViewById(android.R.id.content).post(() -> OnboardingOverlay.show(this));
        }
    }

    /** Debug-only PCM capture toggle driven from adb intents. */
    private void handleCaptureIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (ACTION_CAPTURE_START.equals(action)) {
            File dir = getExternalFilesDir(null);
            if (dir != null) {
                EqAudioProcessor.startCapture(new File(dir, "eq_capture.pcm"));
                AudioOutputQueue.startCapture(new File(dir, "eq_capture_airplay.pcm"));
                MultiRoomAudioPlayer.startCapture(new File(dir, "mr_capture.pcm"));
            }
        } else if (ACTION_CAPTURE_STOP.equals(action)) {
            EqAudioProcessor.stopCapture();
            AudioOutputQueue.stopCapture();
            MultiRoomAudioPlayer.stopCapture();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        StateBus.get().removeListener(stateListener);
        volumePollHandler.removeCallbacks(volumePoll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        volumePollHandler.removeCallbacks(volumePoll);
        volumePollHandler.removeCallbacks(controlTimeoutRunnable);
        PlaybackService service = PlaybackService.getInstance();
        if (service != null) {
            service.removeControlAckListener(controlAckListener);
        }
        if (volumeObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(volumeObserver);
            } catch (Exception ignored) {
            }
            volumeObserver = null;
        }
    }

    private void openMultiRoomDialog() {
        PlaybackService service = PlaybackService.getInstance();
        if (service == null) return;
        MultiRoomManager mgr = service.getMultiRoomManager();
        if (mgr == null) return;
        // Show a spinner while the network scan runs so the delay doesn't
        // feel like a freeze.
        BukaDialog progress = BukaDialog.loading(this, getString(R.string.multicast_scanning));
        progress.show();
        final Handler scanHandler = new Handler(Looper.getMainLooper());
        final Runnable showDialog = () -> {
            if (progress.isShowing()) progress.dismiss();
            showMultiRoomDialog(mgr);
        };
        progress.setOnCancelListener(d -> scanHandler.removeCallbacks(showDialog));
        mgr.rescanDevices();
        scanHandler.postDelayed(showDialog, 2500);
    }

    private void showMultiRoomDialog(MultiRoomManager mgr) {
        List<MultiRoomDiscovery.DeviceInfo> devices = new ArrayList<>();
        for (MultiRoomDiscovery.DeviceInfo d : mgr.getDevices()) {
            devices.add(d);
        }
        if (devices.isEmpty()) {
            BukaDialog.message(this, getString(R.string.multicast_title),
                    getString(R.string.multicast_empty)).show();
            return;
        }
        String[] names = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) names[i] = devices.get(i).name;
        boolean[] checked = new boolean[devices.size()];
        List<MultiRoomDiscovery.DeviceInfo> selected = new ArrayList<>();
        // Remember which receivers are already connected so the checkboxes
        // keep their state between dialog opens.
        for (int i = 0; i < devices.size(); i++) {
            if (mgr.isTargetConnected(devices.get(i).name)) {
                checked[i] = true;
                selected.add(devices.get(i));
            }
        }
        BukaDialog.multiChoice(this, getString(R.string.multicast_title), names, checked,
                getString(R.string.multicast_confirm), state -> {
                    List<MultiRoomDiscovery.DeviceInfo> picked = new ArrayList<>();
                    for (int i = 0; i < devices.size() && i < state.length; i++) {
                        if (state[i]) picked.add(devices.get(i));
                    }
                    mgr.updateTargets(picked);
                }).show();
    }


    private void render(PlayerUiState s) {
        // 临时诊断：量化每 500ms 状态推送里三件「重活」各自的开销。
        final long tBlurStart = android.os.SystemClock.uptimeMillis();
        BlurBackground.apply(this, R.drawable.bg_main_gradient);
        final long tBlurEnd = android.os.SystemClock.uptimeMillis();

        // Leaving receiver mode (disconnect / AirPlay takes over) should
        // never leave a transport loading spinner stuck on screen.
        if (s.source != PlayerUiState.Source.REMOTE) {
            hideControlSpinners();
        }

        trackTitle.setText(s.title);
        trackArtist.setText(s.artist);
        trackAlbum.setText(s.album);
        // 点歌手 / 专辑：跳到曲库对应分组（AirPlay / 多房间接收时屏幕上不是本地
        // 曲库里的信息，就不跳）
        boolean localInfo = s.source == PlayerUiState.Source.LOCAL
                || s.source == PlayerUiState.Source.IDLE;
        trackArtist.setClickable(localInfo);
        trackAlbum.setClickable(localInfo);

        Bitmap art = s.art;
        // 动态配色：从当前封面取主色（降饱和）后给按钮 / 滑块上色。
        ColorTheme.update(art);
        final long tPaletteEnd = android.os.SystemClock.uptimeMillis();
        BukaTheme.tintButtons(this);
        final long tTintEnd = android.os.SystemClock.uptimeMillis();
        long total = tTintEnd - tBlurStart;
        if (total > 80) {
            Log.i(TAG, "render cost blur=" + (tBlurEnd - tBlurStart)
                    + " palette=" + (tPaletteEnd - tBlurEnd)
                    + " tint=" + (tTintEnd - tPaletteEnd) + " total=" + total
                    + "ms art=" + (art == null ? "null"
                            : art.getWidth() + "x" + art.getHeight()));
        }
        if (s.source == PlayerUiState.Source.AIRPLAY || s.source == PlayerUiState.Source.REMOTE) {
            if (art != null) {
                albumArt.setImageBitmap(art);
            } else {
                albumArt.setImageResource(R.drawable.ic_airplay);
            }
        } else if (s.source == PlayerUiState.Source.IDLE) {
            albumArt.setImageResource(R.drawable.ic_airplay);
        } else {
            // 本地播放也统一用专属占位封面：没有内嵌封面的歌不该变成小音符图标。
            albumArt.setImageResource(R.drawable.ic_airplay);
            if (art != null) {
                albumArt.setImageBitmap(art);
            }
    }

        if (s.source == PlayerUiState.Source.AIRPLAY) {
            sourceBadge.setText(getString(R.string.source_airplay) + " · " + s.clientName);
        } else if (s.source == PlayerUiState.Source.REMOTE) {
            sourceBadge.setText(R.string.source_remote);
        } else if (s.source == PlayerUiState.Source.LOCAL) {
            sourceBadge.setText(R.string.source_local);
        } else {
            sourceBadge.setText(R.string.source_idle);
        }

        if (!(controlPending && playProgress.getVisibility() == View.VISIBLE)) {
            updatePlayIcon(s.playing, true);
        }
        btnPlay.setContentDescription(getString(s.playing ? R.string.pause : R.string.play));

        if (s.source == PlayerUiState.Source.AIRPLAY) {
            // Multi-room is meaningless while receiving AirPlay.
            multicastProgress.setVisibility(View.GONE);
            volumePollHandler.removeCallbacks(restoreMulticastUi);
            btnMulticast.setVisibility(View.GONE);
            btnLibrary.setVisibility(View.GONE);
        } else if (s.source == PlayerUiState.Source.REMOTE) {
            // The library belongs to this device's own collection, not the
            // stream being received from the master.
            btnLibrary.setVisibility(View.GONE);
            // While a disconnect is fading out, incoming clock refreshes
            // arrive every ~500 ms; don't re-show the button on top of the
            // loading spinner (they would overlap).
            if (multicastProgress.getVisibility() != View.VISIBLE) {
                btnMulticast.setVisibility(View.VISIBLE);
                btnMulticast.setImageResource(R.drawable.ic_disconnect);
                btnMulticast.setContentDescription(getString(R.string.multicast_disconnect));
            }
        } else {
            btnLibrary.setVisibility(View.VISIBLE);
            multicastProgress.setVisibility(View.GONE);
            volumePollHandler.removeCallbacks(restoreMulticastUi);
            btnMulticast.setVisibility(View.VISIBLE);
            // 多房间按钮也用新的线性图标 + 动效（render 每次刷新都要保持它）
            BukaIcons.view(btnMulticast, R.drawable.ic_anim_nav_cast, false);
            btnMulticast.setContentDescription(getString(R.string.multicast));
        }

        boolean showSeek = (s.source == PlayerUiState.Source.LOCAL
                || s.source == PlayerUiState.Source.REMOTE) && s.durationMs > 0;
        seekRow.setVisibility(showSeek ? View.VISIBLE : View.GONE);
        if (showSeek) {
            if (!seeking) {
                int progress = s.durationMs > 0 ? (int) ((long) s.positionMs * 1000 / s.durationMs) : 0;
                seekBar.setValue(Math.min(1000, Math.max(0, progress)));
                positionText.setText(formatTime(s.positionMs));
            }
            durationText.setText(formatTime(s.durationMs));
        }

    }

    private String formatTime(long ms) {
        long totalSec = ms / 1000;
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }

    /** 点歌手 / 专辑 -> 打开曲库并展开对应的分组。 */
    private void openLibraryFor(TrackAdapter.GroupBy mode, CharSequence name) {
        if (name == null) return;
        String key = name.toString().trim();
        if (key.isEmpty()
                || key.equals(getString(R.string.unknown_artist))
                || key.equals(getString(R.string.unknown_album))
                || key.equals(getString(R.string.source_idle))) {
            return;
        }
        Intent intent = new Intent(this, LibraryActivity.class);
        intent.putExtra(LibraryActivity.EXTRA_GROUP_BY, mode.name());
        intent.putExtra(LibraryActivity.EXTRA_GROUP_KEY, key);
        startActivity(intent);
    }
}
