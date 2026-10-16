package com.airmusic.player;

import android.app.Activity;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.util.Log;

public class App extends Application {

    private static final String TAG = "App";

    /** 判断"应用是否已经回到后台"的延迟：够看清是换页面 / 转屏还是真离开。 */
    private static final long BACKGROUND_STOP_DELAY_MS = 800L;

    /**
     * 当前处于 "已启动"（可见）状态的 Activity 数量。
     *
     * <p>用来判断应用是不是在前台。用 Activity 生命周期计数而不是
     * {@code ActivityManager.getRunningAppProcesses()}：后者在部分 ROM 上被裁剪，
     * 拿不到自己进程时就判断不了前台，"允许后台运行"关闭后那条被系统推迟的停止
     * 请求就会在用户回到应用时把服务停掉（播放中断、页面看着像卡死）。
     */
    private static final java.util.concurrent.atomic.AtomicInteger VISIBLE_ACTIVITIES =
            new java.util.concurrent.atomic.AtomicInteger();

    /** True when at least one activity of this app is started (visible). */
    public static boolean isAppVisible() {
        return VISIBLE_ACTIVITIES.get() > 0;
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(Activity activity) {
                VISIBLE_ACTIVITIES.incrementAndGet();
            }

            @Override
            public void onActivityStopped(Activity activity) {
                VISIBLE_ACTIVITIES.decrementAndGet();
                if (VISIBLE_ACTIVITIES.get() > 0) {
                    return;
                }
                // 转屏 / 主题切换导致的销毁不算离开应用。
                if (activity.isChangingConfigurations()) {
                    return;
                }
                MAIN.removeCallbacks(backgroundStopCheck);
                MAIN.postDelayed(backgroundStopCheck, BACKGROUND_STOP_DELAY_MS);
            }

            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            }

            @Override
            public void onActivityResumed(Activity activity) {
            }

            @Override
            public void onActivityPaused(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
            }
        });
    }

    /**
     * 「允许后台运行」关掉后，真正回到后台就停掉播放服务。
     *
     * <p>原来挂在 {@code MainActivity.onUserLeaveHint()} 上，但那个回调的行为和直觉相反：
     * 按 HOME 时系统是用带 {@code FLAG_ACTIVITY_NO_USER_ACTION} 的 Intent 启动桌面的，
     * 所以"回桌面"根本不触发它；反而在应用内部跳到别的页面（点底栏"曲库"/"设置"）会触发。
     * 结果就是开关关掉后：点进曲库 -> 服务被停掉 -> 点歌没反应、播放中断、AirPlay 也没了，
     * 而真正回到桌面时服务却照常常驻。
     *
     * <p>现在以"最后一个 Activity 也 stop 了"为准，再延迟确认一次（躲开转屏），并且熄屏
     * 不算离开——否则关掉开关后锁屏会把音乐停掉。
     */
    private final Runnable backgroundStopCheck = () -> {
        if (VISIBLE_ACTIVITIES.get() > 0) {
            return;
        }
        if (!isScreenInteractive()) {
            return;
        }
        if (new com.airmusic.player.util.Prefs(this).isBackgroundRun()) {
            return;
        }
        Log.i(TAG, "应用已回到后台，按设置停止播放服务");
        com.airmusic.player.service.PlaybackService.stop(this);
    };

    private boolean isScreenInteractive() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }
}
