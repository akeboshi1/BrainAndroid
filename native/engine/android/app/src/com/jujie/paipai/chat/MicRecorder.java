package com.jujie.paipai.chat;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * MicRecorder 负责麦克风采集和增强（AEC/NS/AGC）以及读流线程管理。
 * 使用约定：onPcmData 的回调在录音线程同步触发，缓冲区可复用，若需要跨线程保存请自行拷贝。
 */
public final class MicRecorder {

    public interface Callback {
        void onPcmData(@NonNull byte[] buffer, int length);
        default void onLog(@NonNull String line) {}
    }

    private final Context app;
    private @Nullable AudioRecord audioRecord;
    private @Nullable Thread audioThread;
    private volatile boolean isRecording = false;

    // 录音增强
    private @Nullable AcousticEchoCanceler aec;
    private @Nullable NoiseSuppressor ns;
    private @Nullable AutomaticGainControl agc;

    public MicRecorder(@NonNull Context context) {
        this.app = context.getApplicationContext();
    }

    public synchronized boolean start(int sampleRate, @NonNull Callback cb) {
        if (isRecording) return false;
        int minBuf = AudioRecord.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            cb.onLog("AudioRecord buffer 无效");
            return false;
        }
        AudioRecord ar = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf
        );
        if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
            cb.onLog("AudioRecord 初始化失败");
            try { ar.release(); } catch (Exception ignored) {}
            return false;
        }
        enableRecordEffects(ar.getAudioSessionId());
        try {
            ar.startRecording();
        } catch (Exception e) {
            cb.onLog("startRecording 异常: "+e.getMessage());
            try { ar.release(); } catch (Exception ignored) {}
            disableRecordEffects();
            return false;
        }
        audioRecord = ar;
        isRecording = true;
        audioThread = new Thread(() -> {
            byte[] buf = new byte[minBuf];
            while (isRecording) {
                int read = 0;
                try {
                    read = ar.read(buf, 0, buf.length);
                } catch (Exception e) {
                    cb.onLog("AudioRecord 读取异常: "+e.getMessage());
                    break;
                }
                if (read > 0) {
                    cb.onPcmData(buf, read);
                }
            }
        }, "mic-recorder-thread");
        audioThread.start();
        cb.onLog("MicRecorder 已开始 采样率="+sampleRate);
        return true;
    }

    public synchronized void stop() {
        isRecording = false;
        Thread t = audioThread; audioThread = null;
        if (t != null) { try { t.interrupt(); } catch (Exception ignored) {} }
        AudioRecord ar = audioRecord; audioRecord = null;
        if (ar != null) {
            try { ar.stop(); } catch (Exception ignored) {}
            try { ar.release(); } catch (Exception ignored) {}
        }
        disableRecordEffects();
    }

    public boolean isRunning() { return isRecording; }

    public void release() { stop(); }

    private void enableRecordEffects(int sessionId){
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId);
                if (aec != null) aec.setEnabled(true);
            }
        } catch (Exception ignored) {}
        try {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId);
                if (ns != null) ns.setEnabled(true);
            }
        } catch (Exception ignored) {}
        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId);
                if (agc != null) agc.setEnabled(true);
            }
        } catch (Exception ignored) {}
    }

    private void disableRecordEffects(){
        try { if (aec != null) { aec.setEnabled(false); aec.release(); } } catch (Exception ignored) {}
        try { if (ns != null) { ns.setEnabled(false); ns.release(); } } catch (Exception ignored) {}
        try { if (agc != null) { agc.setEnabled(false); agc.release(); } } catch (Exception ignored) {}
        aec = null; ns = null; agc = null;
    }
}

