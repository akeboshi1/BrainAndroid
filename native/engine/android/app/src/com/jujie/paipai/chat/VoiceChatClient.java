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
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天客户端：管理连接、录音、文本增量、TTS 播放与随播。
 */
public class VoiceChatClient {

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

    private final Context app;
    private final Listener listener;

    private final AudioManager audioManager;
    private boolean commModeApplied = false;

    private final ChatTransport transport;

    private boolean isConnected = false;
    private boolean isReady = false;

    private final ChatTtsPlayer ttsPlayer;
    private @Nullable MicRecorder micRecorder;

    private static final int SAMPLE_RATE = 16000;

    private static class TtsStreamJob {
        final String requestId; final int sequence; final boolean isFinal;
        final List<byte[]> buffers = new ArrayList<>();
        String textDelta = "";
        TtsStreamJob(String r, int s, boolean f){ requestId=r; sequence=s; isFinal=f; }
    }
    private @Nullable TtsStreamJob activeStreamJob;

    private volatile boolean autoStartOnReady = false;

    private final Map<String, Long> asrDoneAtMs = new java.util.HashMap<>();
    private final Set<String> firstAudioReported = new java.util.HashSet<>();

    private final Map<String, StringBuilder> assistantBuffers = new java.util.HashMap<>();
    private @Nullable String activeResponseId = null;
    private final Map<String, Integer> playbackDisplayedIndex = new java.util.HashMap<>();
    private final Set<String> ttsManagedResponses = new java.util.HashSet<>();
    private final Map<String, StringBuilder> playbackTextBuffers = new java.util.HashMap<>();

    private final Set<String> finalizedResponses = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @UnstableApi
    public VoiceChatClient(@NonNull Context context, @NonNull Listener l) {
        this.app = context.getApplicationContext();
        this.listener = l;
        this.audioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);

        this.ttsPlayer = new ChatTtsPlayer(app, new ChatTtsPlayer.Callback() {
            @Override
            public void onSegmentStart(@NonNull String requestId, int sequence, @NonNull String textDelta) {
                if (!textDelta.isEmpty()) {
                    StringBuilder buf = getOrCreateStringBuilder(playbackTextBuffers, requestId);
                    buf.append(textDelta);
                    try { listener.onAssistantDelta(textDelta); } catch (Exception ignored) {}
                    int shown = getOrDefaultCompat(playbackDisplayedIndex, requestId, 0);
                    playbackDisplayedIndex.put(requestId, shown + textDelta.length());
                }
            }
            @Override
            public void onSegmentEnd(@NonNull String requestId, int sequence, boolean isFinalSegment, boolean canceled) {
                if (!canceled && isFinalSegment) {
                    StringBuilder buf = playbackTextBuffers.remove(requestId);
                    String content = (buf != null) ? buf.toString().trim() : "";
                    if (!content.isEmpty()) {
                        finalizeAssistantResponse(requestId, content);
                    }
                    ttsManagedResponses.remove(requestId);
                    playbackDisplayedIndex.remove(requestId);
                }
            }
        });

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
            }
            @Override public void onFailure(@NonNull Throwable t, @Nullable okhttp3.Response response) {
                isConnected = false; isReady = false;
                log("WebSocket 错误: "+t.getMessage()+"，将尝试自动重连");
            }
            @Override public void onReconnectScheduled(int attempt, long delayMs) {
                log("计划第"+attempt+"次重连，延迟="+delayMs+"ms");
            }
        });

        updateOutputRoute();
    }

    public void connect(@NonNull String url){
        transport.setAutoReconnect(true);
        transport.setReconnectOnNormalClose(true);
        transport.connect(url);
        isReady = false;
        log("WebSocket 连接中 -> "+url);
    }

    public void disconnect(){
        stopRecording();
        clearTtsQueue();
        resetConversation();
        transport.close();
        isConnected=false; isReady=false;
        applyCommunicationAudioMode(false);
        listener.onConnectionClosed();
    }

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
                        StringBuilder sb = getOrCreateStringBuilder(assistantBuffers, responseId);
                        sb.append(delta);
                    }
                    break; }
                case "llm_complete": {
                    if (responseId != null && ttsManagedResponses.contains(responseId)) {
                        log("LLM 完成(随播已接管)");
                    } else {
                        if (responseId != null) {
                            String finalText = obj.optString("text", obj.optString("content", ""));
                            finalizeAssistantResponse(responseId, finalText);
                        }
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
                                int shown = getOrDefaultCompat(playbackDisplayedIndex, responseId, 0);
                                if (buf != null && buf.length() > shown) {
                                    provided = buf.substring(shown);
                                }
                            }
                            job.textDelta = provided;
                            activeStreamJob = job;
                            ttsManagedResponses.add(responseId);
                            getOrCreateStringBuilder(playbackTextBuffers, responseId);
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

    private boolean isActiveResponse(@Nullable String responseId){
        if (responseId == null) return activeResponseId == null;
        return activeResponseId == null || responseId.equals(activeResponseId);
    }

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
        getOrCreateStringBuilder(assistantBuffers, responseId).setLength(0);
        playbackDisplayedIndex.put(responseId, 0);
    }

    private void finalizeAssistantResponse(@NonNull String responseId, @Nullable String finalText){
        boolean first = finalizedResponses.add(responseId);
        StringBuilder sb = assistantBuffers.remove(responseId);
        String content = (finalText != null && !finalText.isEmpty()) ? finalText : (sb != null ? sb.toString() : "");
        if (first && !content.isEmpty()) listener.onAssistantFinal(content.trim());
        if (responseId.equals(activeResponseId)) activeResponseId = null;
    }

    private void enqueueTts(@NonNull String requestId, int sequence, @NonNull byte[] audio, @NonNull String textDelta, boolean isFinalSegment){
        ttsPlayer.enqueue(requestId, sequence, audio, textDelta, isFinalSegment);
    }

    private void cancelTtsForResponse(@NonNull String responseId){
        ttsPlayer.cancelForResponse(responseId);
        playbackTextBuffers.remove(responseId);
        ttsManagedResponses.remove(responseId);
        playbackDisplayedIndex.remove(responseId);
    }

    private void clearTtsQueue(){
        ttsPlayer.clear();
    }

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
            listener.onRecordingReady();
        }
    }

    public void stopRecording(){
        if (micRecorder != null) {
            try { micRecorder.stop(); } catch (Exception ignored) {}
        }
        applyCommunicationAudioMode(false);
        log("停止录音");
        listener.onRecordingStopped();
    }

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
        finalizedResponses.clear();
    }

    private void log(String s){ listener.onLog(s); }

    public void release() {
        try { ttsPlayer.release(); } catch (Exception ignored) {}
        try { transport.release(); } catch (Exception ignored) {}
        try { if (micRecorder != null) micRecorder.release(); } catch (Exception ignored) {}
    }

    public void startChat(@NonNull String url) {
        autoStartOnReady = true;
        connect(url);
    }

    public void stopChat() {
        autoStartOnReady = false;
        disconnect();
    }

    private static <K, V> V getOrDefaultCompat(Map<K, V> map, K key, V def) {
        V v = map.get(key);
        return v != null ? v : def;
    }

    private static <K> StringBuilder getOrCreateStringBuilder(Map<K, StringBuilder> map, K key) {
        StringBuilder sb = map.get(key);
        if (sb == null) {
            sb = new StringBuilder();
            map.put(key, sb);
        }
        return sb;
    }
}

