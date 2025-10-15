/****************************************************************************
 Copyright (c) 2015-2016 Chukong Technologies Inc.
 Copyright (c) 2017-2018 Xiamen Yaji Software Co., Ltd.

 http://www.cocos2d-x.org

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in
 all copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 THE SOFTWARE.
 ****************************************************************************/
package com.cocos.game;

import static com.jujie.audiosdk.Constant.REQUEST_CHAT_AUDIO_PERMISSION;
import static com.jujie.audiosdk.Constant.REQUEST_LOCATION_PERMISSION;
import static com.jujie.audiosdk.Constant.REQUEST_RECORD_AUDIO_ASR_PERMISSION;
import static com.jujie.audiosdk.Constant.REQUEST_RECORD_AUDIO_FSR_PERMISSION;
import static com.jujie.audiosdk.Constant.REQUEST_RECORD_CAMERA_PERMISSION;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.content.res.Configuration;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.cocos.lib.CocosActivity;
import com.cocos.lib.JsbBridge;
import com.cocos.service.SDKWrapper;
import com.jujie.audiosdk.AddressManager;
import com.jujie.audiosdk.FSRManager;
import com.jujie.audiosdk.PaipaiCaptureActivity;
import com.tencent.mm.opensdk.constants.ConstantsAPI;
import com.tencent.mm.opensdk.modelbiz.WXLaunchMiniProgram;
import com.tencent.mm.opensdk.openapi.IWXAPI;
import com.tencent.mm.opensdk.openapi.WXAPIFactory;
import com.jujie.paipai.common.DeviceInfo;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;

// 仅供 BridgeCallback 访问的渲染相关引用
import com.jujie.rendersdk.CameraXManager;
import com.jujie.rendersdk.ImageLayerManager;
import java.io.File; // 仍被 onActivityResult/上传逻辑引用（保留以防后续扩展）

public class AppActivity extends CocosActivity implements LifecycleOwner {
    /**
     * App 主入口 Activity (Cocos 容器)
     * 职责:
     * 1. 采集并缓存基础设备 / 应用版本信息 (DeviceInfo)
     * 2. 处理 DeepLink/自定义 Scheme 启动
     * 3. 初始化并注册微信 SDK 刷新广播
     * 4. 绑定 JsbBridge 回调 (BridgeCallback) 将原生能力暴露给脚本层
     * 5. 管理生命周期同步 (给 SDKWrapper / LogcatCapture / Camera / Overlay)
     * 6. 处理运行期动态权限回调
     */
    // 供 BridgeCallback 使用的可包访问字段 (避免大量 setter，保持简单)
    Map<String, Object> args;                 // ASR 参数缓存（脚本层 connect 解析后保存）
    IWXAPI api;                               // 微信 SDK 实例
    CameraXManager cameraXManager;            // CameraX 封装管理器
    View cameraView;                          // 相机预览视图
    ImageLayerManager imageLayerManager;      // 叠层渲染管理
    View overlayView;                         // 叠层根视图引用

    private LifecycleRegistry lifecycleRegistry; // LifecycleOwner 实现所需
    private BroadcastReceiver screenStateReceiver; // 监听息屏/亮屏（如需前后台优化可扩展）

    /** 将字节数组转 16 进制字符串 (签名 MD5 输出使用) */
    private String bytesToHex(byte[] bytes) {
        final char[] hexArray = "0123456789ABCDEF".toCharArray();
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    /**
     * 获取当前APK版本信息
     * @return 包含版本号、版本名称等信息的JSON字符串
     * 说明: 包内访问，给 BridgeCallback 的版本查询调用使用。
     */
    String getVersionInfo() {
        try {
            Log.e("AppActivity", "getVersionInfo");
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            JSONObject versionInfo = new JSONObject();
            versionInfo.put("versionName", packageInfo.versionName);
            versionInfo.put("versionCode", packageInfo.versionCode);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                versionInfo.put("longVersionCode", packageInfo.getLongVersionCode());
            }
            return versionInfo.toString();
        } catch (Exception e) {
            Log.e("AppActivity", "获取版本信息失败", e);
            JSONObject errorInfo = new JSONObject();
            try {
                errorInfo.put("error", "获取版本信息失败: " + e.getMessage());
            } catch (JSONException je) {
                Log.e("AppActivity", "创建错误信息JSON失败", je);
            }
            return errorInfo.toString();
        }
    }

