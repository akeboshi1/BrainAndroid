package com.jujie.rendersdk;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.view.Gravity;
import android.widget.ImageView;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLSurface;

public class ImageLayerManager {
    private static final String TAG = "ImageLayerManager";
    
    // 设计分辨率常量
    private static final int DESIGN_WIDTH = 1080;
    private static final int DESIGN_HEIGHT = 1920;
    private static final float DESIGN_ASPECT_RATIO = (float) DESIGN_WIDTH / DESIGN_HEIGHT;
    
    // 相机画幅常量
    private static final int CAMERA_WIDTH = 984;
    private static final int CAMERA_HEIGHT = 556;
    private static final float CAMERA_ASPECT_RATIO = (float) CAMERA_WIDTH / CAMERA_HEIGHT;
    
    // 相机画幅底边距离设计分辨率底边的距离
    private static final int CAMERA_BOTTOM_MARGIN = 474;
    
    // 单例实例
    private static ImageLayerManager instance;
    
    private Activity activity;
    private View overlayView;
    private String currentImagePath;
    private boolean isVisible = false;
    private FrameLayout parentContainer;
    
    private ImageView overlayImageViewTop;    // 顶层图片
    private ImageView overlayImageViewBottom; // 底层图片
    
    public ImageLayerManager(Activity activity) {
        this.activity = activity;
    }
    
    // 获取单例实例
    public static ImageLayerManager getInstance(Activity activity) {
        if (instance == null) {
            instance = new ImageLayerManager(activity);
        }
        return instance;
    }
    
    // 清理单例实例
    public static void clearInstance() {
        if (instance != null) {
            instance.cleanup();
            instance = null;
        }
    }
    
    public View createOverlayView(FrameLayout parentContainer) {
        // 如果已经创建过且父容器相同，直接返回
        if (overlayView != null && this.parentContainer == parentContainer) {
            Log.d(TAG, "Overlay view already created for this container, returning existing view");
            return overlayView;
        }
        
        // 如果父容器不同，先清理旧的视图
        if (overlayView != null && this.parentContainer != null && this.parentContainer != parentContainer) {
            Log.d(TAG, "Parent container changed, removing old overlay view");
            this.parentContainer.removeView(overlayView);
            overlayView = null;
        }
        
        // 保存父容器引用
        this.parentContainer = parentContainer;
        
        // 获取屏幕尺寸
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
        float screenAspectRatio = (float) screenWidth / screenHeight;
        
        Log.d(TAG, String.format("屏幕尺寸: %dx%d, 宽高比: %.3f", screenWidth, screenHeight, screenAspectRatio));
        
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
        
        // 计算图片画幅在游戏画面中的位置和尺寸
        float scaleX = (float) gameWidth / DESIGN_WIDTH;
        float scaleY = (float) gameHeight / DESIGN_HEIGHT;
        
        int overlayWidth = (int) (CAMERA_WIDTH * scaleX);
        int overlayHeight = (int) (CAMERA_HEIGHT * scaleY);
        
        // 计算图片画幅在屏幕中的位置
        int overlayLeftInGame = (DESIGN_WIDTH - CAMERA_WIDTH) / 2;
        int overlayTopInGame = DESIGN_HEIGHT - CAMERA_BOTTOM_MARGIN - CAMERA_HEIGHT;
        
        // 将位置往上移动一半高度的距离
        overlayTopInGame = overlayTopInGame - CAMERA_HEIGHT / 2;
        
        int overlayLeft = gameLeft + (int) (overlayLeftInGame * scaleX);
        int overlayTop = gameTop + (int) (overlayTopInGame * scaleY);
        
        Log.d(TAG, String.format("图片画幅: %dx%d, 位置: (%d, %d)", overlayWidth, overlayHeight, overlayLeft, overlayTop));
        
        // 创建ImageView
        ImageView imageView = new ImageView(activity);
        
        // 设置ImageView的布局参数
        FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(
            overlayWidth, overlayHeight
        );
        
        // 在相机容器内居中显示
        imageParams.gravity = Gravity.CENTER;
        
        imageView.setLayoutParams(imageParams);
        imageView.setVisibility(View.GONE);
        
        // 创建底层ImageView
        overlayImageViewBottom = new ImageView(activity);
        overlayImageViewBottom.setLayoutParams(imageParams);
        overlayImageViewBottom.setVisibility(View.GONE);

        // 创建顶层ImageView
        overlayImageViewTop = new ImageView(activity);
        overlayImageViewTop.setLayoutParams(imageParams);
        overlayImageViewTop.setVisibility(View.GONE);

        // 先添加底层，再添加顶层，保证叠加顺序
        parentContainer.addView(overlayImageViewBottom);
        parentContainer.addView(overlayImageViewTop);

        overlayView = overlayImageViewTop; // 兼容旧逻辑
        
        Log.d(TAG, "New overlay view created and added to container");
        return overlayView;
    }
    
