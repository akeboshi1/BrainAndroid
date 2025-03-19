package com.jujie.audiosdk;


import static com.jujie.audiosdk.Constant.REQUEST_RECORD_AUDIO_ASR_PERMISSION;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import com.cocos.lib.JsbBridge;

import java.util.Map;

public class ASRManager {

    private static ASRManager manager = null;

    private AudioRecord audioRecord;
    private int bufferSize;
    private boolean isRecording;
    private Thread worker;
    private boolean connected = false;

    public ASRManager() {
//        this.context = context;
        bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    }

    public static void start(Context context, Map args) {
        if (manager == null) {
            manager = new ASRManager();
        }
        manager.startRecording(context, args);
    }

    public static void close() {
        manager.closeASR();
    }

    public void startRecording(Context context, Map args) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // 请求权限
            ActivityCompat.requestPermissions((android.app.Activity) context, new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO_ASR_PERMISSION);
            return;
        }

        if (connected) {
            return;
        }
        connected = true;
        Log.d("ASR", "startRecording");

        Log.d("ASR", "record starting");
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        audioRecord.startRecording();
        isRecording = true;

        Log.d("ASR", "worker starting");

        ASRWebSocket.getInstance().connectWS(args);

        new Thread(() -> {
            byte[] buffer = new byte[bufferSize];
            while (isRecording) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    // 调用 websocket 发送音频帧
                    ASRWebSocket.getInstance().sendAudioFrame(buffer);
                }
            }
        }).start();

//        this.worker.setDaemon(true);
        Log.d("ASR", "thread start");
//        worker.start();
    }

    public void stopRecording() {
        isRecording = false;
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
    }

    public void closeASR() {
        if (!connected) {
            return;
        }
        connected = false;
        this.stopRecording();
        this.isRecording = false;
        ASRWebSocket.getInstance().closeWS();
        JsbBridge.sendToScript("ASRClosed");
    }
}