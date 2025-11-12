package com.jujie.paipai.chat;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media3.common.util.UnstableApi;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天客户端：管理连接、录音、文本增量、TTS 播放与随播，以及歌曲播放。
 */
public class VoiceChatClient {

    public interface Listener {
        void onReady();
        void onCharacterSwitched();
        void onLimitExceeded();
        void onLog(@NonNull String line);
        void onUserTranscript(@NonNull String text);
        void onAssistantFinal(@NonNull String text);
        void onConnectionClosed();
        void onFirstAudioLatency(long millis);
        void onAssistantDelta(@NonNull String text);
        void onRecordingReady();
        void onRecordingStopped();

        void onModeSwitched(@NonNull String mode, JSONObject params);
        // 新增：歌曲事件回调（默认空实现，避免破坏兼容）
         void onSongStart(int id, @NonNull String name) ;
         void onSongStop() ;
         void onSongResume() ;
         void onSongEnd(int id, @NonNull String name) ;
    }

    // 新增：客户端模式
    private enum Mode { CHAT, SONG }

    private final Context app;
    private final Listener listener;

    private final AudioManager audioManager;
    private boolean commModeApplied = false;

    private boolean enableAsr = true;

    private final ChatTransport transport;

    private boolean isConnected = false;
    private boolean isReady = false;
    private boolean isMicReady = false;
    private boolean isSwitchingMode = false;

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

    // 新增：歌曲相关状态
    private Mode mode = Mode.CHAT;
    private @Nullable String currentSongId = null;
    private int currentSongUid = 0;
    private @Nullable String currentSongName = null;
    private int currentSongSeq = 0;
    private boolean songPaused = false;
    private final Set<String> processedSongChunks = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // 添加缓冲区
    private final Map<String, ByteArrayOutputStream> songBuffers = new ConcurrentHashMap<>();
    private static final int SONG_BUFFER_THRESHOLD = 2 * 1024 * 1024; // 2MB

    // 防抖/校验：仅当收到当前歌曲音频后，才接受 song_end；每首歌只入队一次结束标记
    private volatile boolean currentSongAudioReceived = false;
    private volatile boolean currentSongEndQueued = false;
    private final Set<String> notifiedSongEnd = Collections.newSetFromMap(new ConcurrentHashMap<>());


