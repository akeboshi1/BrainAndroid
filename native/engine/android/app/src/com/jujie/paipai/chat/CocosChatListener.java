package com.jujie.paipai.chat;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.cocos.lib.JsbBridge;

import org.json.JSONObject;

import java.lang.reflect.Method;

/**
 * 面向 Cocos Creator 3.8 的桥接监听器：
 * - 实现 VoiceChatClient.Listener
 * - 通过反射调用 com.cocos.lib.JsbBridge#sendToScript(String, String)
 * - 优先在 Cocos 游戏线程执行（com.cocos.lib.CocosHelper#runOnGameThread），不可用则回落主线程
 *
 * 说明：使用反射以避免在未集成 cocos 原生库时的编译期依赖问题。
 */
public final class CocosChatListener implements VoiceChatClient.Listener {

    private static final String TAG = "CocosChatListener";

    private final Handler main = new Handler(Looper.getMainLooper());

    // 反射缓存
    private static Class<?> sJsbBridgeCls;
    private static Method sSendToScript;
    private static Class<?> sCocosHelperCls;
    private static Method sRunOnGameThread;

    private void runOnGameThread(@NonNull Runnable task) {
        try {
            if (sRunOnGameThread != null && sCocosHelperCls != null) {
                sRunOnGameThread.invoke(null, task);
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "runOnGameThread fallback: " + t.getMessage());
        }
        // 回落到主线程
        main.post(task);
    }

    private void sendToCocos(@NonNull String event, @NonNull JSONObject payload) {
        final String json = payload.toString();
        runOnGameThread(() -> {
            try {
                Log.d(TAG, "sendToCocos " + event + " " + json);
                JsbBridge.sendToScript(event, payload.toString());
                // todo: Log.i(TAG, "[noop] sendToScript(" + event + ", " + json + ")");
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
        // sendToCocos("chat.assistant.delta", jText(text));
        sendToCocos("CHAT.ASSISTANT.DELTA", jText(text));
    }

    @Override
    public void onRecordingReady() {
        sendToCocos("CHAT.RECORDING.READY", new JSONObject());
    }

    @Override
    public void onRecordingStopped() {
        sendToCocos("CHAT.RECORDING.STOPPED", new JSONObject());
    }

    @Override
    public void onAssistantFinal(@NonNull String text) {
        sendToCocos("CHAT.ASSISTANT.FINAL", jText(text));
    }

    @Override
    public void onFirstAudioLatency(long millis) {
        // sendToCocos("chat.latency", jPair("latencyMs", millis));
    }

    @Override
    public void onConnectionClosed() {
        sendToCocos("CHAT.STOPPED", jPair("reason", "normal"));
    }
}