    /**
     * 跳转微信小程序
     * @param path 小程序内路径 (含查询参数)
     * 说明: 包内访问，给 BridgeCallback 的支付/订单相关调用使用。
     */
    void sendWXMiniReq(String path) {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
            if (packageInfo.signatures != null) {
                for (Signature signature : packageInfo.signatures) {
                    MessageDigest md = MessageDigest.getInstance("MD5");
                    md.update(signature.toByteArray());
                    String md5Signature = bytesToHex(md.digest());
                    Log.d("MD5_SIGNATURE", md5Signature);
                }
            }
        } catch (Exception e) {
            Log.w("AppActivity", "calc signature fail", e);
        }
        WXLaunchMiniProgram.Req req = new WXLaunchMiniProgram.Req();
        req.userName = "gh_965f61b76764";
        req.path = path;
        req.miniprogramType = WXLaunchMiniProgram.Req.MINIPTOGRAM_TYPE_RELEASE;
        api.sendReq(req);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // ====== 1) 采集设备 & 版本信息，写入 DeviceInfo ======
        // 仅在应用启动时执行一次（无需放到 Application，避免多处分散）
        String brand = Build.BRAND;
        String model = Build.MODEL;
        Log.d("AppActivity", "Brand: " + brand + ", Model: " + model );
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            Log.d("AppActivity", "Version Code: " + pi.versionCode);
            try {
                DeviceInfo.BRAND = brand;
                DeviceInfo.MODEL = model;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    long longCode = pi.getLongVersionCode();
                    try { DeviceInfo.class.getField("LONG_VERSION_CODE").setLong(null, longCode); } catch (Exception ignore) {}
                    DeviceInfo.VERSION_CODE = (int) longCode;
                } else {
                    DeviceInfo.VERSION_CODE = pi.versionCode;
                    try { DeviceInfo.class.getField("LONG_VERSION_CODE").setLong(null, pi.versionCode); } catch (Exception ignore) {}
                }
                DeviceInfo.VERSION_NAME = pi.versionName;
            } catch (Throwable t) {
                Log.w("AppActivity", "DeviceInfo 写入失败: " + t.getMessage());
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }

        // ====== 2) 处理外部启动 URI (DeepLink) ======
        // 支持 scheme: paipai://?target=home 之类调用，可按需要扩展更多参数
        Uri uri = getIntent().getData();
        if (uri != null && "paipai".equals(uri.getScheme())) {
            String target = uri.getQueryParameter("target");
            Log.d("AppActivity", "Received URI: " + uri);
            Log.d("AppActivity", "Target: " + target);
            if ("home".equals(target)) {
                startActivity(new Intent(this, AppActivity.class));
                finish();
            }
        }

        // ====== 3) 初始化微信 SDK 并注册刷新广播 ======
        // Android 13+ 需显式 flag；广播仅用于确保客户端拉起后重新 registerApp
        api = WXAPIFactory.createWXAPI(this, "wx763bc34e94cf5aaf", false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { api.registerApp("wx763bc34e94cf5aaf"); } },
                    new IntentFilter(ConstantsAPI.ACTION_REFRESH_WXAPP), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { api.registerApp("wx763bc34e94cf5aaf"); } },
                    new IntentFilter(ConstantsAPI.ACTION_REFRESH_WXAPP));
        }

        super.onCreate(savedInstanceState);
        // ====== 4) 初始化生命周期与日志捕获 ======
        // CREATED -> 后续在 onResume / onPause 中推进/回退
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        LogcatCapture.startCapturing();
        registerScreenStateReceiver();
        // ====== 5) 初始化通用 SDKWrapper ======
        // 若后续接入更多 Framework，可在此集中添加
        SDKWrapper.shared().init(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            registerActivityLifecycleCallbacks(new AppLifecycleTracker());
        }
        // ====== 6) 网络连通性监听 (恢复日志上传等) ======
        ConnectivityMonitor.register(this, () -> {
            Log.d("AppActivity", "Network is available");
            if (!LogcatCapture.isAlive() || !LogcatCapture.isConnecting()) LogcatCapture.connect();
        });
        // ====== 7) 绑定抽离后的 BridgeCallback (统一管理脚本指令) ======
        // 回调内部不做 UI 复杂状态存储，必要状态放在本 Activity 字段
        JsbBridge.setCallback(new BridgeCallback(this));
    }

    /**
     * 动态权限回调集中处理。
     * 覆盖: 录音(ASR/FSR), 定位, 相机预览, 扫码。
     * 说明: 仅在 granted 时执行对应启动逻辑，拒绝时返回脚本错误码。
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Log.d("AppActivity", "onRequestPermissionsResult: requestCode=" + requestCode + ", permissions=" + Arrays.toString(permissions));
        if (requestCode == REQUEST_RECORD_AUDIO_ASR_PERMISSION) {
            boolean granted = isPermissionGranted(permissions, grantResults[0], new String[]{Manifest.permission.RECORD_AUDIO});
            if (granted) {
                // 可在此恢复 ASRManager.start(this, args);
            }
        } else if (requestCode == REQUEST_RECORD_AUDIO_FSR_PERMISSION) {
            boolean granted = isPermissionGranted(permissions, grantResults[0], new String[]{Manifest.permission.RECORD_AUDIO});
            if (granted) {
                FSRManager.start(this);
            } else {
                JSONObject error = new JSONObject();
                try { error.put("code", 1).put("error", "permission denied"); } catch (JSONException ignored) {}
                JsbBridge.sendToScript("FSRResult", error.toString());
            }
        } else if (requestCode == REQUEST_LOCATION_PERMISSION) {
            boolean granted = isPermissionGranted(permissions, grantResults[0], new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
            if (granted) AddressManager.start(this);
        } else if (requestCode == 1003) { // CAMERA 权限
            boolean granted = isPermissionGranted(permissions, grantResults[0], new String[]{Manifest.permission.CAMERA});
            JSONObject result = new JSONObject();
            try { result.put("code", granted ? 0 : 1).put("message", granted ? "camera permission granted" : "camera permission denied"); } catch (JSONException ignored) {}
            JsbBridge.sendToScript("CAMERAPermissionResult", result.toString());
        } else if (requestCode == REQUEST_RECORD_CAMERA_PERMISSION) { // 扫码权限回调
            Intent intent = new Intent(this, PaipaiCaptureActivity.class);
            startActivityForResult(intent, 1002);
        } else if (requestCode == REQUEST_CHAT_AUDIO_PERMISSION) {
            boolean granted = isPermissionGranted(permissions, grantResults[0], new String[]{Manifest.permission.RECORD_AUDIO});
            Log.d("AppActivity", "REQUEST_CHAT_AUDIO_PERMISSION granted=" + granted);
             // 无论授予与否均通知脚本侧，避免脚本侧等待
            JsbBridge.sendToScript("CHAT:RECORD_AUDIO:PERMISSION", granted ? "1" : "0");
        }
    }

    /**
     * 简单权限数组匹配 (首个匹配即返回)。
     * 注意: grantResults 与 permissions 顺序由系统保证对应；这里只取第一个结果即可满足当前用例。
     */
    private static boolean isPermissionGranted(String[] permissions, int grantResult, String[] expectedPermissions ) {
        for (String permission : permissions) {
            for (String expected : expectedPermissions) {
                if (permission.equals(expected) && grantResult == PackageManager.PERMISSION_GRANTED) {
                    return true;
                }
            }
        }
        return false;
    }

    // ====== 生命周期桥接：将系统事件同步给 SDKWrapper / 日志组件 ======
    // 简化：使用方法链单行写法，逻辑保持清晰
    @Override protected void onResume() { // 进入前台
        super.onResume(); lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED); LogcatCapture.onAppComesToForeground(); SDKWrapper.shared().onResume();
    }
    @Override protected void onPause() { // 即将进入后台
        super.onPause(); lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED); LogcatCapture.onAppGoesToBackground(); SDKWrapper.shared().onPause();
    }
    @Override protected void onStop() { // 不再可见
        super.onStop(); lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED); SDKWrapper.shared().onStop();
    }
    @Override protected void onRestart() { // 从停止回到前台前
        super.onRestart(); SDKWrapper.shared().onRestart();
    }
    @Override protected void onNewIntent(Intent intent) { // singleTop / DeepLink 复用
        super.onNewIntent(intent); SDKWrapper.shared().onNewIntent(intent);
    }
    @Override protected void onSaveInstanceState(Bundle outState) { // 进程/配置变更前保存状态
        SDKWrapper.shared().onSaveInstanceState(outState); super.onSaveInstanceState(outState);
    }
    @Override protected void onRestoreInstanceState(Bundle state) { // 进程被杀后恢复
        SDKWrapper.shared().onRestoreInstanceState(state); super.onRestoreInstanceState(state);
    }

    /**
     * 资源释放与注销。
     * 注意: isTaskRoot() 检查用于规避系统因任务栈中其他 Activity 触发的重复 onDestroy。
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        unregisterScreenStateReceiver();
        LogcatCapture.stopCapturing();
        ImageLayerManager.clearInstance();
        imageLayerManager = null; overlayView = null; cameraXManager = null; cameraView = null;
        ConnectivityMonitor.unregister(this);
        if (!isTaskRoot()) return; // 避免重复销毁逻辑
        SDKWrapper.shared().onDestroy();
    }

    /**
     * 扫码 / 其他 Activity 返回结果。
     * 当前仅处理 requestCode=1002 的二维码扫描结果。
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        SDKWrapper.shared().onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1002 && resultCode == RESULT_OK && data != null) {
            String scannedResult = data.getStringExtra("SCAN_RESULT");
            if (scannedResult != null) {
                Toast.makeText(this, "扫码结果: " + scannedResult, Toast.LENGTH_SHORT).show();
                String jsonData = "{\"code\":\"" + scannedResult.replace("\"", "'") + "\"}";
                JsbBridge.sendToScript("QRCODEResult", jsonData);
            }
        }
    }

    @Override public void onBackPressed() { // 统一通过 SDKWrapper 扩展脚本侧 back 行为
        SDKWrapper.shared().onBackPressed(); super.onBackPressed();
    }
    @Override public void onConfigurationChanged(Configuration newConfig) { // 横竖屏/尺寸/语言变更
        SDKWrapper.shared().onConfigurationChanged(newConfig); super.onConfigurationChanged(newConfig);
    }

    /** LifecycleOwner 实现，供 CameraX / 其他依赖生命周期组件使用 */
    @Override
    public Lifecycle getLifecycle() { return lifecycleRegistry; }

    /** 注册屏幕状态广播：可用于节省资源或打点（现阶段仅日志输出） */
    private void registerScreenStateReceiver() {
        if (screenStateReceiver != null) return;
        screenStateReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) Log.d("AppActivity", "Screen off");
                else if (Intent.ACTION_SCREEN_ON.equals(action)) Log.d("AppActivity", "Screen on");
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenStateReceiver, filter);
            }
        } catch (Exception e) { Log.e("AppActivity", "register screen receiver fail", e); }
    }

    /** 注销屏幕状态广播，防止内存泄漏 */
    private void unregisterScreenStateReceiver() {
        if (screenStateReceiver == null) return;
        try { unregisterReceiver(screenStateReceiver); } catch (Exception e) { Log.e("AppActivity", "unregister screen receiver fail", e); }
        screenStateReceiver = null;
    }
}
