package com.airmusic.player;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

public class App extends Application {

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
}