    @UnstableApi
    public VoiceChatClient(@NonNull Context context, @NonNull Listener l) {
        this.app = context.getApplicationContext();
        this.listener = l;
        this.audioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        // 增加一个麦克风状态
        this.isMicReady = false;

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

            @Override
            public void onQueueIdle() {
                if (mode == Mode.SONG) {
                    log("歌曲播放完成，音频队列已空");
//                    try { listener.onSongEnd(currentSongName); } catch (Exception ignored) {}

                    // 仅停止继续播放，不切回聊天；如需切回聊天可改为：
                    // stopSongPlayback(true); // true 表示重置并切回 Mode.CHAT
                    // 这里我们选择“留在 SONG 模式等待下一首或用户操作”
                }
            }

            @Override
            public void onSongEndMarker(@NonNull String requestId) {
                Log.d("VoiceChatClient", "onSongEndMarker: currentSongId=" + currentSongId +
                        ", currentSongUid=" + currentSongUid +
                        ", currentSongName=" + currentSongName + ", requestId=" + requestId +
                        ", mode=" + mode);

                if (mode == Mode.SONG && requestId.equals(currentSongId)) {
                    // 去重：同一首歌只触发一次
                    if (!notifiedSongEnd.add(requestId)) {
                        Log.w("VoiceChatClient", "忽略重复歌曲结束标记: " + requestId);
                        return;
                    }
                    log("收到歌曲结束标记，触发歌曲结束事件: " + currentSongName);
                    Log.d("VoiceChatClient","----------------------------------------->"+currentSongName+", " + currentSongUid);
                    try {
                        listener.onSongEnd(currentSongUid, currentSongName != null ? currentSongName : "未知歌曲");
                    } catch (Exception ignored) {}
                    // 注意：这里我们不清除歌曲状态，因为可能还需要在onSongComplete中处理，或者由外部切换模式
                    // 我们只是通知歌曲完成，但不自动切换模式，由调用者决定后续行为
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
        // 退出时重置歌曲状态
        stopSongPlayback(true);
        transport.close();
        isConnected=false; isReady=false;
        applyCommunicationAudioMode(false);
        listener.onConnectionClosed();
    }

    public void setEnableAsr(boolean enable){
        this.enableAsr = enable;
    }

    public void setSwitchingMode(boolean switching){
        this.isSwitchingMode = switching;
    }

    public boolean isSwitchingMode(){
        return this.isSwitchingMode;
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
                    isReady = true; log("服务器 ready");
                    if(isSwitchingMode){
                        listener.onCharacterSwitched();
                        isSwitchingMode = false;
                    }else{
                        listener.onReady();
                    }
                    Log.d("VoiceChatClient", "handleJsonMessage: ready received, autoStartOnReady=" + autoStartOnReady);
                    if (autoStartOnReady && enableAsr) startRecording();
                    break;
                case "limit_exceeded":
                    Log.d("VoiceChatClient", "handleJsonMessage: limit_exceeded received");
                    listener.onLimitExceeded();
                    stopChat();
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
                    break;
                }
                case "llm_delta": {
                    String delta = obj.optString("content");
                    if (responseId != null && !delta.isEmpty()) {
                        StringBuilder sb = getOrCreateStringBuilder(assistantBuffers, responseId);
                        sb.append(delta);
                    }
                    break;
                }
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
                    break;
                }
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
                        if ("completed".equals(reason)) {
                            // 优先以 tts_end 携带的 isFinal/final 为准，若缺失再回退到 tts_start 的标志
                            boolean isFinalSeg = obj.has("isFinal") || obj.has("final")
                                    ? obj.optBoolean("isFinal", obj.optBoolean("final", false))
                                    : job.isFinal;
                            if (!job.buffers.isEmpty()) {
                                byte[] merged = merge(job.buffers);
                                // 这里传入最终段标志，确保单段语音也能触发 onAssistantFinal
                                enqueueTts(job.requestId, job.sequence, merged, job.textDelta, isFinalSeg);
                            } else {
                                // 无音频块的完成：直接按文本结束，避免遗漏 onSegmentEnd
                                if (isFinalSeg) {
                                    finalizeAssistantResponse(job.requestId, job.textDelta);
                                } else if (job.textDelta != null && !job.textDelta.isEmpty()) {
                                    try { listener.onAssistantDelta(job.textDelta); } catch (Exception ignored) {}
                                }
                                ttsManagedResponses.remove(job.requestId);
                                playbackTextBuffers.remove(job.requestId);
                                playbackDisplayedIndex.remove(job.requestId);
                            }
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
                case "song_end": {
                    if (mode == Mode.SONG && currentSongId != null) {
                        // 如服务端带 songId，做严格校验；否则仅在已收到当前歌曲音频后才接受此结束
                        int serverSongId = obj.has("songId") ? obj.optInt("songId", -1) : -1;
                        int expected = currentSongUid & 0xFFFF;
                        if (serverSongId != -1 && (serverSongId & 0xFFFF) != expected) {
                            Log.w("VoiceChatClient", "忽略非当前歌曲的结束信号 serverSongId="+serverSongId+", expected="+expected);
                            break;
                        }
                        if (!currentSongAudioReceived) {
                            Log.w("VoiceChatClient", "忽略过期/抢先到达的歌曲结束信号（当前歌曲尚未收到音频）: " + currentSongName + "," + currentSongUid);
                            break;
                        }
                        if (currentSongEndQueued) {
                            Log.w("VoiceChatClient", "忽略重复歌曲结束信号: " + currentSongName + "," + currentSongUid);
                            break;
                        }

                        log("收到歌曲结束信号: " + currentSongName+","+currentSongUid);
                        // 清空缓冲区，发送剩余音频
                        flushSongBuffer(currentSongId);
                        // 在队列中添加结束标识
                        enqueueTts(currentSongId, currentSongSeq++, new byte[0], "", true);
                        currentSongEndQueued = true;
                    }
                    break;

//                    if (mode == Mode.SONG && currentSongId != null) {
//                        log("收到歌曲结束信号: " + currentSongName);
//                        // 在队列中添加结束标识
//                        enqueueTts(currentSongId, currentSongSeq++, new byte[0], "", true);
//                    }
//                    break;
                }
                default:
                    log("事件: "+type);
            }
        } catch (Exception e){ log("解析错误: "+e.getMessage()); }
    }

    private void handleBinary(byte[] bytes){
        // 新增：歌曲模式下直接处理歌曲流
        if (mode == Mode.SONG) {
            handleSongBinary(bytes);
            return;
        }

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
            this.enableAsr = true;
            this.transport.setEnableAsr(true);
            // 启动asr
            transport.sendText("{\"type\":\"start_asr\"}");
        }
    }

    public void stopRecording(){
        if (micRecorder != null) {
            try { micRecorder.stop(); } catch (Exception ignored) {}
        }
        applyCommunicationAudioMode(false);
        log("停止录音");
//        this.enableAsr = false;
        this.transport.setEnableAsr(false);
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
        // 重置模式
        mode = Mode.CHAT;
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

    public void stopChatSilently(){
        Log.d("VoiceChatClient", "stopChatSilently called");
        stopRecording();
        clearTtsQueue();
        resetConversation();
        // 退出时重置歌曲状态
        stopSongPlayback(true);
        transport.close();
        isConnected=false; isReady=false;
        applyCommunicationAudioMode(false);
    }

    public boolean isTransportConnected(){
         Log.d("VoiceChatClient", "isTransportConnected: " + isConnected);
         return transport.isConnected();
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

    // ====== 歌曲相关：模式切换与数据处理 ======
    public void switchMode(@NonNull String modeStr, JSONObject params) {
        if (modeStr.equalsIgnoreCase("chat")) {
            switchToChat();
            try { listener.onModeSwitched(modeStr.toLowerCase(), params); } catch (Exception ignored) {}
        } else if (modeStr.equalsIgnoreCase("song")) {
            String songName = params.optString("songName", "未知歌曲");
            int songId = params.optInt("songId", 0);
            switchToSong(null,songId, songName);
            try { listener.onModeSwitched(modeStr.toLowerCase(), params); } catch (Exception ignored) {}
        }
    }

    private void switchToSong(@Nullable String responseId,int songId, @NonNull String songName) {
        Log.d("VoiceChatClient", "switchToSong: 开始设置歌曲状态, songName=" + songName);

        // 切歌时彻底清理旧缓冲/状态
        songBuffers.clear();
        processedSongChunks.clear();
        notifiedSongEnd.clear();
        currentSongAudioReceived = false;
        currentSongEndQueued = false;

        currentSongId = "song-" + SystemClock.elapsedRealtime();
        currentSongUid = songId;

        // 停止录音与清空所有音频（语音/歌曲）
        stopRecording();
        clearTtsQueue();

        this.transport.sendText("{\"type\":\"song\", \"songName\":\""+songName+"\", \"songId\":"+songId+"}");

        // 取消当前 TTS 响应（如有）
        if (activeResponseId != null) {
            try { cancelTtsForResponse(activeResponseId); } catch (Exception ignored) {}
            activeResponseId = null;
        }
        // 切歌时重置歌曲状态
//        currentSongId = responseId != null ? responseId : ("song-" + SystemClock.elapsedRealtime());
        currentSongName = songName;
        currentSongSeq = 0;
        songPaused = false;
        mode = Mode.SONG;
        log("播放歌曲: " + songName + " id=" + currentSongId);
        Log.d("VoiceChatClient", "播放歌曲: " + songName + " id=" + currentSongId);
        this.ttsPlayer.resume();

        try { listener.onSongStart(songId, songName); } catch (Exception ignored) {}
    }

    public void pauseSong() {
        if (mode != Mode.SONG) return;
        if (!songPaused) {
            songPaused = true;
            // 仅暂停播放器，不清队列，不缓存流
            try { ttsPlayer.pause(); } catch (Exception ignored) {}
            try { listener.onSongStop(); } catch (Exception ignored) {}
            log("歌曲暂停");
        }
    }

    public void resumeSong() {
        Log.d("VoiceChatClient", "resumeSong called mode: " + mode + " songPaused: " + songPaused + " currentSongId: " + currentSongId);

        if (mode != Mode.SONG) return;
        if (songPaused) {
            songPaused = false;
            // 恢复播放器继续播放
            try { ttsPlayer.resume(); } catch (Exception ignored) {}
            try { listener.onSongResume(); } catch (Exception ignored) {}
            log("歌曲继续");
        }
    }

    public void switchToChat() {
        if (mode == Mode.SONG) {
            // 停止之前歌曲播放
            stopSongPlayback(false);
            mode = Mode.CHAT;
            this.transport.sendText("{\"type\":\"chat\"}");

            log("切回聊天");
            Log.d("VoiceChatClient", "switchToChat called enableAsr: " + enableAsr + " isConnected: " + isConnected + " isReady: " + isReady);
            // 回到聊天后，根据 enableAsr 决定是否开始录音
            if (enableAsr) {
                listener.onRecordingReady();
                startRecording();
            }
        }
    }

    private void handleSongBinary(@NonNull byte[] bytes) {
        if (currentSongId == null) {
            // 异常：未收到 play_song 但来了二进制，兜底创建一个歌曲会话
            currentSongId = "song-" + SystemClock.elapsedRealtime();
            currentSongName = "";
            currentSongUid = 0;
            currentSongSeq = 0;
            songPaused = false;
        }

        // 歌曲流必须包含2字节songId头
        if (bytes.length < 2) {
            Log.w("VoiceChatClient", "收到无效歌曲二进制数据（长度不足2字节），丢弃");
            return;
        }

        // 解析前2字节的大端songId，并与当前歌曲ID比对（按UInt16范围）
        int headerSongId = ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
        int expectedSongId = currentSongUid & 0xFFFF;
        Log.d("VoiceChatClient", "handleSongBinary: headerSongId=" + headerSongId + ", expectedSongId=" + expectedSongId + ", currentSongUid=" + currentSongUid);

        if (headerSongId != expectedSongId) {
            Log.w("VoiceChatClient", "丢弃非当前歌曲片段，headerSongId=" + headerSongId + ", expected=" + expectedSongId + ", currentSongUid=" + currentSongUid);
            return;
        }

        // 去掉2字节头部，保留真实音频体
        byte[] body = Arrays.copyOfRange(bytes, 2, bytes.length);
        if (body.length == 0) {
            Log.w("VoiceChatClient", "歌曲音频体为空，丢弃");
            return;
        }

        // 标记：当前歌曲已收到音频
        currentSongAudioReceived = true;

        // 基于真实音频体做去重
        String chunkKey = currentSongId + "-" + Arrays.hashCode(body);
        if (!processedSongChunks.add(chunkKey)) {
            Log.w("VoiceChatClient", "检测到重复的歌曲数据块（已去除头部后），跳过: " + chunkKey);
            return;
        }

        // 获取或创建缓冲区
        ByteArrayOutputStream buffer = songBuffers.get(currentSongId);
        if (buffer == null) {
            buffer = new ByteArrayOutputStream();
            songBuffers.put(currentSongId, buffer);
        }

        try {
            buffer.write(body);

            // 当缓冲区达到阈值时发送
            if (buffer.size() >= SONG_BUFFER_THRESHOLD) {
                byte[] mergedData = buffer.toByteArray();
                enqueueTts(currentSongId, currentSongSeq++, mergedData, "", false);
                buffer.reset(); // 清空缓冲区
            }
        } catch (IOException e) {
            Log.e("VoiceChatClient", "歌曲缓冲区写入失败", e);
        }

    }
    // 在歌曲结束时清空缓冲区
    private void flushSongBuffer(String songId) {
        ByteArrayOutputStream buffer = songBuffers.remove(songId);
        if (buffer != null && buffer.size() > 0) {
            byte[] remainingData = buffer.toByteArray();
            enqueueTts(songId, currentSongSeq++, remainingData, "", false);
        }
    }

    private void stopSongPlayback(boolean resetState) {
        try { ttsPlayer.cancelForResponse(currentSongId != null ? currentSongId : ""); } catch (Exception ignored) {}
        try { ttsPlayer.clear(); } catch (Exception ignored) {}
        // 清理当前歌曲缓冲
        if (currentSongId != null) {
            songBuffers.remove(currentSongId);
        }
        currentSongAudioReceived = false;
        currentSongEndQueued = false;
        if (resetState) {
            currentSongId = null;
            currentSongName = null;
            currentSongUid = 0;
            currentSongSeq = 0;
            songPaused = false;
            mode = Mode.CHAT;
            notifiedSongEnd.clear();
        }
    }
}
