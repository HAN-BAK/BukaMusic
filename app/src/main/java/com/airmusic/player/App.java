package com.airmusic.player;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

public class App extends Application {

    /**
     * 当前处于 "已启动"（可见）状态的 Activity 数量。
     *
     * <p>用来判断应用是不是在前台（服务里那条"迟到停止请求"的判断用它）。
     * 用 Activity 生命周期计数而不是 {@code ActivityManager.getRunningAppProcesses()}：
     * 后者在部分 ROM 上被裁剪，拿不到自己进程时就判断不了前台。
     */
    private static final java.util.concurrent.atomic.AtomicInteger VISIBLE_ACTIVITIES =
            new java.util.concurrent.atomic.AtomicInteger();

    /** True when at least one activity of this app is started (visible). */
    public static boolean isAppVisible() {
        return VISIBLE_ACTIVITIES.get() > 0;
    }

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

    // 「允许后台运行」的语义（用户 2026-10-17 明确）：**被从最近任务里划掉之后**才不再运行，
    // 进入后台（按 HOME / 切到别的应用 / 熄屏）并不停服务——它是背景音乐播放器，退到后台
    // 本来就该继续播放、继续接收 AirPlay。
    //
    // 所以这里不再监听"回到后台就停服务"，改由 PlaybackService.onTaskRemoved() 处理
    // "任务被移除"这一件事；开机自启的开关也仍然是这个 pref（见 BootReceiver）。
}
