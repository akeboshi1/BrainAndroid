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
                        .setExecutor(ContextCompat.getMainExecutor(activity))
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
        long startTime = System.currentTimeMillis();
        Log.i(TAG, "startRecording: 开始录制流程 - " + startTime);
        
        if (videoCapture == null) {
            Log.e(TAG, "startRecording: videoCapture is null");
            return;
        }
        
        // 获取合适的存储目录
        long beforeStorageTime = System.currentTimeMillis();
        File appDir = getStorageDirectory();
        long afterStorageTime = System.currentTimeMillis();
        Log.i(TAG, "获取存储目录耗时 - " + (afterStorageTime - beforeStorageTime) + "ms");
        
        if (appDir == null) {
            Log.e(TAG, "startRecording: 无法获取有效的存储目录");
            Toast.makeText(activity, "无法访问存储，请检查应用权限", Toast.LENGTH_LONG).show();
            return;
        }
        
        // 检查存储空间 - 使用更准确的检查方法
        long beforeSpaceCheckTime = System.currentTimeMillis();
        long freeSpace = appDir.getFreeSpace();
        long requiredSpace = 100 * 1024 * 1024; // 100MB
        long afterSpaceCheckTime = System.currentTimeMillis();
        Log.i(TAG, "存储空间检查耗时 - " + (afterSpaceCheckTime - beforeSpaceCheckTime) + "ms");
        Log.i(TAG, "startRecording: 存储目录=" + appDir.getAbsolutePath() + ", 可用存储空间=" + (freeSpace / 1024 / 1024) + "MB, 需要空间=" + (requiredSpace / 1024 / 1024) + "MB");
        
        if (freeSpace < requiredSpace) {
            Log.w(TAG, "startRecording: 存储空间不足");
            Toast.makeText(activity, "存储空间不足，请清理手机存储后重试", Toast.LENGTH_LONG).show();
            return;
        }
        
        Log.i(TAG, "startRecording: 开始准备录制");
        
        // 检查目录权限和可写性
        long beforePermissionCheckTime = System.currentTimeMillis();
        if (!appDir.canWrite()) {
            Log.e(TAG, "startRecording: 目录无写入权限: " + appDir.getAbsolutePath());
            Toast.makeText(activity, "无法写入存储目录，请检查应用权限", Toast.LENGTH_LONG).show();
            return;
        }
        
        // 尝试创建测试文件验证写入权限
        File testFile = new File(appDir, "test_write.tmp");
        try {
            if (!testFile.createNewFile()) {
                Log.w(TAG, "startRecording: 测试文件已存在，尝试删除");
                if (!testFile.delete()) {
                    Log.e(TAG, "startRecording: 无法删除测试文件，可能权限不足");
                    Toast.makeText(activity, "存储权限不足，请检查应用权限设置", Toast.LENGTH_LONG).show();
                    return;
                }
                if (!testFile.createNewFile()) {
                    Log.e(TAG, "startRecording: 无法创建测试文件");
                    Toast.makeText(activity, "无法写入存储，请检查存储权限", Toast.LENGTH_LONG).show();
                    return;
                }
            }
            testFile.delete(); // 删除测试文件
        } catch (Exception e) {
            Log.e(TAG, "startRecording: 测试文件创建失败", e);
            Toast.makeText(activity, "存储访问失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        long afterPermissionCheckTime = System.currentTimeMillis();
        Log.i(TAG, "权限检查耗时 - " + (afterPermissionCheckTime - beforePermissionCheckTime) + "ms");
        
        // 生成唯一文件名，避免文件冲突
        long beforeFilePrepTime = System.currentTimeMillis();
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(new Date());
        String name = "VID_" + timeStamp + ".mp4";
        File file = new File(appDir, name);
        
        // 确保文件不存在
        int fileIndex = 1;
        while (file.exists()) {
            name = "VID_" + timeStamp + "_" + fileIndex + ".mp4";
            file = new File(appDir, name);
            fileIndex++;
            if (fileIndex > 100) {
                Log.e(TAG, "startRecording: 无法生成唯一文件名");
                Toast.makeText(activity, "无法创建录制文件", Toast.LENGTH_LONG).show();
                return;
            }
        }
        
        // 创建final副本供lambda表达式使用
        final File finalFile = file;
        final String finalName = name;
        
        Log.i(TAG, "startRecording: 录制文件路径=" + finalFile.getAbsolutePath());

        // 创建输出选项，增加错误处理
        FileOutputOptions outputOptions;
        try {
            outputOptions = new FileOutputOptions.Builder(finalFile).build();
        } catch (Exception e) {
            Log.e(TAG, "startRecording: 创建输出选项失败", e);
            Toast.makeText(activity, "录制配置失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        long afterFilePrepTime = System.currentTimeMillis();
        Log.i(TAG, "文件准备耗时 - " + (afterFilePrepTime - beforeFilePrepTime) + "ms");

        long beforeStartRecordingTime = System.currentTimeMillis();
        recording = videoCapture.getOutput()
                .prepareRecording(activity, outputOptions)
                .start(ContextCompat.getMainExecutor(activity), recordEvent -> {
                    if (recordEvent instanceof VideoRecordEvent.Start) {
                        long recordStartTime = System.currentTimeMillis();
                        Log.i(TAG, "录制已开始 - " + recordStartTime);
                        long afterStartRecordingTime = System.currentTimeMillis();
                        Log.i(TAG, "录制启动总耗时 - " + (afterStartRecordingTime - beforeStartRecordingTime) + "ms");
                    }
                    if (recordEvent instanceof VideoRecordEvent.Finalize) {
                        long finalizeStartTime = System.currentTimeMillis();
                        Log.i(TAG, "录制Finalize事件开始 - " + finalizeStartTime);
                        
                        VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) recordEvent;
                
                        if (finalizeEvent.hasError()) {
                            long errorStartTime = System.currentTimeMillis();
                            Log.i(TAG, "开始处理录制错误 - " + errorStartTime);
                            
                            int errorCode = finalizeEvent.getError();
                            String errorMessage = getErrorMessage(errorCode);
                            Log.e(TAG, "录制失败: 错误码=" + errorCode + ", 错误信息=" + errorMessage);
                            
                            // 针对错误码5的特殊处理
                            if (errorCode == 5) {
                                Log.e(TAG, "录制失败: 输出选项无效，可能原因：文件路径问题、权限问题或设备兼容性问题");
                                errorMessage = "录制配置无效，请检查存储权限或重启应用重试";
                            }
                            
                            long beforeToastTime = System.currentTimeMillis();
                            Toast.makeText(activity, "录制失败: " + errorMessage, Toast.LENGTH_LONG).show();
                            long afterToastTime = System.currentTimeMillis();
                            Log.i(TAG, "错误Toast显示耗时 - " + (afterToastTime - beforeToastTime) + "ms");
                            
                            long beforeJsonTime = System.currentTimeMillis();
                            try {
                                JSONObject errorJson = new JSONObject();
                                errorJson.put("code", 1);
                                errorJson.put("error", errorMessage);
                                errorJson.put("errorCode", errorCode);
                                long afterJsonTime = System.currentTimeMillis();
                                Log.i(TAG, "错误JSON创建耗时 - " + (afterJsonTime - beforeJsonTime) + "ms");
                                
                                long beforeBridgeTime = System.currentTimeMillis();
                                JsbBridge.sendToScript("CAMERARECORDERRESULT", errorJson.toString());
                                long afterBridgeTime = System.currentTimeMillis();
                                Log.i(TAG, "错误JsbBridge发送耗时 - " + (afterBridgeTime - beforeBridgeTime) + "ms");
                            } catch (org.json.JSONException e) {
                                Log.e(TAG, "创建错误JSON对象失败", e);
                                JsbBridge.sendToScript("CAMERARECORDERRESULT", "{\"code\":1,\"error\":\"录制失败\",\"errorCode\":" + errorCode + "}");
                            }
                            
                            long errorEndTime = System.currentTimeMillis();
                            Log.i(TAG, "错误处理总耗时 - " + (errorEndTime - errorStartTime) + "ms");
                        } else {
                            long successStartTime = System.currentTimeMillis();
                            Log.i(TAG, "开始处理录制成功 - " + successStartTime);
                            
                            Log.i(TAG, "录制完成: " + finalFile.getAbsolutePath());
                            
                            // long beforeScanTime = System.currentTimeMillis();
                            // MediaScannerConnection.scanFile(
                            //     activity,
                            //     new String[]{finalFile.getAbsolutePath()},
                            //     new String[]{"video/mp4"},
                            //     null
                            // );
                            // long afterScanTime = System.currentTimeMillis();
                            // Log.i(TAG, "MediaScanner操作耗时 - " + (afterScanTime - beforeScanTime) + "ms");
                            
                            // long beforeToastTime = System.currentTimeMillis();
                            // Toast.makeText(activity,
                            //     "视频已保存到: " + finalName,
                            //     Toast.LENGTH_LONG).show();
                            // long afterToastTime = System.currentTimeMillis();
                            // Log.i(TAG, "成功Toast显示耗时 - " + (afterToastTime - beforeToastTime) + "ms");
                            
                            long beforeJsonTime = System.currentTimeMillis();
                            try {
                                JSONObject json = new JSONObject();
                                json.put("code", 0);
                                json.put("message", "录制完成");
                                json.put("absolutePath", finalFile.getAbsolutePath());

                                Log.d(TAG, "录制完成: " + json.toString());
                                long afterJsonTime = System.currentTimeMillis();
                                Log.i(TAG, "成功JSON创建耗时 - " + (afterJsonTime - beforeJsonTime) + "ms");
                                
                                if (saveFlag) {
                                    long beforeBridgeTime = System.currentTimeMillis();
                                    JsbBridge.sendToScript("CAMERARECORDERRESULT", json.toString());
                                    long afterBridgeTime = System.currentTimeMillis();
                                    Log.i(TAG, "成功JsbBridge发送耗时 - " + (afterBridgeTime - beforeBridgeTime) + "ms");
                                } else {
                                    Log.i(TAG, "saveFlag为false，跳过JsbBridge发送");
                                }
                            } catch (org.json.JSONException e) {
                                Log.e(TAG, "创建JSON对象失败", e);
                                JsbBridge.sendToScript("CAMERARECORDERRESULT", "{\"code\":1,\"error\":\"JSON创建失败\"}");
                            }
                            
                            long successEndTime = System.currentTimeMillis();
                            Log.i(TAG, "成功处理总耗时 - " + (successEndTime - successStartTime) + "ms");
                        }
                        
                        recording = null;
                        long finalizeEndTime = System.currentTimeMillis();
                        Log.i(TAG, "录制Finalize事件完成 - " + finalizeEndTime + ", 总耗时: " + (finalizeEndTime - finalizeStartTime) + "ms");
                    }
                });
    }

    public void stopRecording() {
        long startTime = System.currentTimeMillis();
        Log.i(TAG, "stopRecording: 开始停止录制 - " + startTime);
        
        if (recording != null) {
            long beforeStopTime = System.currentTimeMillis();
            Log.i(TAG, "stopRecording: 调用recording.stop()前 - " + beforeStopTime);
            
            recording.stop();
            
            long afterStopTime = System.currentTimeMillis();
            Log.i(TAG, "stopRecording: recording.stop()耗时 - " + (afterStopTime - beforeStopTime) + "ms");
            
            recording = null;
            saveFlag = true;
            
            long endTime = System.currentTimeMillis();
            Log.i(TAG, "stopRecording: 停止录制完成 - " + endTime + ", 总耗时: " + (endTime - startTime) + "ms");
        } else {
            Log.w(TAG, "stopRecording: 当前没有正在录制");
        }
    }

    public void stopRecordingWithoutSave() {
        long startTime = System.currentTimeMillis();
        Log.i(TAG, "stopRecordingWithoutSave: 开始停止录制(不保存) - " + startTime);
        
        if (recording != null) {
            long beforeStopTime = System.currentTimeMillis();
            Log.i(TAG, "stopRecordingWithoutSave: 调用recording.stop()前 - " + beforeStopTime);
            
            recording.stop();
            
            long afterStopTime = System.currentTimeMillis();
            Log.i(TAG, "stopRecordingWithoutSave: recording.stop()耗时 - " + (afterStopTime - beforeStopTime) + "ms");
            
            recording = null;
            saveFlag = false;
            
            long endTime = System.currentTimeMillis();
            Log.i(TAG, "stopRecordingWithoutSave: 停止录制完成 - " + endTime + ", 总耗时: " + (endTime - startTime) + "ms");
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

    /**
     * 获取录制错误的中文描述
     * @param errorCode 错误码
     * @return 错误描述
     */
    private String getErrorMessage(int errorCode) {
        switch (errorCode) {
            case 0:
                return "录制成功";
            case 1:
                return "未知错误";
            case 2:
                return "文件大小限制";
            case 3:
                return "存储空间不足";
            case 4:
                return "视频源非活动状态";
            case 5:
                return "输出选项无效";
            case 6:
                return "编码失败";
            case 7:
                return "录制器错误";
            case 8:
                return "无有效数据";
            case 9:
                return "录制时长限制";
            case 10:
                return "录制对象被回收";
            default:
                return "未知错误 (错误码: " + errorCode + ")";
        }
    }

    /**
     * 获取合适的存储目录，兼容Android 10+的存储权限
     * @return 可用的存储目录，如果都不可用则返回null
     */
    private File getStorageDirectory() {
        long startTime = System.currentTimeMillis();
        Log.i(TAG, "getStorageDirectory: 开始获取存储目录 - " + startTime);
        
        // 尝试多个存储路径，按优先级排序
        File[] candidates = {
            // 1. 应用私有目录（推荐，无需权限）
            new File(activity.getExternalFilesDir(null), "CameraRecords"),
            // 2. 应用内部存储
            new File(activity.getFilesDir(), "CameraRecords"),
            // 3. 外部存储Downloads目录（需要权限）
            new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CameraRecords"),
            // 4. 外部存储根目录（需要权限）
            new File(Environment.getExternalStorageDirectory(), "CameraRecords")
        };
        
        for (int i = 0; i < candidates.length; i++) {
            File dir = candidates[i];
            long candidateStartTime = System.currentTimeMillis();
            Log.i(TAG, "getStorageDirectory: 测试存储目录 " + (i + 1) + "/" + candidates.length + " - " + dir.getAbsolutePath());
            
            try {
                Log.d(TAG, "尝试存储目录: " + dir.getAbsolutePath());
                
                // 检查目录是否存在，不存在则创建
                long beforeExistsCheck = System.currentTimeMillis();
                if (!dir.exists()) {
                    long beforeMkdirs = System.currentTimeMillis();
                    boolean created = dir.mkdirs();
                    long afterMkdirs = System.currentTimeMillis();
                    Log.d(TAG, "创建目录 " + dir.getAbsolutePath() + " 结果: " + created + ", 耗时: " + (afterMkdirs - beforeMkdirs) + "ms");
                    if (!created) {
                        continue; // 尝试下一个目录
                    }
                }
                long afterExistsCheck = System.currentTimeMillis();
                Log.i(TAG, "目录存在检查耗时: " + (afterExistsCheck - beforeExistsCheck) + "ms");
                
                // 检查是否可写
                long beforeWriteCheck = System.currentTimeMillis();
                if (!dir.canWrite()) {
                    Log.w(TAG, "目录不可写: " + dir.getAbsolutePath());
                    continue;
                }
                long afterWriteCheck = System.currentTimeMillis();
                Log.i(TAG, "目录写入权限检查耗时: " + (afterWriteCheck - beforeWriteCheck) + "ms");
                
                // 测试写入权限
                long beforeTestFile = System.currentTimeMillis();
                File testFile = new File(dir, "test_write.tmp");
                if (testFile.exists()) {
                    testFile.delete();
                }
                
                if (testFile.createNewFile()) {
                    testFile.delete();
                    long afterTestFile = System.currentTimeMillis();
                    Log.i(TAG, "测试文件创建耗时: " + (afterTestFile - beforeTestFile) + "ms");
                    Log.i(TAG, "使用存储目录: " + dir.getAbsolutePath());
                    
                    long endTime = System.currentTimeMillis();
                    Log.i(TAG, "getStorageDirectory: 成功获取存储目录 - " + endTime + ", 总耗时: " + (endTime - startTime) + "ms");
                    return dir;
                }
                
            } catch (Exception e) {
                Log.w(TAG, "测试目录失败: " + dir.getAbsolutePath() + ", 错误: " + e.getMessage());
                continue;
            }
            
            long candidateEndTime = System.currentTimeMillis();
            Log.i(TAG, "存储目录 " + (i + 1) + " 测试总耗时: " + (candidateEndTime - candidateStartTime) + "ms");
        }
        
        long endTime = System.currentTimeMillis();
        Log.e(TAG, "所有存储目录都不可用，总耗时: " + (endTime - startTime) + "ms");
        return null;
    }
}
