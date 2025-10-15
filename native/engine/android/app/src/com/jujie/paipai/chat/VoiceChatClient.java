package com.jujie.paipai.chat;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media3.common.util.UnstableApi;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 *  聊天客户端职责梳理：
 * 1) 连接管理：通过 WebSocket 与服务器通信，按服务器事件驱动 UI 与音频。
 * 2) 语音采集：使用 AudioRecord 以 16 kHz/PCM16 单声道采集，流式发送到服务器。
 * 3) 对话展示：处理 transcript/llm_delta/llm_complete 事件，按“你/大模型”分栏呈现。
 * 4) 语音播放：维护 TTS 队列，串行播放，支持 tts_cancel 实时打断。
 *
 * 关键设计点：
 * - 播放线程：由 TtsPlayer 内部封装，避免跨线程访问 ExoPlayer。
 * - 音频焦点：对通信用途（USAGE_VOICE_COMMUNICATION）禁用自动音频焦点（在 TtsPlayer 内处理）。
 * - 全双工：录音使用 VOICE_COMMUNICATION 源并启用 AEC/NS/AGC；系统音频模式切换为 MODE_IN_COMMUNICATION 以获得更稳定的双向通话路径。
 * - 路由策略：不强制外放；检测到耳机/蓝牙时关闭外放并交由系统路由至耳机。
 * - 中断策略：新回复触发时清空旧 TTS 队列，尽快播放最新一轮回复，保持“最新优先”。
 */
public class VoiceChatClient {

    /**
     * - onReady：服务器返回 ready，允许开始录音
     * - onLog：调试日志
     * - onUserTranscript：追加“你”的识别文本
     * - onAssistantFinal：落地“大模型”最终回复
     * - onConnectionClosed：连接关闭通知
     */
    public interface Listener {
        void onReady();
        void onLog(@NonNull String line);
        void onUserTranscript(@NonNull String text);
        void onAssistantFinal(@NonNull String text);
        void onConnectionClosed();
        void onFirstAudioLatency(long millis);
        void onAssistantDelta(@NonNull String text);
        void onRecordingReady();
        void onRecordingStopped();
    }

    // 应用上下文与回调
    private final Context app;
    private final Listener listener;

    // 音频系统与录音增强效果（可用则启用）：AEC 回声消除、NS 降噪、AGC 自动增益
    private final AudioManager audioManager;
    private boolean commModeApplied = false; // 是否已切到 MODE_IN_COMMUNICATION

    // WebSocket 传输层
    private final ChatTransport transport;

    // 不再直接持有 OkHttpClient/WebSocket
    // private final OkHttpClient http
    // private WebSocket ws;
    private boolean isConnected = false; // 连接打开后置 true，由 ChatTransport 回调维护
    private boolean isReady = false;     // 服务器是否返回 ready（允许录音）

    // 改由独立的 TtsPlayer 管理播放与队列
    private final ChatTtsPlayer ttsPlayer;

    // 新增：MicRecorder 封装麦克风
    private @Nullable MicRecorder micRecorder;

    // 录音配置：16 kHz/PCM16 单声道
    private static final int SAMPLE_RATE = 16000;

    // TTS 流拼接任务：在 tts_start 与 tts_end 之间累积二进制块
    private static class TtsStreamJob {
        final String requestId; final int sequence; final boolean isFinal;
        final List<byte[]> buffers = new ArrayList<>();
        String textDelta = ""; // 新增：增量文本
        TtsStreamJob(String r, int s, boolean f){ requestId=r; sequence=s; isFinal=f; }
    }
    private @Nullable TtsStreamJob activeStreamJob; // 当前活跃的 TTS 拼接任务

    // 新增：是否在收到 ready 后自动开始录音（用于“开始”一键连接+录音）
    private volatile boolean autoStartOnReady = false;

    // 新增：时延统计（按 responseId）
    private final Map<String, Long> asrDoneAtMs = new java.util.HashMap<>();
    private final Set<String> firstAudioReported = new java.util.HashSet<>();

