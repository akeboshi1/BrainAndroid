package com.cocos.game;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

/**
 * 轻量级网络状态监控工具：
 *  - 仅在「有任何可用网络」时触发一次 onAvailable 回调
 *  - 不做复杂的连通性判断（如是否能上网、能否访问指定域名）
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public final class ConnectivityMonitor {

    public interface Listener {
        /** 当系统报告“至少有一条网络可用”时触发 */
        void onNetworkAvailable();
    }

    private static final String TAG = "ConnectivityMonitor";
    private static ConnectivityManager.NetworkCallback networkCallback;

    /** 注册监听；多次调用只会保留第一次的 Listener */
    public static void register(@NonNull Context ctx, @NonNull Listener listener) {
        if (networkCallback != null) return;      // 已经注册过

        ConnectivityManager cm = (ConnectivityManager)
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE);

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                Log.d(TAG, "Network available: " + network);
                listener.onNetworkAvailable();
            }

            @Override
            public void onCapabilitiesChanged(@NonNull Network net,
                                              @NonNull NetworkCapabilities caps) {
                // 可在此判断 Wi-Fi/蜂窝、是否具备 NET_CAPABILITY_VALIDATED 等
            }

            @Override
            public void onLost(@NonNull Network network) {
                Log.d(TAG, "Network lost: " + network);
            }
        };

        NetworkRequest req = new NetworkRequest.Builder().build();
        cm.registerNetworkCallback(req, networkCallback);
        Log.d(TAG, "ConnectivityMonitor registered");
    }

    /** 反注册，避免内存泄漏；可在 Application.onTerminate() 或 Service.onDestroy() 调 */
    public static void unregister(@NonNull Context ctx) {
        if (networkCallback == null) return;
        ConnectivityManager cm = (ConnectivityManager)
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        cm.unregisterNetworkCallback(networkCallback);
        networkCallback = null;
        Log.d(TAG, "ConnectivityMonitor unregistered");
    }

    // 工具类禁止实例化
    private ConnectivityMonitor() {}
}
