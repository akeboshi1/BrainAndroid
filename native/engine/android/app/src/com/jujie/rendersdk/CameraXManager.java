package com.jujie.rendersdk;

import android.app.Activity;
import android.graphics.Color;
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
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.List;
import java.lang.Thread;
import java.util.concurrent.CountDownLatch;
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
    
    
    // 分辨率检查控制变量
    private boolean resolutionCheckCompleted = false;
    private android.os.Handler resolutionCheckHandler = null;

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

                // 创建统一的分辨率选择器，确保预览和录制使用相同分辨率
                ResolutionSelector resolutionSelector = createUnifiedResolutionSelector();

                Preview preview = new Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // 使用相同的分辨率选择器创建录制器
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
                
                // 添加光栅问题诊断
                diagnoseRasterIssues();
                
                // 检查预览和录制分辨率同步
                checkResolutionSync();
                
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
                        
                        // 记录编码器信息
                        logEncoderInfo();
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
            
            // 重置分辨率检查状态，允许下次显示时重新检查
            resolutionCheckCompleted = false;
            if (resolutionCheckHandler != null) {
                resolutionCheckHandler.removeCallbacksAndMessages(null);
                resolutionCheckHandler = null;
            }
        });
    }

    // 获取相机容器，供ImageLayerManager使用
    public FrameLayout getCameraContainer() {
        return cameraContainer;
    }

    /**
     * 检查预览和录制分辨率同步
     */
    private void checkResolutionSync() {
        try {
            // 避免重复检查
            if (resolutionCheckCompleted) {
                Log.d(TAG, "分辨率检查已完成，跳过重复检查");
                return;
            }
            
            // 取消之前的检查任务
            if (resolutionCheckHandler != null) {
                resolutionCheckHandler.removeCallbacksAndMessages(null);
            }
            
            // 创建新的Handler实例
            resolutionCheckHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            
            Log.i(TAG, "=== 分辨率同步检查 ===");
            
            // 延迟检查，确保相机完全初始化
            resolutionCheckHandler.postDelayed(() -> {
                try {
                    if (previewView != null && videoCapture != null) {
                        int previewWidth = previewView.getWidth();
                        int previewHeight = previewView.getHeight();
                        
                        // 减少日志输出，只在有问题时输出详细信息
                        if (previewWidth != CAMERA_WIDTH || previewHeight != CAMERA_HEIGHT) {
                            Log.w(TAG, "预览分辨率: " + previewWidth + "x" + previewHeight + 
                                  " vs 相机画幅: " + CAMERA_WIDTH + "x" + CAMERA_HEIGHT);
                            Log.w(TAG, "警告: 预览分辨率与相机画幅不匹配!");
                            Log.w(TAG, "这可能导致录制视频出现光栅条纹");
                            
                            // 计算分辨率差异
                            int widthDiff = Math.abs(previewWidth - CAMERA_WIDTH);
                            int heightDiff = Math.abs(previewHeight - CAMERA_HEIGHT);
                            Log.w(TAG, "宽度差异: " + widthDiff + "px, 高度差异: " + heightDiff + "px");
                            
                            // 尝试自动修复分辨率不匹配问题
                            attemptResolutionFix(previewWidth, previewHeight);
                            
                            // 建议修复方案
                            suggestResolutionFix(previewWidth, previewHeight);
                        } else {
                            Log.i(TAG, "预览分辨率与相机画幅匹配，分辨率同步正常");
                        }
                        
                        // 标记检查完成
                        resolutionCheckCompleted = true;
                    } else {
                        Log.e(TAG, "预览视图或录制器未初始化，无法检查分辨率同步");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "分辨率同步检查失败", e);
                }
            }, 2000); // 延迟2秒检查
            
        } catch (Exception e) {
            Log.e(TAG, "分辨率同步检查异常", e);
        }
    }
    
    /**
     * 创建统一的分辨率选择器，确保预览和录制使用相同分辨率
     */
    private ResolutionSelector createUnifiedResolutionSelector() {
        try {
            Log.i(TAG, "=== 创建统一分辨率选择器 ===");
            
            ResolutionSelector.Builder builder = new ResolutionSelector.Builder();
            
            // 使用4:3宽高比策略
            builder.setAspectRatioStrategy(new AspectRatioStrategy(
                AspectRatio.RATIO_4_3, 
                AspectRatioStrategy.FALLBACK_RULE_AUTO
            ));
            
            // 添加分辨率过滤器，强制使用特定分辨率
            builder.setResolutionFilter(new ResolutionFilter() {
                @Override
                public List<Size> filter(List<Size> availableSizes, int targetRotation) {
                    Log.i(TAG, "可用分辨率数量: " + availableSizes.size());
                    Log.i(TAG, "目标旋转: " + targetRotation);
                    
                    // 记录所有可用分辨率
                    for (Size size : availableSizes) {
                        Log.d(TAG, "可用分辨率: " + size.getWidth() + "x" + size.getHeight());
                    }
                    
                    // 优先选择与相机画幅最接近的分辨率
                    Size bestSize = null;
                    int minDiff = Integer.MAX_VALUE;
                    
                    for (Size size : availableSizes) {
                        // 计算与相机画幅的差异
                        int widthDiff = Math.abs(size.getWidth() - CAMERA_WIDTH);
                        int heightDiff = Math.abs(size.getHeight() - CAMERA_HEIGHT);
                        int totalDiff = widthDiff + heightDiff;
                        
                        if (totalDiff < minDiff) {
                            minDiff = totalDiff;
                            bestSize = size;
                        }
                    }
                    
                    if (bestSize != null) {
                        Log.i(TAG, "选择分辨率: " + bestSize.getWidth() + "x" + bestSize.getHeight());
                        Log.i(TAG, "与相机画幅差异: " + minDiff + "px");
                        
                        // 返回包含最佳分辨率的列表
                        return java.util.Arrays.asList(bestSize);
                    } else {
                        Log.w(TAG, "未找到合适的分辨率，返回原始列表");
                        return availableSizes;
                    }
                }
            });
            
            ResolutionSelector selector = builder.build();
            Log.i(TAG, "统一分辨率选择器创建完成");
            return selector;
            
        } catch (Exception e) {
            Log.e(TAG, "创建统一分辨率选择器失败，使用默认配置", e);
            return new ResolutionSelector.Builder()
                    .setAspectRatioStrategy(new AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                    .build();
        }
    }

    /**
     * 尝试自动修复分辨率不匹配问题
     */
    private void attemptResolutionFix(int previewWidth, int previewHeight) {
        try {
            Log.i(TAG, "=== 尝试自动修复分辨率 ===");
            
            // 检查是否需要调整相机容器尺寸
            if (cameraContainer != null) {
                int containerWidth = cameraContainer.getWidth();
                int containerHeight = cameraContainer.getHeight();
                
                Log.i(TAG, "当前容器尺寸: " + containerWidth + "x" + containerHeight);
                Log.i(TAG, "目标容器尺寸: " + CAMERA_WIDTH + "x" + CAMERA_HEIGHT);
                
                // 如果容器尺寸不匹配，尝试调整
                if (containerWidth != CAMERA_WIDTH || containerHeight != CAMERA_HEIGHT) {
                    Log.w(TAG, "容器尺寸不匹配，尝试调整...");
                    
                    // 这里可以添加动态调整容器尺寸的逻辑
                    // 但由于容器尺寸通常在布局时确定，这里主要记录信息
                    Log.w(TAG, "建议在布局时确保容器尺寸为: " + CAMERA_WIDTH + "x" + CAMERA_HEIGHT);
                }
            }
            
            // 检查预览视图的缩放设置
            if (previewView != null) {
                Log.i(TAG, "预览视图缩放类型: " + previewView.getScaleType());
                
                // 如果缩放类型可能导致分辨率问题，建议调整
                if (previewView.getScaleType() != PreviewView.ScaleType.FILL_CENTER) {
                    Log.w(TAG, "建议将预览视图缩放类型设置为FILL_CENTER");
                }
            }
            
            Log.i(TAG, "自动修复尝试完成");
            
        } catch (Exception e) {
            Log.e(TAG, "自动修复分辨率失败", e);
        }
    }

    /**
     * 建议分辨率修复方案
     */
    private void suggestResolutionFix(int previewWidth, int previewHeight) {
        try {
            Log.i(TAG, "=== 分辨率修复建议 ===");
            
            // 计算最佳分辨率
            int targetWidth = CAMERA_WIDTH;
            int targetHeight = CAMERA_HEIGHT;
            
            Log.i(TAG, "目标分辨率: " + targetWidth + "x" + targetHeight);
            
            // 检查是否需要调整相机画幅常量
            if (previewWidth > 0 && previewHeight > 0) {
                float previewRatio = (float) previewWidth / previewHeight;
                float cameraRatio = (float) CAMERA_WIDTH / CAMERA_HEIGHT;
                
                Log.i(TAG, "预览宽高比: " + previewRatio);
                Log.i(TAG, "相机画幅宽高比: " + cameraRatio);
                
                if (Math.abs(previewRatio - cameraRatio) > 0.1f) {
                    Log.w(TAG, "宽高比不匹配，建议调整相机画幅常量:");
                    Log.w(TAG, "当前相机画幅: " + CAMERA_WIDTH + "x" + CAMERA_HEIGHT);
                    Log.w(TAG, "建议相机画幅: " + previewWidth + "x" + previewHeight);
                }
            }
            
            Log.i(TAG, "====================");
            
        } catch (Exception e) {
            Log.e(TAG, "分辨率修复建议失败", e);
        }
    }

    /**
     * 诊断光栅问题
     */
    private void diagnoseRasterIssues() {
        try {
            Log.i(TAG, "=== 光栅问题诊断开始 ===");
            
            // 记录设备基本信息
            android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            Log.i(TAG, "设备型号: " + android.os.Build.MODEL);
            Log.i(TAG, "Android版本: " + android.os.Build.VERSION.RELEASE);
            Log.i(TAG, "屏幕尺寸: " + metrics.widthPixels + "x" + metrics.heightPixels);
            Log.i(TAG, "屏幕密度: " + metrics.densityDpi + " DPI");
            
            // 记录相机画幅配置
            Log.i(TAG, "设计分辨率: " + DESIGN_WIDTH + "x" + DESIGN_HEIGHT);
            Log.i(TAG, "相机画幅: " + CAMERA_WIDTH + "x" + CAMERA_HEIGHT);
            Log.i(TAG, "相机画幅比例: " + CAMERA_ASPECT_RATIO);
            
            // 延迟检查预览和录制配置
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                checkPreviewRecordingAlignment();
            }, 2000); // 延迟2秒确保相机完全初始化
            
        } catch (Exception e) {
            Log.e(TAG, "光栅问题诊断失败", e);
        }
    }
    
    /**
     * 检查预览和录制对齐情况
     */
    private void checkPreviewRecordingAlignment() {
        try {
            Log.i(TAG, "=== 预览录制对齐检查 ===");
            
            if (previewView != null) {
                int previewWidth = previewView.getWidth();
                int previewHeight = previewView.getHeight();
                Log.i(TAG, "预览视图尺寸: " + previewWidth + "x" + previewHeight);
                
                // 检查预览尺寸是否与相机画幅匹配
                if (previewWidth != CAMERA_WIDTH || previewHeight != CAMERA_HEIGHT) {
                    Log.w(TAG, "警告: 预览尺寸与相机画幅不匹配!");
                    Log.w(TAG, "预览: " + previewWidth + "x" + previewHeight + " vs 相机画幅: " + CAMERA_WIDTH + "x" + CAMERA_HEIGHT);
                    
                    // 计算缩放比例
                    float scaleX = (float) previewWidth / CAMERA_WIDTH;
                    float scaleY = (float) previewHeight / CAMERA_HEIGHT;
                    Log.w(TAG, "缩放比例: X=" + scaleX + ", Y=" + scaleY);
                    
                    if (Math.abs(scaleX - scaleY) > 0.05f) {
                        Log.e(TAG, "严重警告: 缩放比例不匹配，这可能导致光栅条纹!");
                    }
                } else {
                    Log.i(TAG, "预览尺寸与相机画幅匹配");
                }
            }
            
            if (cameraContainer != null) {
                int containerWidth = cameraContainer.getWidth();
                int containerHeight = cameraContainer.getHeight();
                Log.i(TAG, "相机容器尺寸: " + containerWidth + "x" + containerHeight);
                
                // 检查容器尺寸
                if (containerWidth != CAMERA_WIDTH || containerHeight != CAMERA_HEIGHT) {
                    Log.w(TAG, "警告: 容器尺寸与相机画幅不匹配!");
                    Log.w(TAG, "容器: " + containerWidth + "x" + containerHeight + " vs 相机画幅: " + CAMERA_WIDTH + "x" + CAMERA_HEIGHT);
                }
            }
            
            // 检查录制器配置
            if (videoCapture != null) {
                Log.i(TAG, "录制器已配置");
            } else {
                Log.e(TAG, "录制器未配置，这可能导致录制问题");
            }
            
            Log.i(TAG, "========================");
            
        } catch (Exception e) {
            Log.e(TAG, "预览录制对齐检查失败", e);
        }
    }

    
    /**
     * 记录编码器信息
     */
    private void logEncoderInfo() {
        try {
            Log.i(TAG, "=== 编码器信息 ===");
            
            // 记录设备硬件信息
            Log.i(TAG, "CPU架构: " + android.os.Build.CPU_ABI);
            Log.i(TAG, "硬件: " + android.os.Build.HARDWARE);
            Log.i(TAG, "制造商: " + android.os.Build.MANUFACTURER);
            
            // 记录Android版本信息
            int apiLevel = android.os.Build.VERSION.SDK_INT;
            Log.i(TAG, "API级别: " + apiLevel);
            
            // 检查是否支持硬件编码
            boolean supportsHardwareEncoding = apiLevel >= 21; // Android 5.0+
            Log.i(TAG, "支持硬件编码: " + supportsHardwareEncoding);
            
            // 记录内存信息
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            
            Log.i(TAG, "最大内存: " + (maxMemory / 1024 / 1024) + "MB");
            Log.i(TAG, "总内存: " + (totalMemory / 1024 / 1024) + "MB");
            Log.i(TAG, "可用内存: " + (freeMemory / 1024 / 1024) + "MB");
            
            // 检查录制质量设置
            if (videoCapture != null) {
                Log.i(TAG, "录制器配置: 已配置");
            } else {
                Log.e(TAG, "录制器配置: 未配置");
            }
            
            Log.i(TAG, "================");
            
        } catch (Exception e) {
            Log.e(TAG, "记录编码器信息失败", e);
        }
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
            // 3. Downloads/video目录（方便查看）
            new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "video"),
            // 4. 外部存储Downloads目录（需要权限）
            new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CameraRecords"),
            // 5. 外部存储根目录（需要权限）
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