    // 助手回复增量缓存：按 responseId 聚合 llm_delta，llm_complete 时落地
    private final Map<String, StringBuilder> assistantBuffers = new java.util.HashMap<>();
    private @Nullable String activeResponseId = null; // 当前“最新优先”的响应 ID
    // 新增：按 responseId 跟踪“已随播展示的字符位置”，用于在无 tts 文本时从 llm_delta 回退切片
    private final Map<String, Integer> playbackDisplayedIndex = new java.util.HashMap<>();
    // 新增：随播接管集合，避免 llm_complete 重复落地
    private final Set<String> ttsManagedResponses = new java.util.HashSet<>();
    // 新增：按 responseId 聚合随播播报的已展示文本，最终段播放完成时一次性落地
    private final Map<String, StringBuilder> playbackTextBuffers = new java.util.HashMap<>();

    /**
     * 构造函数：
     * - 初始化 TtsPlayer（内部自带播放器线程与 ExoPlayer）
     * - 设置音频属性与回调在 TtsPlayer 内部完成
     * - 初始化一次路由
     */
    @UnstableApi
    public VoiceChatClient(@NonNull Context context, @NonNull Listener l) {
        this.app = context.getApplicationContext();
        this.listener = l;
        this.audioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);

        // 初始化 TtsPlayer，并桥接“随播文本/最终落地”的行为
        this.ttsPlayer = new ChatTtsPlayer(app, new ChatTtsPlayer.Callback() {
            @Override
            public void onSegmentStart(@NonNull String requestId, int sequence, @NonNull String textDelta) {
                if (!textDelta.isEmpty()) {
                    StringBuilder buf = playbackTextBuffers.computeIfAbsent(requestId, k -> new StringBuilder());
                    buf.append(textDelta);
                    try { listener.onAssistantDelta(textDelta); } catch (Exception ignored) {}
                    int shown = playbackDisplayedIndex.getOrDefault(requestId, 0);
                    playbackDisplayedIndex.put(requestId, shown + textDelta.length());
                }
            }
            @Override
            public void onSegmentEnd(@NonNull String requestId, int sequence, boolean isFinalSegment, boolean canceled) {
                if (!canceled && isFinalSegment) {
                    StringBuilder buf = playbackTextBuffers.remove(requestId);
                    if (buf != null && buf.length() > 0) {
                        try { listener.onAssistantFinal(buf.toString().trim()); } catch (Exception ignored) {}
                    }
                    ttsManagedResponses.remove(requestId);
                    playbackDisplayedIndex.remove(requestId);
                }
            }
        });

        // 初始化传输层并桥接事件
        this.transport = new ChatTransport(new ChatTransport.Listener() {
            @Override public void onOpen() {
                isConnected = true;
                log("WebSocket 已打开");
            }
            @Override public void onTextMessage(@NonNull String text) {
                handleJsonMessage(text);
            }
            @Override public void onBinaryMessage(@NonNull byte[] bytes) {
                handleBinary(bytes);
            }
            @Override public void onClosed(int code, @NonNull String reason) {
                isConnected = false; isReady = false;
                log("WebSocket 已关闭 code="+code+" reason="+reason+"，将尝试自动重连");
                // 不回调 listener.onConnectionClosed()，避免上层触发 stopChat() 进而关闭重连
            }
            @Override public void onFailure(@NonNull Throwable t, @Nullable okhttp3.Response response) {
                isConnected = false; isReady = false;
                log("WebSocket 错误: "+t.getMessage()+"，将尝试自动重连");
                // 不回调 listener.onConnectionClosed()，避免上层触发 stopChat() 进而关闭重连
            }
            @Override public void onReconnectScheduled(int attempt, long delayMs) {
                log("计划第"+attempt+"次重连，延迟="+delayMs+"ms");
            }
        });

