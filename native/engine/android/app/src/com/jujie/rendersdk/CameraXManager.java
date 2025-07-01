package com.jujie.rendersdk;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.core.impl.UseCaseConfigFactory;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionFilter;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.lang.Thread;
import java.util.concurrent.CountDownLatch;
import java.util.Timer;
import java.util.TimerTask;
import com.cocos.lib.JsbBridge;
import org.json.JSONObject;
import android.graphics.drawable.GradientDrawable;

public class CameraXManager {
    private static final String TAG = "CameraXManager";

    // 设计分辨率常量
    private static final int DESIGN_WIDTH = 1080;
    private static final int DESIGN_HEIGHT = 1920;
    private static final float DESIGN_ASPECT_RATIO = (float) DESIGN_WIDTH / DESIGN_HEIGHT;
    
    // 相机画幅常量
    private static final int CAMERA_WIDTH = 984;
    private static final int CAMERA_HEIGHT = 556;
    private static final float CAMERA_ASPECT_RATIO = (float) CAMERA_WIDTH / CAMERA_HEIGHT;
    
    // 相机画幅底边距离设计分辨率底边的距离
    private static final int CAMERA_BOTTOM_MARGIN = 402;

    private Activity activity;
    private LifecycleOwner lifecycleOwner;
    private PreviewView previewView;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private ProcessCameraProvider cameraProvider;
    private FrameLayout cameraContainer;
    private boolean saveFlag = true;

    public CameraXManager(Activity activity, LifecycleOwner lifecycleOwner) {
        this.activity = activity;
        this.lifecycleOwner = lifecycleOwner;
    }

    public View createCameraView() {
        // 确保在主线程中执行UI操作
        if (activity.getMainLooper().getThread() != Thread.currentThread()) {
            Log.w(TAG, "createCameraView: 不在主线程，使用runOnUiThread");
            final View[] result = new View[1];
            final CountDownLatch latch = new CountDownLatch(1);
            activity.runOnUiThread(() -> {
                try {
                    result[0] = createCameraViewInternal();
                } finally {
                    latch.countDown();
                }
            });
            // 等待UI线程完成
            try {
                latch.await();
            } catch (InterruptedException e) {
                Log.e(TAG, "createCameraView: 等待UI线程被中断", e);
                Thread.currentThread().interrupt();
            }
            return result[0];
        }
        
        return createCameraViewInternal();
    }
    
