package com.jujie.audiosdk;

import android.util.Log;

import com.cocos.lib.JsbBridge;

import org.json.JSONException;
import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class ASRWebSocket {
    private static ASRWebSocket instance;
    private WebSocket webSocket;

    private ASRWebSocket() {
    }

    public void connectWS() {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url("wss://test.paipai.xinjiaxianglao.com/asr-tmp/").build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket webSocket, okhttp3.Response response) {
                // WebSocket 连接已打开
                Log.d("WebSocketManager", "onOpen:" + response.message());

                JsbBridge.sendToScript("ASRConnected");
            }


            @Override
            public void onMessage(WebSocket webSocket, String text) {
                // 处理来自服务器的文本消息
                Log.d("ASR", ">>" + text + "<<");

                if (text.trim().equals("1")) {
                    Log.d("ASR", "ASRConnected");
                    JsbBridge.sendToScript("ASRConnected");
                    return;
                }
                Log.d("ASR", "onMessage:" + text);
                JSONObject jsonObject = null;
                try {
                    jsonObject = new JSONObject(text);
                    String content = jsonObject.getString("content");
                    JsbBridge.sendToScript("ASRResult", "{\"content\":\"" + content + "\"}");
                } catch (JSONException e) {
                    Log.d("WebSocketManager", "parse json fail");
                }
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                Log.d("ASR", "on closed");
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, okhttp3.Response response) {
                Log.d("ASR", "on failure");
            }
        });
    }

    public void closeWS() {
        Log.d("ASR", "closeWS ");
        if (webSocket != null) {
            Log.d("ASR", "close 1000 ");
            webSocket.close(1000, "关闭");
            webSocket = null;
        }
        JsbBridge.sendToScript("ASRClosed");
    }

    public static ASRWebSocket getInstance() {
        if (instance == null) {
            instance = new ASRWebSocket();
        }
        return instance;
    }

    public void sendAudioFrame(byte[] audioFrame) {
        if (webSocket != null) {
            webSocket.send(ByteString.of(audioFrame));
        }
    }
}