        // 初始化一次路由
        updateOutputRoute();
    }

    /**
     * 建立 WebSocket 连接。
     * - 成功 onOpen 后等待服务端下发 "ready" 事件才允许录音。
     */
    public void connect(@NonNull String url){
        // 启用自动重连并连接
        transport.setAutoReconnect(true);
        transport.setReconnectOnNormalClose(true);
        transport.connect(url);
        isReady = false;
        log("WebSocket 连接中 -> "+url);
    }

    /**
     * 断开连接并释放相关资源：
     * - 停止录音，清空 TTS 队列，清理对话缓存
     * - 关闭 WebSocket 与通信音频模式
     */
    public void disconnect(){
        stopRecording();
        clearTtsQueue();
        resetConversation();
        transport.close();
        isConnected=false; isReady=false;
        applyCommunicationAudioMode(false);
        // 用户主动断开才回调 onConnectionClosed
        listener.onConnectionClosed();
    }

    /**
     * 解析服务器 JSON 消息并分发到对应处理逻辑。
     * 协议关键点：
     * - ready：允许录音
     * - transcript：追加“你”的文本
     * - llm_delta：累计“大模型”增量
     * - llm_complete：落地“大模型”最终文本
     * - tts_start/tts_end：分段 TTS 拼接并入队串行播放
     * - tts_cancel：清空队列并打断当前播放
     */
    private void handleJsonMessage(String raw){
        try {
            JSONObject obj = new JSONObject(raw);
            String type = obj.optString("type");
            String r1 = obj.optString("responseId");
            String r2 = obj.optString("requestId");
            String responseId = !r1.isEmpty()? r1 : (!r2.isEmpty()? r2 : null);
            switch (type){
                case "ready":
                    isReady = true; log("服务器 ready"); listener.onReady();
                    if (autoStartOnReady) startRecording();
                    break;
                case "transcript": {
                    String text = obj.optString("sanitized", obj.optString("text"));
                    if (!text.isEmpty()) listener.onUserTranscript(text);
                    boolean isFinal = obj.optBoolean("final", obj.optBoolean("is_final", false));
                    if (isFinal && responseId != null) {
                        asrDoneAtMs.put(responseId, SystemClock.elapsedRealtime());
                    }
                    break; }
                case "llm_request": {
                    if (responseId != null) asrDoneAtMs.put(responseId, SystemClock.elapsedRealtime());
                    startNewResponse(responseId);
                    String userText = obj.optString("text");
                    if (!userText.isEmpty()) listener.onUserTranscript(userText);
                    log("LLM 请求中");
                    break; }
                case "llm_delta": {
                    String delta = obj.optString("content");
                    if (responseId != null && !delta.isEmpty()) {
                        StringBuilder sb = assistantBuffers.computeIfAbsent(responseId, k -> new StringBuilder());
                        sb.append(delta);
                    }
                    break; }
                case "llm_complete": {
                    if (responseId != null && ttsManagedResponses.contains(responseId)) {
                        log("LLM 完成(随播已接管)");
                    } else {
                        if (responseId != null) finalizeAssistantResponse(responseId, obj.optString("text"));
                        log("LLM 完成");
                    }
                    break; }
                case "tts_start": {
                    if (responseId != null) {
                        if (!responseId.equals(activeResponseId)) {
                            startNewResponse(responseId);
                        }
                        if (isActiveResponse(responseId)) {
                            int seq = obj.optInt("sequence");
                            boolean isFinalSeg = obj.optBoolean("isFinal", obj.optBoolean("final", false));
                            TtsStreamJob job = new TtsStreamJob(responseId, seq, isFinalSeg);
                            String provided = obj.optString("text", obj.optString("content", ""));
                            if (provided.isEmpty()) {
                                StringBuilder buf = assistantBuffers.get(responseId);
                                int shown = playbackDisplayedIndex.getOrDefault(responseId, 0);
                                if (buf != null && buf.length() > shown) {
                                    provided = buf.substring(shown);
                                }
                            }
                            job.textDelta = provided;
                            activeStreamJob = job;
                            ttsManagedResponses.add(responseId);
                            playbackTextBuffers.computeIfAbsent(responseId, k -> new StringBuilder());
                            log("tts_start r="+responseId+" s="+seq);
                        }
                    }
                    break;
                }
                case "tts_end": {
                    TtsStreamJob job = activeStreamJob;
                    int seq = obj.optInt("sequence");
                    if (job != null && responseId != null && responseId.equals(job.requestId) && seq == job.sequence) {
                        String reason = obj.optString("reason");
                        if ("completed".equals(reason) && !job.buffers.isEmpty()) {
                            byte[] merged = merge(job.buffers);
                            enqueueTts(job.requestId, job.sequence, merged, job.textDelta, job.isFinal);
                        } else {
                            log("TTS 未完成, reason="+reason);
                        }
                    }
                    activeStreamJob = null;
                    log("tts_end r="+responseId);
                    break;
                }
                case "tts_cancel": {
                    if (responseId != null) {
                        cancelTtsForResponse(responseId);
                        playbackTextBuffers.remove(responseId);
                        ttsManagedResponses.remove(responseId);
                        log("tts_cancel r="+responseId);
                    }
                    break;
                }
                default:
                    log("事件: "+type);
            }
        } catch (Exception e){ log("解析错误: "+e.getMessage()); }
    }

    /**
     * 累积 TTS 二进制音频块（通常为编码后音频，比如 mp3/opus/wav 等，需与播放器内 MIME 对齐）
     */
    private void handleBinary(byte[] bytes){
        TtsStreamJob job = activeStreamJob;
        if (job != null && isActiveResponse(job.requestId)) {
            if (!firstAudioReported.contains(job.requestId)) {
                Long start = asrDoneAtMs.get(job.requestId);
                long now = SystemClock.elapsedRealtime();
                if (start != null && now >= start) {
                    long latency = now - start;
                    listener.onFirstAudioLatency(latency);
                }
                firstAudioReported.add(job.requestId);
                asrDoneAtMs.remove(job.requestId);
            }
            job.buffers.add(bytes);
        }
    }

    /**
     * 判断 response 是否为当前“最新优先”的活跃响应。
     */
    private boolean isActiveResponse(@Nullable String responseId){
        if (responseId == null) return activeResponseId == null;
        return activeResponseId == null || responseId.equals(activeResponseId);
    }

    /**
     * 新一轮回复开始：旧的先落地并清空播放（最新优先）。
     */
    private void startNewResponse(@Nullable String responseId){
        if (responseId == null) return;
        if (activeResponseId != null) {
            asrDoneAtMs.remove(activeResponseId);
            firstAudioReported.remove(activeResponseId);
            finalizeAssistantResponse(activeResponseId, null);
            clearTtsQueue();
            playbackTextBuffers.remove(activeResponseId);
            ttsManagedResponses.remove(activeResponseId);
            playbackDisplayedIndex.remove(activeResponseId);
        }
        activeResponseId = responseId;
        assistantBuffers.computeIfAbsent(responseId, k -> new StringBuilder()).setLength(0);
        playbackDisplayedIndex.put(responseId, playbackDisplayedIndex.getOrDefault(responseId, 0));
    }

    /**
     * 完成回复：聚合最终文本并通知 UI。
     */
    private void finalizeAssistantResponse(@NonNull String responseId, @Nullable String finalText){
        StringBuilder sb = assistantBuffers.remove(responseId);
        String content = (finalText != null && !finalText.isEmpty()) ? finalText : (sb != null ? sb.toString() : "");
        if (!content.isEmpty()) listener.onAssistantFinal(content.trim());
        if (responseId.equals(activeResponseId)) activeResponseId = null;
    }

    /**
     * 入队一段 TTS 音频。
     */
    private void enqueueTts(@NonNull String requestId, int sequence, @NonNull byte[] audio, @NonNull String textDelta, boolean isFinalSegment){
        ttsPlayer.enqueue(requestId, sequence, audio, textDelta, isFinalSegment);
    }

    /** 取消某个 responseId 的所有 TTS，并清理随播状态 */
    private void cancelTtsForResponse(@NonNull String responseId){
        ttsPlayer.cancelForResponse(responseId);
        playbackTextBuffers.remove(responseId);
        ttsManagedResponses.remove(responseId);
        playbackDisplayedIndex.remove(responseId);
    }

    /** 清空 TTS 队列并停止播放 */
    private void clearTtsQueue(){
        ttsPlayer.clear();
    }

    /**
     * 开始录音并将 PCM16 流式发送至服务器。
     */
    public void startRecording(){
        if (!isConnected || !isReady) return;
        if (micRecorder != null && micRecorder.isRunning()) return;
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log("缺少 RECORD_AUDIO 权限，无法开始录音");
            return;
        }
        applyCommunicationAudioMode(true);
        if (micRecorder == null) micRecorder = new MicRecorder(app);
        boolean ok = micRecorder.start(SAMPLE_RATE, new MicRecorder.Callback() {
            @Override public void onPcmData(@NonNull byte[] buffer, int length) {
                if (isConnected && isReady) {
                    try { transport.sendBinary(buffer, 0, length); } catch (Exception ignored) {}
                }
            }
            @Override public void onLog(@NonNull String line) { log(line); }
        });
        if (ok) {
            log("开始录音 16kHz PCM16");
        }
        listener.onRecordingReady();
    }

    /**
     * 停止录音并释放资源。
     */
    public void stopRecording(){
        if (micRecorder != null) {
            try { micRecorder.stop(); } catch (Exception ignored) {}
        }
        applyCommunicationAudioMode(false);
        log("停止录音");
        listener.onRecordingStopped();
    }

    /**
     * 切换系统音频模式与输出路由。
     */
    private void applyCommunicationAudioMode(boolean enable){
        try {
            if (audioManager == null) return;
            if (enable && !commModeApplied) {
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                updateOutputRoute();
                commModeApplied = true;
            } else if (!enable && commModeApplied) {
                audioManager.setSpeakerphoneOn(false);
                audioManager.setMode(AudioManager.MODE_NORMAL);
                commModeApplied = false;
            }
        } catch (Exception ignored) {}
    }

    private void updateOutputRoute(){
        try {
            if (audioManager == null) return;
            boolean hasHeadset = hasAnyHeadsetOrBt();
            if (hasHeadset) {
                audioManager.setSpeakerphoneOn(false);
            }
        } catch (Exception ignored) {}
    }

    private boolean hasAnyHeadsetOrBt(){
        try {
            if (audioManager == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioDeviceInfo[] outs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                for (AudioDeviceInfo d : outs) {
                    int t = d.getType();
                    if (t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                            || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                            || t == AudioDeviceInfo.TYPE_USB_HEADSET
                            || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                            || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                        return true;
                    }
                }
                return false;
            } else {
                //noinspection deprecation
                return audioManager.isWiredHeadsetOn()
                        //noinspection deprecation
                        || audioManager.isBluetoothScoOn()
                        //noinspection deprecation
                        || audioManager.isBluetoothA2dpOn();
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static byte[] merge(List<byte[]> list){
        int total = 0; for (byte[] a : list) total += a.length;
        ByteBuffer out = ByteBuffer.allocate(total);
        for (byte[] a : list) out.put(a);
        return out.array();
    }

    private void resetConversation(){
        assistantBuffers.clear();
        activeResponseId = null;
        asrDoneAtMs.clear();
        firstAudioReported.clear();
        playbackTextBuffers.clear();
        ttsManagedResponses.clear();
        playbackDisplayedIndex.clear();
    }

    private void log(String s){ listener.onLog(s); }

    /**
     * 释放播放器相关资源（在页面销毁时调用）。
     */
    public void release() {
        try { ttsPlayer.release(); } catch (Exception ignored) {}
        try { transport.release(); } catch (Exception ignored) {}
        try { if (micRecorder != null) micRecorder.release(); } catch (Exception ignored) {}
    }

    public void startChat(@NonNull String url) {
        autoStartOnReady = true;
        connect(url);
    }

    // 一键结束：停止录音并断开
    public void stopChat() {
        autoStartOnReady = false;
        disconnect();
    }

}
