package com.cocos.game;

import android.util.Log;

import com.jujie.audiosdk.Helper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class LogcatCapture {
    private static final String TAG = "LogcatCapture";
    private static final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private static boolean isCapturing = false;
    private static WebSocket webSocket;
    private static WebSocketListener listener;
    private static int wsStatus = 0; // 0: 未连接, 1: 连接中, 2: 已连接, 3: 关闭


    // 启动日志捕获
    public static void startCapturing() {
        if (isCapturing) {
            return; // 如果已经在捕获日志，直接返回
        }

        isCapturing = true;
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                captureCocosLogs();
            }
        });

        // sleep 1s
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public synchronized static void connect() {
        if (isAlive()) return;
        Log.d(TAG, "connect()");
        webSocket = createNewWebSocket();
    }


    // 停止日志捕获
    public static void stopCapturing() {
        isCapturing = false;
    }

    private static WebSocket createNewWebSocket() {

        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        Request request = new Request.Builder().url("wss://test.paipai.xinjiaxianglao.com/cocos-monitor/").build();
        if( listener == null) {
                listener =
                new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket ws, okhttp3.Response response) {
                        super.onOpen(ws, response);
                        Log.d(TAG, "WebSocket 连接成功");
                        wsStatus = 2; // 已连接
                    }

                    @Override
                    public void onMessage(WebSocket ws, String text) {
                        super.onMessage(ws, text);
                    }

                    @Override
                    public void onFailure(WebSocket ws, Throwable t, okhttp3.Response response) {
                        super.onFailure(ws, t, response);
                        Log.e(TAG, "WebSocket 错误: " + t.getMessage());
                        wsStatus = 0; // 未连接
                    }

                    @Override
                    public void onClosed(WebSocket ws, int code, String reason) {
                        super.onClosed(ws, code, reason);
                        Log.d(TAG, "WebSocket 连接关闭: " + reason);
                        wsStatus = 0;


                        // 10s 重新连接
//                        try {
//                            Thread.sleep(10000);
//                        } catch (InterruptedException e) {
//                            e.printStackTrace();
//                        }

//                        webSocket = createNewWebSocket();


                    }
                };
        }

        return client.newWebSocket(request,
                listener
        );

    }

    // 捕获并处理日志
    private static void captureCocosLogs() {
        webSocket = createNewWebSocket();
        wsStatus = 1; // 连接中
        try {
            // 通过 logcat 过滤出 tag=cocos 的日志
            Process process = Runtime.getRuntime().exec("logcat -v time -s Cocos");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            String lastLevel = "";
            while (isCapturing && (line = reader.readLine()) != null) {
                // 将日志发送到服务器
                String[] logDetails = extractLogDetails(line);
                if(logDetails != null && logDetails.length == 2) {
                    lastLevel = logDetails[0];
                    sendLogToServer(logDetails[0], logDetails[1]);
                }else{
                    sendLogToServer(lastLevel, line);
                }

            }
        } catch (Exception e) {
            Log.e("LogcatCapture", "Error capturing logs", e);
        }
    }

    // 提取日志级别和日志内容，返回一个 String 数组
    private static String[] extractLogDetails(String logLine) {
        // 假设日志格式是：01-21 13:51:34.219 D/Cocos   (11720): 13:51:34 [DEBUG]: JS: socket connected
        // 格式解析：
        // 1. 日志级别和标签在第 3 部分（D/Cocos）
        // 2. 日志内容在 ":" 后面

        // 按空格分割开，获取日志的主要部分
        String[] parts = logLine.split(" ");

        if (parts.length < 8) {
            return null;  // 如果日志格式不正确，返回 null
        }

        /**
         * >>> parts[0]: 01-21
         * >>> parts[1]: 14:11:29.370
         * >>> parts[2]: I/Cocos
         * >>> parts[3]:
         * >>> parts[4]:
         * >>> parts[5]: (16453):
         * >>> parts[6]: 14:11:29
         * >>> parts[7]: [INFO]:
         * >>> parts[8]: Shader
         */

        // 日志实体在 parts[7] 之后所有的部分, 合并为一个字符串: 把parts[7]之后的所有部分合并为一个字符串
        StringBuilder sb = new StringBuilder();
        for(int i = 8; i < parts.length; i++) {
            sb.append(parts[i]);
            sb.append(" ");
        }
        Log.d("LogcatCapture", ">>> sb: " + sb.toString());

//        for(int i = 0; i < parts.length; i++) {
//            Log.d("LogcatCapture", ">>> parts[" + i + "]: " + parts[i]);
//        }

//        Log.d("LogcatCapture", ">>> parts: " + parts[0] + ", " + parts[1] + ", " + parts[2] + ", " + parts[3] + ", " + parts[4]);

        // 日志级别在 `D/Cocos` 部分，`D` 是级别
        String levelAndTag = parts[2];  // 例如：D/Cocos
        Log.d("LogcatCapture", ">>> levelAndTag: [" + levelAndTag + "]");

        String level = levelAndTag.split("/")[0]; // 获取日志级别部分（D, I, E）
        Log.d("LogcatCapture", ">>> level: [" + level + "]");

        if (sb.length() > 0) {
            return new String[] {level, sb.toString()};
        }

        return null;  // 如果没有找到日志内容，返回 null
    }


    // 发送日志到服务器
    private static void sendLogToServer(String level, String message) {
        // TO DO: 发送日志到服务器
        Log.d("LogcatCapture", ">>> send log to server: level = " + level + ", message = [" + message + "]");
        Log.d("LogcatCapture", ">>> webSocket: " + webSocket);

        if(webSocket != null && isAlive()) {
            HashMap map = new HashMap();
            map.put("uuid", Helper.uuid);
            map.put("level", level);
            map.put("message", message);
            map.put("timestamp", System.currentTimeMillis());

            String json = new org.json.JSONObject(map).toString();

            webSocket.send(json);
        }
    }

    public static Boolean isAlive(){
        return wsStatus == 2;
    }

    public static Boolean isConnecting(){
        return wsStatus == 1;
    }

}
