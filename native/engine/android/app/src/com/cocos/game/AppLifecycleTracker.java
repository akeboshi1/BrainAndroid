package com.cocos.game;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;

public class AppLifecycleTracker implements Application.ActivityLifecycleCallbacks {

    private int started  = 0;
    private int resumed  = 0;
    private boolean inForeground = false;

    @Override public void onActivityCreated(@NonNull Activity a, Bundle b) {}
    @Override public void onActivityDestroyed(@NonNull Activity a) {}
    @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        if (++started == 1) {
            // 应用整体 onStart ——回到前台
            Log.d("AppLifecycleTracker", "Application started!");
            if (!LogcatCapture.isAlive())
                LogcatCapture.connect();
        }

    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        if (--started == 0) {
            // 应用整体 onStop ——进入后台
            // 如需省电，可选择关闭：
            // WebSocketManager.get().close();
        }
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        resumed++;
        if (!inForeground) inForeground = true;
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        if (--resumed == 0) {
            inForeground = false;
        }
    }
}
