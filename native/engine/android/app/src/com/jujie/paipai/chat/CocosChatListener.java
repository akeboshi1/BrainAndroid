package com.jujie.paipai.chat;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.cocos.lib.JsbBridge;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 面向 Cocos Creator 的桥接监听器：
 * - 实现 VoiceChatClient.Listener
 * - 尝试通过反射调用 com.cocos.lib.JsbBridge#sendToScript(String, String)
 * - 优先在 Cocos 游戏线程执行（若可通过反射获取 com.cocos.lib.CocosHelper#runOnGameThread），不可用则回落主线程
 *
 * 主要修复点：避免短时间内重复发送相同事件导致双发（日志中出现两遍 sendToCocos）。
 */
public final class CocosChatListener implements VoiceChatClient.Listener {

    private static final String TAG = "CocosChatListener";

    private final Handler main = new Handler(Looper.getMainLooper());

    // 仅缓存 Method 对象，避免未集成 cocos 时编译期依赖
    private static Method sSendToScript;
    private static Method sRunOnGameThread;

    // 去重缓存：跨实例/跨线程安全的最近发送键缓存（key -> 上次发送时间 millis）
    // 防止短时间内同一事件被重复发送（例如来自多条路径的重复回调）
    private static final ConcurrentHashMap<String, Long> sRecentSends = new ConcurrentHashMap<>();
    private static final long RECENT_WINDOW_MS = TimeUnit.SECONDS.toMillis(1); // 1s 窗口

    // 额外短时去重（同一实例内更严格的短时间去重）
    private static String sLastEvent;
    private static String sLastJson;
    private static long sLastTsMillis;
    private static final long DUPLICATE_WINDOW_MS = 300; // 300ms

    static {
        // 尝试反射查找 JsbBridge.sendToScript 和 CocosHelper.runOnGameThread
        try {
            Class<?> jb = Class.forName("com.cocos.lib.JsbBridge");
            sSendToScript = jb.getMethod("sendToScript", String.class, String.class);
        } catch (Throwable ignored) {
            sSendToScript = null;
        }
        try {
            Class<?> ch = Class.forName("com.cocos.lib.CocosHelper");
            sRunOnGameThread = ch.getMethod("runOnGameThread", Runnable.class);
        } catch (Throwable ignored) {
            sRunOnGameThread = null;
        }
    }

    private void runOnGameThread(@NonNull Runnable task) {
        try {
            if (sRunOnGameThread != null) {
                sRunOnGameThread.invoke(null, task);
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "runOnGameThread reflect failed, fallback to main: " + t.getMessage());
        }
        main.post(task);
    }

    private void sendToCocos(@NonNull String event, @NonNull JSONObject payload) {
        final String json = payload.toString();
        final String key = event + '|' + json;

        long now = System.currentTimeMillis();
        // 跨实例/线程去重
        Long prev = sRecentSends.get(key);
        if (prev != null && (now - prev) < RECENT_WINDOW_MS) {
            Log.d(TAG, "suppress duplicate recent sendToCocos " + event + " " + json);
            return;
        }
        sRecentSends.put(key, now);

        // 同一实例内更严格的短时去重
        synchronized (CocosChatListener.class) {
            if (event.equals(sLastEvent) && json.equals(sLastJson) && (now - sLastTsMillis) < DUPLICATE_WINDOW_MS) {
                Log.d(TAG, "suppress duplicate sendToCocos (short) " + event + " " + json);
                return;
            }
            sLastEvent = event;
            sLastJson = json;
            sLastTsMillis = now;
        }

        runOnGameThread(() -> {
            try {
                // 日志仅在真正进入发送流程时打印
                Log.d(TAG, "sendToCocos " + event + " " + json);

                // 优先使用反射调用（若成功），否则回退到直接调用
                if (sSendToScript != null) {
                    try {
                        sSendToScript.invoke(null, event, json);
                        return;
                    } catch (Throwable t) {
                        Log.w(TAG, "reflect sendToScript failed, fallback: " + t.getMessage());
                        // fallthrough
                    }
                }

                // 直接调用作为最后回退
                JsbBridge.sendToScript(event, json);
            } catch (Throwable t) {
                Log.e(TAG, "sendToCocos error: " + t.getMessage());
            }
        });
    }

    private static JSONObject jText(@NonNull String text) {
        JSONObject o = new JSONObject();
        try { o.put("text", text); } catch (Throwable ignored) {}
        return o;
    }
    private static JSONObject jPair(@NonNull String k, @NonNull Object v) {
        JSONObject o = new JSONObject();
        try { o.put(k, v); } catch (Throwable ignored) {}
        return o;
    }

    @Override
    public void onReady() {
        JSONObject o = new JSONObject();
        try { o.put("ts", System.currentTimeMillis()); } catch (Throwable ignored) {}
        sendToCocos("CHAT:READY", o);
    }

    @Override
    public void onLog(@NonNull String line) {
        // sendToCocos("chat.log", jText(line));
    }

    @Override
    public void onUserTranscript(@NonNull String text) {
        sendToCocos("CHAT:USER", jText(text));
    }

    @Override
    public void onAssistantDelta(@NonNull String text) {
        if(text.startsWith("歌曲:")){
            sendToCocos("CHAT:ASSISTANT:FINAL", jText(text));
        }else{
            sendToCocos("CHAT:ASSISTANT:DELTA", jText(text));
        }
    }

    @Override
    public void onRecordingReady() {
        sendToCocos("CHAT:RECORDING:READY", new JSONObject());
    }

    @Override
    public void onRecordingStopped() {
        sendToCocos("CHAT:RECORDING:STOPPED", new JSONObject());
    }

    @Override
    public void onModeSwitched(@NonNull String mode, @NonNull JSONObject params) {
//        if(mode.equals("chat")){
//            sendToCocos("CHAT:MODE:", new JSONObject());
//        }
        sendToCocos("CHAT:MODE:SWITCHED", params);
    }

    public void onSongStart(int id, @NonNull String songName) {
        JSONObject o = new JSONObject();
        try {
            o.put("songName", songName);
            o.put("songId", id);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        sendToCocos("CHAT:SONG:STARTED", o);
    }

    @Override
    public void onSongStop() {
        sendToCocos("CHAT:SONG:PAUSED", new JSONObject());
    }

    public void onSongResume(){
        sendToCocos("CHAT:SONG:RESUMED", new JSONObject());
    }

    @Override
    public void onSongEnd(int id, @NonNull String name) {
        JSONObject o = new JSONObject();
        try {
            o.put("songName", name);
            o.put("songId", id);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        sendToCocos("CHAT:SONG:END", o);
    }

    @Override
    public void onAssistantFinal(@NonNull String text) {
        sendToCocos("CHAT:ASSISTANT:FINAL", jText(text));
    }

    @Override
    public void onFirstAudioLatency(long millis) {
        // sendToCocos("chat.latency", jPair("latencyMs", millis));
    }

    @Override
    public void onConnectionClosed() {
        sendToCocos("CHAT:STOPPED", jPair("reason", "normal"));
    }
}
