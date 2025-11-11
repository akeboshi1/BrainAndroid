package com.jujie.paipai.chat;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * 负责 WebSocket 连接、收发与自动重连（指数退避 + 抖动）。
 * 仅承载传输，不解析业务协议。
 */
public class ChatTransport {

    public interface Listener {
        void onOpen();
        void onTextMessage(@NonNull String text);
        void onBinaryMessage(@NonNull byte[] bytes);
        void onClosed(int code, @NonNull String reason);
        void onFailure(@NonNull Throwable t, @Nullable Response response);
        default void onReconnectScheduled(int attempt, long delayMs) {}
    }

    private final OkHttpClient http;
    private final Listener listener;

    private volatile @Nullable WebSocket ws;
    private volatile boolean isConnected = false;
    private volatile boolean connecting = false;

    private volatile boolean manualClose = false;
    private volatile boolean autoReconnect = true;
    private volatile boolean reconnectOnNormalClose = true;

    private boolean enableAsr = true;

    public void setEnableAsr(boolean enableAsr) {
        this.enableAsr = enableAsr;
    }

    public boolean isEnableAsr() {
        return enableAsr;
    }

    private volatile @Nullable String lastUrl = null;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "chat-transport-reconnector");
        t.setDaemon(true);
        return t;
    });
    private final Random random = new Random();
    private int reconnectAttempts = 0;

    public ChatTransport(@NonNull Listener listener) {
        this.listener = listener;
        this.http = new OkHttpClient.Builder()
                .pingInterval(15, TimeUnit.SECONDS)
                .build();
    }

    public boolean isConnected() {
        return isConnected;
    }

    public synchronized void connect(@NonNull String url) {
        manualClose = false;
        lastUrl = url;
        reconnectAttempts = 0;
        openWebSocket(url);
    }

    public synchronized void setAutoReconnect(boolean enable) {
        this.autoReconnect = enable;
    }

    public synchronized void setReconnectOnNormalClose(boolean enable) {
        this.reconnectOnNormalClose = enable;
    }

    public boolean sendText(@NonNull String text) {
        WebSocket w = ws;
        return w != null && isConnected && w.send(text);
    }

    public boolean sendBinary(@NonNull byte[] bytes) {
        WebSocket w = ws;
        return w != null && isConnected && w.send(ByteString.of(bytes));
    }

    public boolean sendBinary(@NonNull byte[] bytes, int offset, int length) {
        WebSocket w = ws;
        return w != null && isConnected && w.send(ByteString.of(bytes, offset, length));
    }

    public synchronized void close() {
        manualClose = true;
        autoReconnect = false;
        reconnectAttempts = 0;
        connecting = false;
        WebSocket w = ws;
        ws = null;
        if (w != null) {
            try { w.close(1000, "client close"); } catch (Exception ignored) {}
        }
    }

    public synchronized void release() {
        close();
        try { scheduler.shutdownNow(); } catch (Exception ignored) {}
    }

    private void openWebSocket(@NonNull String url) {
        synchronized (this) {
            if (connecting) return; // 防止并发重复连接
            connecting = true;
        }

        // 根据 enableAsr 标志修改 URL 参数
        Request req =null;
        if(this.enableAsr){
            req = new Request.Builder().url(url + "&enableAsr=1").build();
        }else{
            req = new Request.Builder().url(url).build();
        }

        Log.d("ChatTransport", "Opening WebSocket to " + req.url());

        ws = http.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                isConnected = true;
                synchronized (ChatTransport.this) { reconnectAttempts = 0; connecting = false; }
                try { listener.onOpen(); } catch (Exception ignored) {}
            }
            @Override public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                try { listener.onTextMessage(text); } catch (Exception ignored) {}
            }
            @Override public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
                try { listener.onBinaryMessage(bytes.toByteArray()); } catch (Exception ignored) {}
            }
            @Override public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                isConnected = false;
                synchronized (ChatTransport.this) { connecting = false; }
                try { listener.onClosed(code, reason); } catch (Exception ignored) {}
                //handleMaybeReconnect(code, reason, null);
            }
            @Override public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                isConnected = false;
                synchronized (ChatTransport.this) { connecting = false; }
                try { listener.onFailure(t, response); } catch (Exception ignored) {}
                //handleMaybeReconnect(-1, String.valueOf(t.getMessage()), response);
            }
        });
    }

    private void handleMaybeReconnect(int code, @NonNull String reason, @Nullable Response response) {
        boolean normalClose = (code == 1000);
        if (!autoReconnect || manualClose || lastUrl == null) return;
        if (normalClose && !reconnectOnNormalClose) return;
        synchronized (this) {
            if (connecting) return; // 仍在连接中则不重复调度
        }
        long delayMs = nextBackoffDelayMs();
        int attempt = reconnectAttempts; // nextBackoffDelayMs 内已++
        try { listener.onReconnectScheduled(attempt, delayMs); } catch (Exception ignored) {}
        scheduler.schedule(() -> {
            synchronized (ChatTransport.this) {
                if (manualClose || !autoReconnect || lastUrl == null) return;
            }
            openWebSocket(lastUrl);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private long nextBackoffDelayMs() {
        // 指数退避：base=500ms，上限15s，添加抖动 +/-20%
        long base = 500L;
        long max = 15_000L;
        long exp = (long) (base * Math.pow(2.0, Math.min(reconnectAttempts, 5))); // cap exponent
        reconnectAttempts++;
        long delay = Math.min(exp, max);
        double jitter = 0.8 + (random.nextDouble() * 0.4); // [0.8,1.2)
        return (long) (delay * jitter);
    }
}
