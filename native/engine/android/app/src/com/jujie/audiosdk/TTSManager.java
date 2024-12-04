package com.jujie.audiosdk;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.provider.MediaStore;
import android.util.Log;

import com.cocos.lib.JsbBridge;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class TTSManager {
    private static TTSManager instance;
    private static final String TAG = "TTSManager";

    private Queue<byte[]> audioQueue = new LinkedList<>();
    private WebSocket webSocket;
    private String wsUrl = "wss://test.paipai.xinjiaxianglao.com/api/tts-tmp/";

    private boolean connected = false;
    private boolean connecting = false;
    private boolean isPlaying = false;
    private int idx = 0;
    private AudioTrack audioTrack;

    public TTSManager() {

    }

    public static void connect() {
        if (instance == null) {
            instance = new TTSManager();
            instance.connectToWebSocket();
        } else if (!instance.connecting) {
            instance.connectToWebSocket();
        }
    }

    private AudioTrack initAudioTrack(int bufferSize) {
        Log.d(TAG, "init audio track bufferSize=" + bufferSize);
        int sampleRateInHz = 24000; // 示例采样率，根据实际情况调整
        int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

        AudioTrack theAudioTrack = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRateInHz,
                channelConfig,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STATIC
        );

        return theAudioTrack;
    }

    private void connectToWebSocket() {
        Log.d(TAG, "connectToWebSocket");
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        Request request = new Request.Builder().url(wsUrl).build();
        webSocket = client.newWebSocket(request,
                new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket webSocket, okhttp3.Response response) {
                        super.onOpen(webSocket, response);
                        Log.d(TAG, "WebSocket 连接成功");
                        connected = true;
                        connecting = false;
                        JsbBridge.sendToScript("TTSConnected");
                    }

                    @Override
                    public void onMessage(WebSocket webSocket, String text) {
                        super.onMessage(webSocket, text);
                        // 处理文本消息（如果需要）
                    }

                    @Override
                    public void onMessage(WebSocket webSocket, ByteString bytes) {
                        Log.d(TAG, "on message");

                        byte[] audioData = bytes.toByteArray();
                        audioQueue.add(audioData);
                        idx++;
                        Log.d(TAG, "isPlaying: " + isPlaying);

                        if (!isPlaying) {
                            playNextInQueue();
                        }
                    }

                    @Override
                    public void onFailure(WebSocket webSocket, Throwable t, okhttp3.Response response) {
                        super.onFailure(webSocket, t, response);
                        Log.e(TAG, "WebSocket 错误: " + t.getMessage());
                    }

                    @Override
                    public void onClosed(WebSocket webSocket, int code, String reason) {
                        super.onClosed(webSocket, code, reason);
                        Log.d(TAG, "WebSocket 连接关闭: " + reason);
                        isPlaying = false;
                    }
                }
        );
    }


    private void playNextInQueue() {
        Log.d(TAG, "play next in queue len: " + audioQueue.size() + ", idx: " + idx);
        if (audioQueue.isEmpty() && idx == 0) {
            isPlaying = false;
            return;
        }

        isPlaying = true;
        byte[] audioData = audioQueue.poll();
        if (audioData != null) {
            String prefix = new String(audioData, 0, 26);
            Log.d(TAG, "prefix: " + prefix);
            String uid = prefix.substring(0, 20);
            boolean isLast = prefix.charAt(20) == '1';
            String seq = prefix.substring(21, 26);

            Log.d(TAG, "on message audioData length: " + audioData.length + ", uid: " + uid + ", isLast: " + isLast + ", seq: " + seq);

            byte[] audioContent = new byte[audioData.length - 26];
            System.arraycopy(audioData, 26, audioContent, 0, audioContent.length);

            int bytesPerSample = 2; // 16 位 PCM
            int channelCount = 1; // 单声道
            int frameCount = audioContent.length / (bytesPerSample * channelCount);
            Log.d(TAG, "audioContent.length: " + audioContent.length + ", frameCount: " + frameCount);

            int iseq = 0;
            try {
                iseq = Integer.valueOf(seq);
            }catch (Exception e) {
                Log.d(TAG, "NumberFormatException: " + e.getMessage());
            }

            if(iseq == 1) {
                // 第一帧
                try {
                    JsbBridge.sendToScript("TTSStart", "{\"uid\": \"" + uid.trim() + "\"}");
                } catch (Exception e) {
                    Log.d(TAG, e.getMessage());
                }
            }

            if (audioContent.length == 0) {
                if (isLast) {
                    isPlaying = false;
                    // 帧长度为0， 播放结束
                    try {
                        JsbBridge.sendToScript("TTSEnd", "{\"uid\": \"" + uid.trim() + "\"}");
                    } catch (Exception e) {
                        Log.d(TAG, e.getMessage());
                    }
                }

                idx--;
                // 继续播放下一条
                isPlaying = false;
                playNextInQueue();
                return;
            }

            if (audioTrack != null) {
                audioTrack.release(); // 释放之前的 AudioTrack
            }
            audioTrack = initAudioTrack(audioContent.length);

            int result = audioTrack.write(audioContent, 0, audioContent.length);
            if (result == AudioTrack.ERROR_BAD_VALUE || result == AudioTrack.ERROR_INVALID_OPERATION) {
                Log.e(TAG, "AudioTrack write error: " + result);
            } else {
                Log.d(TAG, "AudioTrack write successful, bytes written: " + result);
            }

            audioTrack.setNotificationMarkerPosition(frameCount);

            audioTrack.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
                @Override
                public void onMarkerReached(AudioTrack track) {
                    Log.d(TAG, "on marker reached uid: " + uid + ", isLast: " + isLast);
                    try {
                        if (isLast) {
                            JsbBridge.sendToScript("TTSEnd", "{\"uid\": \"" + uid.trim() + "\"}");
                        }
                    } catch (Exception e) {
                        Log.d(TAG, e.getMessage());
                    }
                    Log.d(TAG, "play next in queue " + audioQueue.size());
                    playNextInQueue();
                }

                @Override
                public void onPeriodicNotification(AudioTrack track) {
                }
            });

            audioTrack.play();
            Log.d(TAG, "play audio");

            idx--;
        }
    }


    public static void send(String uid, String text) {
        if (instance != null) {
            instance.sendText(uid, text);
        }
    }

    public static void closeTTS() {
        Log.d(TAG, "closeTTS");
        instance.close();
    }

    public void close() {
        Log.d(TAG, "close() connected: " + connected);
        if (connected) {
            connected = false;
            audioQueue.clear();
            if (webSocket != null) {
                webSocket.close(1000, "");
                webSocket = null;
            }
            Log.d(TAG, "send script");
            JsbBridge.sendToScript("TTSClosed");
            isPlaying = false;
        }
    }

    public void sendText(String uid, String text) {
        if (!connected) {
            return;
        }
        Log.d(TAG, "websocket: " + webSocket + ", connected: " + connected);
        if (webSocket != null && connected) {
            String message = "{\"uid\":\"" + uid + "\", \"encoding\": \"pcm\", \"content\":\"" + text.trim() + "\"}";
            HashMap<String, String> map = new HashMap<>();
            map.put("uid", uid);
            map.put("encoding", "pcm");
            map.put("content", text.trim());

            JSONObject jsonObject = new JSONObject(map);
            webSocket.send(jsonObject.toString());

        }
    }
}
