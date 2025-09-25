package com.cocos.game;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.jujie.paipai.common.DeviceInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class LogcatCapture {
    private static final String TAG = "LogcatCapture";
    // TODO: 可根据构建环境切换地址（如: dev / test / prod）；当前为生产/监控地址
    private static final String WEBSOCKET_URL = "wss://colapai.xinjiaxianglao.com/cocos-monitor/";
    private static final int RECONNECT_DELAY_SECONDS = 10;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;

    // 批量发送策略：每3秒发送一次或累计达到10条立即发送
    private static final int BATCH_MAX_SIZE = 10;
    private static final int BATCH_INTERVAL_SECONDS = 3;
    // 队列最大长度（防止异常洪泛占用内存）；超出时丢弃最旧日志
    private static final int QUEUE_CAPACITY = 1000;

    // 当前应用的进程ID，仅发送当前进程日志
    private static final int CURRENT_PID = android.os.Process.myPid();

    // 日期格式（SimpleDateFormat 不是线程安全，这里仅在单线程中使用，如需多线程共享可改用 ThreadLocal）
    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    // 静态初始化块 - 注册关闭钩子，进程退出前确保资源释放
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.d(TAG, "JVM shutdown hook triggered, cleaning up resources");
            shutdown();
        }));
    }

    // 状态控制变量
    private static final AtomicBoolean isCapturing = new AtomicBoolean(false);
    private static final AtomicInteger wsStatus = new AtomicInteger(0); // 0: 未连接, 1: 连接中, 2: 已连接
    private static final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private static final AtomicBoolean isAppInForeground = new AtomicBoolean(true);
    private static final AtomicBoolean shouldPauseCapture = new AtomicBoolean(false);

    // 线程池：采集、重连、批量发送
    private static final ExecutorService logCaptureExecutor = Executors.newSingleThreadExecutor();
    private static final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
    private static final ScheduledExecutorService batchSenderExecutor = Executors.newSingleThreadScheduledExecutor();

    // 日志缓冲队列（生产者：logcat采集线程；消费者：批量发送线程）
    private static final LinkedBlockingQueue<Map<String, Object>> logQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    // WebSocket相关
    private static volatile WebSocket webSocket;
    private static volatile WebSocketListener listener;
    private static volatile OkHttpClient httpClient;

    // logcat 进程与读取器
    private static volatile Process logProcess;
    private static volatile BufferedReader logReader;

    // ============================= 对外主控方法 =============================

    // 启动日志采集
    public static synchronized void startCapturing() {
        if (isCapturing.get()) {
            Log.d(TAG, "Already capturing logs");
            return;
        }
        Log.d(TAG, "Starting log capture");
        isCapturing.set(true);
        // 启动采集线程
        logCaptureExecutor.submit(() -> {
            try {
                captureCocosLogs();
            } catch (Exception e) {
                Log.e(TAG, "Error in log capture thread", e);
                isCapturing.set(false);
            }
        });
        // 启动批量发送调度
        startBatchSender();
    }

    // 停止日志采集（同时停止批量发送）
    public static synchronized void stopCapturing() {
        Log.d(TAG, "Stopping log capture");
        if (!isCapturing.getAndSet(false)) {
            return; // 已经停止
        }
        // 关闭 logcat 进程及 reader
        closeResources();
        // 关闭 WebSocket
        if (webSocket != null) {
            try {
                webSocket.close(1000, "Stopping capture");
            } catch (Exception ignored) {
            }
            webSocket = null;
        }
        wsStatus.set(0);
        // 清理队列，避免残留
        logQueue.clear();
    }

    // 应用进入后台
    public static synchronized void onAppGoesToBackground() {
        Log.d(TAG, "App went to background, pausing log processing");
        isAppInForeground.set(false);
        shouldPauseCapture.set(true);
        if (webSocket != null && isAlive()) {
            webSocket.close(1000, "App background");
        }
    }

    // 应用回到前台
    public static synchronized void onAppComesToForeground() {
        Log.d(TAG, "App came to foreground, resuming log processing");
        isAppInForeground.set(true);
        shouldPauseCapture.set(false);
        if (!isCapturing.get()) {
            startCapturing();
        } else if (!isAlive()) {
            reconnectAttempts.set(0);
            connect();
        }
    }

    public static synchronized void onScreenOff() {
        Log.d(TAG, "Screen off - pause capture loop speed");
        shouldPauseCapture.set(true);
    }

    public static synchronized void onScreenOn() {
        Log.d(TAG, "Screen on - resume capture if needed");
        if (isAppInForeground.get()) {
            shouldPauseCapture.set(false);
            if (isCapturing.get() && !isAlive()) {
                connect();
            }
        }
    }

    // 关闭所有资源（供外部 APP 退出时调用）
    public static synchronized void shutdown() {
        Log.d(TAG, "Shutting down LogcatCapture");
        stopCapturing();
        // 关闭线程池
        logCaptureExecutor.shutdown();
        reconnectExecutor.shutdown();
        batchSenderExecutor.shutdown();
        try {
            logCaptureExecutor.awaitTermination(3, TimeUnit.SECONDS);
            reconnectExecutor.awaitTermination(3, TimeUnit.SECONDS);
            batchSenderExecutor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (httpClient != null) {
            try {
                httpClient.dispatcher().executorService().shutdown();
                httpClient.connectionPool().evictAll();
            } catch (Exception ignored) {}
        }
    }

    // ============================= 状态查询 =============================
    public static boolean isAlive() { return wsStatus.get() == 2; }
    public static boolean isConnecting() { return wsStatus.get() == 1; }
    public static boolean isCapturingLogs() { return isCapturing.get(); }
    public static int getWebSocketStatus() { return wsStatus.get(); }
    public static boolean isInForeground() { return isAppInForeground.get(); }

    // ============================= 内部实现 =============================

    // 建立 WebSocket 连接
    public static synchronized void connect() {
        if (isAlive()) { return; }
        if (isConnecting()) { return; }
        Log.d(TAG, "Connecting to WebSocket");
        webSocket = createNewWebSocket();
    }

    private static WebSocket createNewWebSocket() {
        if (httpClient == null) {
            httpClient = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .build();
        }
        Request request = new Request.Builder().url(WEBSOCKET_URL).build();
        if (listener == null) {
            listener = new WebSocketListener() {
                @Override
                public void onOpen(@NonNull WebSocket ws, @NonNull okhttp3.Response response) {
                    wsStatus.set(2);
                    reconnectAttempts.set(0);
                    Log.d(TAG, "WebSocket connected");
                }
                @Override
                public void onMessage(@NonNull WebSocket ws, @NonNull String text) {
                    Log.d(TAG, "WS message: " + text);
                }
                @Override
                public void onFailure(@NonNull WebSocket ws, @NonNull Throwable t, @Nullable okhttp3.Response response) {
                    wsStatus.set(0);
                    Log.e(TAG, "WebSocket failure: " + t.getMessage(), t);
                    scheduleReconnect();
                }
                @Override
                public void onClosed(@NonNull WebSocket ws, int code, @NonNull String reason) {
                    wsStatus.set(0);
                    Log.d(TAG, "WebSocket closed: " + reason);
                    if (isCapturing.get()) {
                        scheduleReconnect();
                    }
                }
            };
        }
        wsStatus.set(1);
        return httpClient.newWebSocket(request, listener);
    }

    private static void scheduleReconnect() {
        if (shouldPauseCapture.get() || !isAppInForeground.get()) {
            Log.d(TAG, "Skip reconnect (background or paused)");
            return;
        }
        int attempts = reconnectAttempts.incrementAndGet();
        if (attempts > MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Max reconnect attempts reached");
            return;
        }
        Log.d(TAG, "Reconnect attempt " + attempts + " scheduled");
        reconnectExecutor.schedule(() -> {
            if (isCapturing.get() && !isAlive() && isAppInForeground.get() && !shouldPauseCapture.get()) {
                connect();
            }
        }, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    // 采集 logcat (仅 Cocos tag, 当前进程) 并写入队列
    private static void captureCocosLogs() {
        connect();
        try {
            logProcess = Runtime.getRuntime().exec("logcat -v time -s Cocos");
            logReader = new BufferedReader(new InputStreamReader(logProcess.getInputStream()));
            String line;
            String lastLevel = "I"; // 默认信息级别
            Log.d(TAG, "Start capturing Cocos logs");
            // 修改while循环条件，在后台时暂停处理但不退出循环
            while (isCapturing.get() && (line = logReader.readLine()) != null) {
                try {
                    // 在后台时暂停处理，但不退出循环
                    if (!isAppInForeground.get() || shouldPauseCapture.get()) {
                        Thread.sleep(800); // 后台时降低处理频率
                        continue;
                    }
                    String[] details = extractLogDetails(line);
                    if (details != null && details.length == 2) {
                        lastLevel = details[0];
                        enqueueLog(details[0], details[1]);
                    } else if (line.trim().length() > 0) {
                        enqueueLog(lastLevel, line.trim());
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception perLineEx) {
                    Log.e(TAG, "Error processing log line", perLineEx);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error starting logcat process", e);
        } finally {
            closeResources();
            isCapturing.set(false);
            Log.d(TAG, "Capture loop ended");
        }
    }

    // 解析 logcat 行，提取级别与消息；格式示例：
    // 01-21 13:51:34.219 D/Cocos   (11720): 13:51:34 [DEBUG]: JS: socket connected
    private static String[] extractLogDetails(String logLine) {
        if (logLine == null || logLine.trim().isEmpty()) return null;
        try {
            String[] parts = logLine.split("\\s+");
            if (parts.length < 5) return null;
            String levelAndTag = parts[2];
            if (!levelAndTag.contains("/Cocos")) return null;
            String pidPart = parts[3]; // (11720):
            if (!(pidPart.startsWith("(") && pidPart.endsWith("):"))) return null;
            String pidStr = pidPart.substring(1, pidPart.length() - 2);
            int logPid;
            try { logPid = Integer.parseInt(pidStr); } catch (NumberFormatException e) { return null; }
            if (logPid != CURRENT_PID) return null;
            String level = levelAndTag.split("/")[0];
            StringBuilder sb = new StringBuilder();
            for (int i = 4; i < parts.length; i++) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(parts[i]);
            }
            String msg = sb.toString().trim();
            if (msg.isEmpty()) return null;
            return new String[]{level, msg};
        } catch (Exception e) {
            Log.e(TAG, "Parse error", e);
            return null;
        }
    }

    // 入队；若队列满，丢弃最旧一条以保证最新日志
    private static void enqueueLog(String level, String message) {
        if (message == null || message.trim().isEmpty()) return;
        Map<String, Object> map = new HashMap<>();
        SimpleDateFormat sdf = new SimpleDateFormat(DATE_PATTERN);
        map.put("level", level);
        map.put("version", DeviceInfo.VERSION_NAME);
        map.put("brand", DeviceInfo.BRAND);
        map.put("model", DeviceInfo.MODEL);
        map.put("ts", sdf.format(new Date()));
        map.put("message", message.trim());
        if (nonEmpty(DeviceInfo.MpNo)) map.put("mpNo", DeviceInfo.MpNo);
        if (nonEmpty(DeviceInfo.OrgCode)) map.put("orgCode", DeviceInfo.OrgCode);
        if (nonEmpty(DeviceInfo.UserName)) map.put("userName", DeviceInfo.UserName);
        // 控制队列大小：满则移除最旧
        if (!logQueue.offer(map)) {
            logQueue.poll();
            logQueue.offer(map);
        }
    }

    private static boolean nonEmpty(String s) { return s != null && !s.isEmpty(); }

    // 启动批量发送调度器
    private static void startBatchSender() {
        batchSenderExecutor.scheduleWithFixedDelay(() -> {
            try {
                if (!isCapturing.get() || logQueue.isEmpty()) return;
                if (!isAlive()) return; // 未连接则等待
                List<Map<String, Object>> batch = new ArrayList<>(BATCH_MAX_SIZE);
                // drainTo 不阻塞，尽量批量获取
                logQueue.drainTo(batch, BATCH_MAX_SIZE);
                if (batch.isEmpty()) return;
                sendBatch(batch);
            } catch (Exception e) {
                Log.e(TAG, "Batch sender error", e);
            }
        }, BATCH_INTERVAL_SECONDS, BATCH_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    // 批量发送
    private static void sendBatch(List<Map<String, Object>> batch) {
        if (batch == null || batch.isEmpty() || webSocket == null || !isAlive()) return;
        try {
            String jsonArr = new org.json.JSONArray(batch).toString();
            boolean ok = webSocket.send(jsonArr);
            if (!ok) {
                Log.w(TAG, "Failed to send batch size=" + batch.size());
            }
        } catch (Exception e) {
            Log.e(TAG, "Send batch exception", e);
        }
    }

    // 关闭 logcat 相关资源
    private static void closeResources() {
        try { if (logReader != null) { logReader.close(); } } catch (IOException ignored) {}
        logReader = null;
        try { if (logProcess != null) { logProcess.destroy(); } } catch (Exception ignored) {}
        logProcess = null;
    }
}