    private View createCameraViewInternal() {
        // 获取屏幕尺寸
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
        float screenAspectRatio = (float) screenWidth / screenHeight;
        
        Log.d(TAG, String.format("屏幕尺寸: %dx%d, 宽高比: %.3f", screenWidth, screenHeight, screenAspectRatio));
        Log.d(TAG, String.format("设计分辨率: %dx%d, 宽高比: %.3f", DESIGN_WIDTH, DESIGN_HEIGHT, DESIGN_ASPECT_RATIO));
        
        // 计算自适应后的游戏画面尺寸和位置
        int gameWidth, gameHeight, gameLeft, gameTop;
        
        if (screenAspectRatio > DESIGN_ASPECT_RATIO) {
            // 屏幕更宽（如iPad），两侧留白，画幅居中
            gameHeight = screenHeight;
            gameWidth = (int) (gameHeight * DESIGN_ASPECT_RATIO);
            gameLeft = (screenWidth - gameWidth) / 2;
            gameTop = 0;
        } else {
            // 屏幕更窄，上下留黑，画幅居中
            gameWidth = screenWidth;
            gameHeight = (int) (gameWidth / DESIGN_ASPECT_RATIO);
            gameLeft = 0;
            gameTop = (screenHeight - gameHeight) / 2;
        }
        
        Log.d(TAG, String.format("游戏画面: %dx%d, 位置: (%d, %d)", gameWidth, gameHeight, gameLeft, gameTop));
        
        // 计算相机画幅在游戏画面中的位置和尺寸
        float scaleX = (float) gameWidth / DESIGN_WIDTH;
        float scaleY = (float) gameHeight / DESIGN_HEIGHT;
        
        int cameraViewWidth = (int) (CAMERA_WIDTH * scaleX);
        int cameraViewHeight = (int) (CAMERA_HEIGHT * scaleY);
        
        // 计算相机画幅在屏幕中的位置
        // 相机画幅在设计分辨率中的位置：水平居中，底边距离设计分辨率底边474px
        int cameraLeftInGame = (DESIGN_WIDTH - CAMERA_WIDTH) / 2;
        int cameraTopInGame = DESIGN_HEIGHT - CAMERA_BOTTOM_MARGIN - CAMERA_HEIGHT;
        
        int cameraLeft = gameLeft + (int) (cameraLeftInGame * scaleX);
        int cameraTop = gameTop + (int) (cameraTopInGame * scaleY);
        
        Log.d(TAG, String.format("相机画幅: %dx%d, 位置: (%d, %d)", cameraViewWidth, cameraViewHeight, cameraLeft, cameraTop));
        
        // 创建根布局（全屏透明）
        FrameLayout rootLayout = new FrameLayout(activity);
        FrameLayout.LayoutParams rootParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
        rootLayout.setLayoutParams(rootParams);
        rootLayout.setBackgroundColor(Color.TRANSPARENT);

        // 创建相机预览容器
        cameraContainer = new FrameLayout(activity);
        FrameLayout.LayoutParams cameraParams = new FrameLayout.LayoutParams(
            cameraViewWidth, cameraViewHeight
        );
        cameraParams.leftMargin = cameraLeft;
        cameraParams.topMargin = cameraTop;
        cameraContainer.setLayoutParams(cameraParams);
        cameraContainer.setClipChildren(true);
        cameraContainer.setBackgroundColor(Color.TRANSPARENT);
        
        // 创建PreviewView
        previewView = new PreviewView(activity);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        FrameLayout.LayoutParams previewParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
        previewView.setLayoutParams(previewParams);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewView.setBackgroundColor(Color.BLACK);
        previewView.setClipToOutline(true);
        previewView.setClipChildren(true);
        previewView.setVisibility(View.GONE);
        
        // 组装视图层次
        cameraContainer.addView(previewView);
        rootLayout.addView(cameraContainer);
        
        // 添加布局变化监听器
        previewView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            String size = String.format("PreviewView size: %dx%d", right-left, bottom-top);
            Log.d(TAG, "PreviewView Layout: " + size);
            Log.d(TAG, String.format("PreviewView position: left=%d, top=%d, right=%d, bottom=%d", left, top, right, bottom));
        });
        
        return rootLayout;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(activity);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();

                ResolutionSelector resolutionSelector = new ResolutionSelector.Builder().setAspectRatioStrategy(new AspectRatioStrategy(AspectRatio.RATIO_4_3,AspectRatioStrategy.FALLBACK_RULE_AUTO)).build();

                Preview preview = new Preview.Builder().setResolutionSelector(resolutionSelector).build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.LOWEST))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                UseCaseGroup useCaseGroup = new UseCaseGroup.Builder()
                        .addUseCase(preview)
                        .addUseCase(videoCapture)
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        lifecycleOwner, cameraSelector, useCaseGroup
                );
            } catch (Exception e) {
                Log.e(TAG, "启动相机失败", e);
            }
        }, ContextCompat.getMainExecutor(activity));
    }

    public void startRecording() {
        if (videoCapture == null) {
            Log.e(TAG, "startRecording: videoCapture is null");
            return;
        }
        Log.i(TAG, "startRecording: 开始准备录制");
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File appDir = new File(downloadDir, "CameraRecords");
        if (!appDir.exists()) {
            boolean mkdirsResult = appDir.mkdirs();
            Log.i(TAG, "startRecording: 创建目录结果=" + mkdirsResult);
        }
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String name = "VID_" + timeStamp + ".mp4";
        File file = new File(appDir, name);
        Log.i(TAG, "startRecording: 录制文件路径=" + file.getAbsolutePath());

        FileOutputOptions outputOptions = new FileOutputOptions.Builder(file).build();

        recording = videoCapture.getOutput()
                .prepareRecording(activity, outputOptions)
                .start(ContextCompat.getMainExecutor(activity), recordEvent -> {
                    if (recordEvent instanceof VideoRecordEvent.Start) {
                        Log.i(TAG, "录制已开始");
                    }
                    if (recordEvent instanceof VideoRecordEvent.Finalize) {
                        VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) recordEvent;
                
                        if (finalizeEvent.hasError()) {
                            Log.e(TAG, "录制失败: " + finalizeEvent.getError());
                            Toast.makeText(activity, "录制失败: " + finalizeEvent.getError(), Toast.LENGTH_LONG).show();
                            JsbBridge.sendToScript("CAMERARECORDERRESULT", "{\"code\":1,\"error\":\"录制失败\"}");
                        } else {
                            Log.i(TAG, "录制完成: " + file.getAbsolutePath());
                            MediaScannerConnection.scanFile(
                                activity,
                                new String[]{file.getAbsolutePath()},
                                new String[]{"video/mp4"},
                                null
                            );
                            Toast.makeText(activity,
                                "视频已保存到: Download/CameraRecords/" + name,
                                Toast.LENGTH_LONG).show();
                            try {
                                JSONObject json = new JSONObject();
                                json.put("code", 0);
                                json.put("message", "录制完成");
                                json.put("absolutePath", file.getAbsolutePath());

                                Log.d(TAG, "录制完成: " + json.toString());
                                if (saveFlag) {
                                    JsbBridge.sendToScript("CAMERARECORDERRESULT", json.toString());
                                }
                            } catch (org.json.JSONException e) {
                                Log.e(TAG, "创建JSON对象失败", e);
                                JsbBridge.sendToScript("CAMERARECORDERRESULT", "{\"code\":1,\"error\":\"JSON创建失败\"}");
                            }
                        }
                        recording = null;
                    }
                });
    }

    public void stopRecording() {
        if (recording != null) {
            Log.i(TAG, "stopRecording: 停止录制");
            recording.stop();
            recording = null;
            saveFlag = true;
        } else {
            Log.w(TAG, "stopRecording: 当前没有正在录制");
        }
    }

    public void stopRecordingWithoutSave() {
        if (recording != null) {
            Log.i(TAG, "stopRecordingWithoutSave: 停止录制");
            recording.stop();
            recording = null;
            saveFlag = false;
        } else {
            Log.w(TAG, "stopRecordingWithoutSave: 当前没有正在录制");
        }
    }

    private void stopCameraPreview() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    public void showCameraPreview() {
        activity.runOnUiThread(() -> {
            if (previewView != null) {
                previewView.setVisibility(View.VISIBLE);
                startCamera();
            } else {
                Log.e(TAG, "PreviewView 未初始化，请先调用 createCameraView");
            }
        });
    }

    public void hideCameraPreview() {
        activity.runOnUiThread(() -> {
            stopCameraPreview();
            if (previewView != null) {
                previewView.setVisibility(View.GONE);
            }
        });
    }

    // 获取相机容器，供ImageLayerManager使用
    public FrameLayout getCameraContainer() {
        return cameraContainer;
    }
}
