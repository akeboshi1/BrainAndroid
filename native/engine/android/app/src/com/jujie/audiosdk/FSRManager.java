package com.jujie.audiosdk;

import static com.jujie.audiosdk.Constant.REQUEST_RECORD_AUDIO_FSR_PERMISSION;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import com.cocos.lib.JsbBridge;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONException;
import org.json.JSONObject;

public class FSRManager {

    private static final String TAG = "FSRManager";
    private static final String FSR_URL = "https://test.paipai.xinjiaxianglao.com/fsr/";
    private static FSRManager manager = null;

    private AudioRecord audioRecord;
    private int bufferSize;
    private boolean isRecording;
    private Thread worker;
    private boolean started = false;
    private ByteArrayOutputStream audioData; // 用于保存录音数据
    private ExecutorService executorService;
    private OkHttpClient client;

    public FSRManager() {
        bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioData = new ByteArrayOutputStream();
        executorService = Executors.newSingleThreadExecutor();
        client = new OkHttpClient();
    }

    public static void start(Context context) {
        if (manager == null) {
            manager = new FSRManager();
        }
        manager.startRecording(context);
    }

    public static void stop() {
        if (manager != null) {
            manager.stopAndUpload();
        }
    }

    public void startRecording(Context context) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // 请求权限
            ActivityCompat.requestPermissions((android.app.Activity) context, new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO_FSR_PERMISSION);
            return;
        }

        if (started) {
            return;
        }
        started = true;
        Log.d(TAG, "startRecording");

        // 重置音频数据流
        audioData.reset();

        Log.d(TAG, "record starting");
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        audioRecord.startRecording();
        isRecording = true;

        Log.d(TAG, "worker starting");

        worker = new Thread(() -> {
            byte[] buffer = new byte[bufferSize];
            while (isRecording) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    // 保存音频数据
                    synchronized (audioData) {
                        audioData.write(buffer, 0, read);
                    }
                }
            }
        });

        worker.start();
        Log.d(TAG, "Recording thread started");
    }

    // 停止录音并上传音频文件
    public void stopAndUpload() {
        if (!isRecording || audioRecord == null) {
            Log.d(TAG, "Not recording, cannot stop");
            return;
        }

        Log.d(TAG, "Stopping recording");
        isRecording = false;

        // 确保录音线程停止
        try {
            if (worker != null && worker.isAlive()) {
                worker.join(1000); // 等待最多1秒
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while waiting for recording thread", e);
        }

        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }

        // 获取录音数据
        byte[] audioBytes;
        synchronized (audioData) {
            audioBytes = audioData.toByteArray();
            audioData.reset();
        }

        if (audioBytes.length == 0) {
            Log.e(TAG, "No audio data recorded");
            return;
        }

        // 创建WAV格式的音频数据
        byte[] wavBytes = createWavFile(audioBytes);

        // 上传音频文件
        uploadAudio(wavBytes);

        started = false;
    }

    // 创建WAV文件格式
    private byte[] createWavFile(byte[] audioData) {
        int sampleRate = 16000;
        int channels = 1;
        int bitsPerSample = 16;
        int dataSize = audioData.length;
        int headerSize = 44;
        int totalSize = headerSize + dataSize;

        ByteArrayOutputStream out = new ByteArrayOutputStream(totalSize);

        try {
            // RIFF header
            writeString(out, "RIFF"); // ChunkID
            writeInt(out, 36 + dataSize); // ChunkSize
            writeString(out, "WAVE"); // Format

            // fmt subchunk
            writeString(out, "fmt "); // Subchunk1ID
            writeInt(out, 16); // Subchunk1Size
            writeShort(out, (short) 1); // AudioFormat (1 = PCM)
            writeShort(out, (short) channels); // NumChannels
            writeInt(out, sampleRate); // SampleRate
            writeInt(out, sampleRate * channels * bitsPerSample / 8); // ByteRate
            writeShort(out, (short) (channels * bitsPerSample / 8)); // BlockAlign
            writeShort(out, (short) bitsPerSample); // BitsPerSample

            // data subchunk
            writeString(out, "data"); // Subchunk2ID
            writeInt(out, dataSize); // Subchunk2Size

            // Audio data
            out.write(audioData);

            return out.toByteArray();
        } catch (IOException e) {
            Log.e(TAG, "Error creating WAV file", e);
            return null;
        }
    }

    // 写入字符串到输出流
    private void writeString(ByteArrayOutputStream out, String s) throws IOException {
        for (int i = 0; i < s.length(); i++) {
            out.write(s.charAt(i));
        }
    }

    // 写入32位整数到输出流 (小端序)
    private void writeInt(ByteArrayOutputStream out, int val) throws IOException {
        out.write(val & 0xFF);
        out.write((val >> 8) & 0xFF);
        out.write((val >> 16) & 0xFF);
        out.write((val >> 24) & 0xFF);
    }

    // 写入16位整数到输出流 (小端序)
    private void writeShort(ByteArrayOutputStream out, short val) throws IOException {
        out.write(val & 0xFF);
        out.write((val >> 8) & 0xFF);
    }

    // 上传音频文件到服务器
    private void uploadAudio(byte[] wavData) {
        if (wavData == null || wavData.length == 0) {
            Log.e(TAG, "No WAV data to upload");
            return;
        }

        Log.d(TAG, "Uploading WAV data, size: " + wavData.length + " bytes");

        executorService.execute(() -> {
            try {
                // 创建临时文件
                File tempFile = File.createTempFile("recording", ".wav", new File(System.getProperty("java.io.tmpdir")));
                try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                    fos.write(wavData);
                }

                // 构建multipart请求
                RequestBody requestBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", "recording.wav",
                                RequestBody.create(MediaType.parse("audio/wav"), tempFile))
                        .build();

                Request request = new Request.Builder()
                        .url(FSR_URL)
                        .post(requestBody)
                        .build();

                // 发送请求
                client.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        Log.e(TAG, "Upload failed", e);
                        sendErrorToJS(2, "语音上传失败");
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        if (response.isSuccessful()) {
                            String responseBody = response.body().string();
                            Log.d(TAG, "Upload successful: " + responseBody);
                            sendResultToJS(responseBody);
                        } else {
                            Log.e(TAG, "Upload failed with code: " + response.code());
                            sendErrorToJS(2, "语音识别失败");
                        }
                    }
                });

                // 删除临时文件
                tempFile.deleteOnExit();

            } catch (IOException e) {
                Log.e(TAG, "Error during upload", e);
                sendErrorToJS(2, "语音识别失败");
            }
        });
    }

    // 发送结果到JS
    private void sendResultToJS(String response) {
        try {
            JSONObject jsonResponse = new JSONObject(response);
            jsonResponse.put("code", 0); // code 0 表示没有错误

            String resultJson = jsonResponse.toString();
            JsbBridge.sendToScript("FSRResult", resultJson);
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing response JSON", e);
            JsbBridge.sendToScript("FSRResult", response);
        }
    }

    // 发送错误到JS
    private void sendErrorToJS(int errCode, String errorMessage) {
        try {
            JSONObject error = new JSONObject();
            error.put("code", errCode);
            error.put("message", errorMessage);
            JsbBridge.sendToScript("FSRResult", error.toString());

        } catch (JSONException e) {
            Log.e(TAG, "Error creating error JSON", e);
            JsbBridge.sendToScript("FSRError", "{\"error\":\"Unknown error\"}");
        }
    }
}