    public void showOverlay(String topAssetPath, String bottomAssetPath) {
        if (overlayImageViewTop == null || overlayImageViewBottom == null) {
            Log.e(TAG, "Overlay views not initialized");
            return;
        }
        
        activity.runOnUiThread(() -> {
            try {
                // 加载底层图片
                if (bottomAssetPath != null && !bottomAssetPath.isEmpty()) {
                    String bottomFullPath = "assets/main/native/" + bottomAssetPath.substring(0, 2) + "/" + bottomAssetPath + ".png";
                    Bitmap bottomBitmap = loadBitmapFromAssets(activity, bottomFullPath);
                    if (bottomBitmap != null) {
                        overlayImageViewBottom.setImageBitmap(bottomBitmap);
                        overlayImageViewBottom.setVisibility(View.VISIBLE);
                    } else {
                        overlayImageViewBottom.setImageBitmap(null);
                        overlayImageViewBottom.setVisibility(View.GONE);
                    }
                } else {
                    overlayImageViewBottom.setImageBitmap(null);
                    overlayImageViewBottom.setVisibility(View.GONE);
                }

                // 加载顶层图片
                if (topAssetPath != null && !topAssetPath.isEmpty()) {
                    String topFullPath = "assets/main/native/" + topAssetPath.substring(0, 2) + "/" + topAssetPath + ".png";
                    Bitmap topBitmap = loadBitmapFromAssets(activity, topFullPath);
                    if (topBitmap != null) {
                        overlayImageViewTop.setImageBitmap(topBitmap);
                        overlayImageViewTop.setVisibility(View.VISIBLE);
                    } else {
                        overlayImageViewTop.setImageBitmap(null);
                        overlayImageViewTop.setVisibility(View.GONE);
                    }
                } else {
                    overlayImageViewTop.setImageBitmap(null);
                    overlayImageViewTop.setVisibility(View.GONE);
                }

                isVisible = (overlayImageViewTop.getVisibility() == View.VISIBLE) || (overlayImageViewBottom.getVisibility() == View.VISIBLE);
                currentImagePath = topAssetPath + (bottomAssetPath != null ? ("+" + bottomAssetPath) : "");
                
                Log.d(TAG, "Image overlay shown successfully");
            } catch (Exception e) {
                Log.e(TAG, "Error showing overlay", e);
            }
        });
    }
    
    public void hideOverlay() {
        if (overlayImageViewTop == null || overlayImageViewBottom == null) {
            Log.e(TAG, "Overlay views not initialized");
            return;
        }
        
        activity.runOnUiThread(() -> {
            overlayImageViewTop.setVisibility(View.GONE);
            overlayImageViewTop.setImageBitmap(null);
            overlayImageViewBottom.setVisibility(View.GONE);
            overlayImageViewBottom.setImageBitmap(null);
            isVisible = false;
            
            Log.d(TAG, "Image overlay hidden");
        });
    }
    
    public void cleanup() {
        if (overlayView != null && parentContainer != null) {
            activity.runOnUiThread(() -> {
                try {
                    // 从父容器中移除视图
                    parentContainer.removeView(overlayView);
                    Log.d(TAG, "Overlay view removed from container");
                } catch (Exception e) {
                    Log.e(TAG, "Error removing overlay view from container", e);
                }
                
                // 清理引用
                overlayView = null;
                parentContainer = null;
                currentImagePath = null;
                isVisible = false;
                
                Log.d(TAG, "ImageLayerManager cleaned up");
            });
        }
    }
    
    public boolean isVisible() {
        return isVisible;
    }
    
    public String getCurrentImagePath() {
        return currentImagePath;
    }
    
    // 从assets加载Bitmap
    private Bitmap loadBitmapFromAssets(Context context, String path) {
        AssetManager assetManager = context.getAssets();
        InputStream is = null;
        Bitmap bitmap = null;
        try {
            Log.d(TAG, "try open asset: " + path);
            is = assetManager.open(path);
            bitmap = BitmapFactory.decodeStream(is);
            return bitmap;
        } catch (IOException e) {
            Log.e(TAG, "Error loading bitmap from assets: " + path, e);
            return null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing input stream", e);
                }
            }
        }
    }
}
