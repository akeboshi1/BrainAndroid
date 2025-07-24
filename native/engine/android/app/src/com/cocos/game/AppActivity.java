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

import static com.jujie.audiosdk.Constant.REQUEST_LOCATION_PERMISSION;
import static com.jujie.audiosdk.Constant.REQUEST_RECORD_AUDIO_ASR_PERMISSION;
import static com.jujie.audiosdk.Constant.REQUEST_RECORD_AUDIO_FSR_PERMISSION;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.content.res.Configuration;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;

import android.content.pm.PackageManager;
import android.widget.Toast;

import com.tencent.mm.opensdk.modelbiz.WXLaunchMiniProgram;
import com.tencent.mm.opensdk.openapi.IWXAPI;
import com.tencent.mm.opensdk.openapi.WXAPIFactory;


import com.cocos.lib.JsbBridge;
import com.cocos.service.SDKWrapper;
import com.cocos.lib.CocosActivity;
import com.jujie.audiosdk.ASRManager;
import com.jujie.audiosdk.AddressManager;
import com.jujie.audiosdk.FSRManager;
import com.jujie.audiosdk.Helper;
import com.jujie.audiosdk.PaipaiCaptureActivity;
import com.jujie.audiosdk.TTSManager;
import com.jujie.rendersdk.CameraXManager;
import com.jujie.rendersdk.ImageLayerManager;

import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.Lifecycle;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import com.cocos.game.LogcatCapture;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.File;

public class AppActivity extends CocosActivity implements LifecycleOwner{
    private Activity instance;
    private Map<String, Object> args;
    private TelephonyManager telephonyManager;
    private IWXAPI api;

    private CameraXManager cameraXManager;
    private View cameraView;
    private ImageLayerManager imageLayerManager;
    private View overlayView;

    // 添加LifecycleRegistry成员变量
    private LifecycleRegistry lifecycleRegistry;


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

    private void sendWXMiniReq(String path) {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
            for (Signature signature : packageInfo.signatures) {
                MessageDigest md = MessageDigest.getInstance("MD5");
                md.update(signature.toByteArray());
                String md5Signature = bytesToHex(md.digest()); // 这是你要填的
                Log.d("MD5_SIGNATURE", md5Signature);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        WXLaunchMiniProgram.Req req = new WXLaunchMiniProgram.Req();
        req.userName = "gh_965f61b76764"; // 这是小程序的原始 ID
        req.path = path;             // 小程序内页面路径
        req.miniprogramType = WXLaunchMiniProgram.Req.MINIPROGRAM_TYPE_PREVIEW;
        api.sendReq(req);

    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {

        Uri uri = getIntent().getData();
        if (uri != null && "paipai".equals(uri.getScheme())) {
            String target = uri.getQueryParameter("target");

            Log.d("AppActivity", "Received URI: " + uri.toString());
            Log.d("AppActivity", "Target: " + target);

            if ("home".equals(target)) {
                Intent intent = new Intent(this, AppActivity.class);
                startActivity(intent);
                finish();
            }
        }
        api = WXAPIFactory.createWXAPI(this, "wx763bc34e94cf5aaf", false);
        api.registerApp("wx763bc34e94cf5aaf");

        super.onCreate(savedInstanceState);

        // 初始化LifecycleRegistry
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);

        // 启动日志捕获
        LogcatCapture.startCapturing();

        // DO OTHER INITIALIZATION BELOW
        SDKWrapper.shared().init(this);

        instance = this;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            registerActivityLifecycleCallbacks(new AppLifecycleTracker());
        }

        // 注册生命周期回调
        ConnectivityMonitor.register(this, () -> {
            Log.d("AppActivity", "Network is available");
            if (!LogcatCapture.isAlive() || !LogcatCapture.isConnecting()) {
                LogcatCapture.connect();
            }

        });


        JsbBridge.setCallback(new JsbBridge.ICallback() {
            @Override
            public void onScript(String arg0, String arg1) {
                //TO DO
                Log.d("AppActivity","on script: "+arg0 +","+arg1);

                ///////////////////////////fsr////////////////////////

                if (arg0.equals("FSR") && arg1.equals("start")) {
                    Log.d("AppActivity", "FSR script: " + arg1);
                    FSRManager.start(instance);
                }
                if (arg0.equals("FSR") && arg1.equals("stop")) {
                    Log.d("AppActivity", "FSR script: " + arg1);
                    FSRManager.stop();
                }

                ///////////////////////////fsr////////////////////////
                if(arg0.equals("ASR") && arg1.startsWith("connect")){
                    Map<String, Object> result = parseCommand(arg1);

                    Log.d("AppActivity","parse command reslut" + result);

                    String func = (String) result.get("function");
                    Map<String, Object> param = (Map<String, Object>) result.get("param");

                    Log.d("AppActivity","ASR script: "+arg1);
                    ASRManager.start(instance, param);
                    args = param;
                }

                if(arg0.equals("ASR") && arg1.equals("close")){
                    Log.d("AppActivity","ASR script: "+arg1);
                    ASRManager.close();
                }

                if(arg0.equals("TTS")){
                    Log.d("AppActivity","TTS: "+arg1);
                }
                if(arg0.equals("TTS") && arg1.equals("connect")){
                    TTSManager.connect();
                    return;
                }

                if(arg0.equals("TTS") && arg1.equals("close")){
                    TTSManager.closeTTS();
                    return;
                }

                // tts send
                if(arg0.equals("TTS")){
                    try {
                        JSONObject jsonObject = new JSONObject(arg1);
                        Log.d("AppActivity", "===>"+jsonObject.toString());
                        String uid = jsonObject.getString("uid");
                        String text = jsonObject.getString("text");
                        TTSManager.send(uid, text);
                    } catch (JSONException e) {
                        Log.d("AppActivity", "parse json fail.");
                    }
                }

                // address start
                if(arg0.equals("ADDRESS") && arg1.equals("start")){
                    AddressManager.start(instance);
                }

                if(arg0.equals("QRCODE") && arg1.equals("scan")){

                    Log.d("AppActivity", "scan qrcode");
                    Intent intent = new Intent(instance, PaipaiCaptureActivity.class);
                    // instance.startActivityForResult(intent, 1002);
                    startActivityForResult(intent, 1002);
                }

                if(arg0.equals("DEVICE") && arg1.equals("info")){

                    String androidId = Settings.Secure.getString(
                            instance.getContentResolver(),
                            Settings.Secure.ANDROID_ID
                    );

                    Helper.uuid = androidId;

                    HashMap<String, String> map = new HashMap<>();
                    map.put("deviceId", androidId);

                    JSONObject jsonObject = new JSONObject(map);
                    String result = jsonObject.toString();

                    JsbBridge.sendToScript("DEVICEInfo", result);
                }

                if (arg0.equals("WXPAY")) {

                    Log.d("AppActivity", "WXPAY script: " + arg1);
                    HashMap order = new HashMap();
                    try {
                        JSONObject jsonObject = new JSONObject(arg1);
                        order.put("order_id", jsonObject.getString("order_id"));
//                        order.put("order_amount", jsonObject.getString("order_amount"));
                    } catch (JSONException e) {
                        Log.e("AppActivity", "parse wxpay json fail.");
                    }
                    sendWXMiniReq("/pages/orders/index?order_id=" + order.get("order_id"));
                }

                if (arg0.equals("CAMERA") && arg1.equals("start")) {
                    Log.d("AppActivity", "CAMERA start");
                    // 确保在主线程中执行UI操作
                    instance.runOnUiThread(() -> {
                        if (cameraXManager == null) {
                            cameraXManager = new CameraXManager(instance, (LifecycleOwner) instance);
                            cameraView = cameraXManager.createCameraView();
                            // 将相机视图添加到当前Activity的根布局中
                            instance.addContentView(cameraView, new FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                            ));
                        }
                        cameraXManager.showCameraPreview();
                    });
                }

                if (arg0.equals("CAMERA") && arg1.equals("stop")) {
                    Log.d("AppActivity", "CAMERA stop");
                    // 确保在主线程中执行UI操作
                    instance.runOnUiThread(() -> {
                        if (cameraXManager != null) {
                            cameraXManager.hideCameraPreview();
                        }
                    });
                }

                if (arg0.equals("CAMERA") && arg1.equals("getPremission")) {
                    Log.d("AppActivity", "CAMERA getPermission");
                    // 检查相机权限
                    if (ContextCompat.checkSelfPermission(instance, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(instance, new String[]{Manifest.permission.CAMERA}, 1003);
                    } else {
                        // 权限已授予，发送成功消息给脚本
                        JSONObject result = new JSONObject();
                        try {
                            result.put("code", 0);
                            result.put("message", "camera permission granted");
                        } catch (JSONException e) {
                            Log.e("AppActivity", "JSON error", e);
                        }
                        JsbBridge.sendToScript("CAMERAPermissionResult", result.toString());
                    }
                }

                if (arg0.equals("CAMERARECORDER") && arg1.equals("start")) {
                    if (cameraXManager != null) {
                        Log.d("AppActivity", "CAMERARECORDER start");
                        cameraXManager.startRecording();
                    } else {
                        Log.e("AppActivity", "CameraXManager not initialized");
                    }
                }

                if (arg0.equals("CAMERARECORDER") && arg1.equals("stop")) {
                    Log.d("AppActivity", "CAMERARECORDER stop");
                    if (cameraXManager != null) {
                        cameraXManager.stopRecording();
                    } else {
                        Log.e("AppActivity", "CameraXManager not initialized");
                    }
                }

                if (arg0.equals("CAMERARECORDER") && arg1.equals("stopWithoutSave")) {
                    Log.d("AppActivity", "CAMERARECORDER stopWithoutSave");
                    if (cameraXManager != null) {
                        cameraXManager.stopRecordingWithoutSave();
                    }
                }

                if (arg0.equals("CAMERAOVERLAY")) {
                    Log.d("AppActivity", "CAMERAOVERLAY: " + arg1);
                    String[] paths = arg1.split(",", -1); // -1 保证分割后即使末尾为空也保留
                    String topPath = paths.length > 0 ? paths[0] : null;
                    String bottomPath = (paths.length > 1 && !paths[1].isEmpty()) ? paths[1] : null;
                    Log.d("AppActivity", "topPath: " + topPath);
                    Log.d("AppActivity", "bottomPath: " + bottomPath);
                    // 确保在主线程中执行UI操作
                    instance.runOnUiThread(() -> {
                        // 使用单例模式获取 ImageLayerManager 实例
                        imageLayerManager = ImageLayerManager.getInstance(instance);

                        // 使用CameraXManager的容器
                        if (cameraXManager != null) {
                            FrameLayout cameraContainer = cameraXManager.getCameraContainer();
                            if (cameraContainer != null) {
                                overlayView = imageLayerManager.createOverlayView(cameraContainer);
                                imageLayerManager.showOverlay(topPath, bottomPath);
                            } else {
                                Log.e("AppActivity", "Camera container is null");
                            }
                        } else {
                            Log.e("AppActivity", "CameraXManager is null, please start camera first");
                        }
                    });
                }

                if (arg0.equals("CAMERAOVERLAYHIDE")) {
                    Log.d("AppActivity", "CAMERAOVERLAYHIDE");
                    // 确保在主线程中执行UI操作
                    instance.runOnUiThread(() -> {
                        // 使用单例模式获取 ImageLayerManager 实例
                        ImageLayerManager manager = ImageLayerManager.getInstance(instance);
                        if (manager != null) {
                            manager.hideOverlay();
                        }
                    });
                }

                if (arg0.equals("POSTVIDEO")) {
                    Log.d("AppActivity", "POSTVIDEO: " + arg1);
                    //arg1是json字符串
                    try {
                        JSONObject jsonObject = new JSONObject(arg1);
                        String absolutePath = jsonObject.getString("absolutePath");
                        String activity_id = jsonObject.getString("activity_id");
                        String token = jsonObject.getString("token");
                        int group_size = jsonObject.getInt("group_size");

                        Log.d("AppActivity", "absolutePath: " + absolutePath);
                        Log.d("AppActivity", "activity_id: " + activity_id);
                        Log.d("AppActivity", "token: " + token);
                        Log.d("AppActivity", "group_size: " + group_size);

                        // 在后台线程中执行视频上传，避免阻塞主线程
                        new Thread(() -> {
                            PostVideoData.postVideoForScore(new File(absolutePath), activity_id, token, group_size);
                        }).start();
                    } catch (JSONException e) {
                        Log.e("AppActivity", "POSTVIDEO JSON parse error: " + e.getMessage());
                        // 发送错误消息给脚本
                        JSONObject error = new JSONObject();
                        try {
                            error.put("code", 1);
                            error.put("message", "JSON parse error: " + e.getMessage());
                        } catch (JSONException je) {
                            Log.e("AppActivity", "Error creating error JSON", je);
                        }
                        JsbBridge.sendToScript("POSTVIDEODATAERROR", error.toString());
                    }
                }
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        Log.d("AppActivity", "onRequestPermissionsResult: requestCode = " + requestCode + ", permissions = " + Arrays.toString(permissions) + ", grantResults = " + Arrays.toString(grantResults));

        if (requestCode == REQUEST_RECORD_AUDIO_ASR_PERMISSION) {
            boolean recordAudioPermissionGranted = isPermissionGranted(permissions, grantResults[0], new String[]{android.Manifest.permission.RECORD_AUDIO});
//            boolean recordAudioPermissionGranted = false;
//            // 检查请求的权限是否被授予
//            for (int i = 0; i < permissions.length; i++) {
//                String permission = permissions[i];
//
//                if (permission.equals(android.Manifest.permission.RECORD_AUDIO)) {
//                    if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//                        recordAudioPermissionGranted = true;
//                        break;
//                    } else {
//                        recordAudioPermissionGranted = false;
//                    }
//                }
//            }
//
//            Log.d("AppActivity", "recordAudioPermissionGranted = " + recordAudioPermissionGranted);

            if (recordAudioPermissionGranted) {
                // 权限已授予，执行需要该权限的操作
                ASRManager.start(this, args);

            } else {
                // 权限被拒绝，处理拒绝的情况
            }
        }else if(requestCode == REQUEST_RECORD_AUDIO_FSR_PERMISSION){
            boolean recordAudioPermissionGranted = isPermissionGranted(permissions, grantResults[0], new String[]{android.Manifest.permission.RECORD_AUDIO});
            if(recordAudioPermissionGranted){
                FSRManager.start(this);
            } else{
                // 权限被拒绝，处理拒绝的情况
                JSONObject error = new JSONObject();
                try {
                    error.put("code", 1); // 1 表示权限被拒绝
                    error.put("error", "permission denied");
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
                JsbBridge.sendToScript("FSRResult", error.toString());
            }


        }else if(requestCode == REQUEST_LOCATION_PERMISSION){
            Log.d("AppActivity", "check location permission");

            boolean locationPermissionGranted = false;
            // 检查请求的权限是否被授予
            // locationPermissionGranted = isPermissionGranted(permissions, grantResults, locationPermissionGranted);
            locationPermissionGranted = isPermissionGranted(permissions, grantResults[0], new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});

            Log.d("AppActivity", "locationPermissionGranted = " + locationPermissionGranted);

            if (locationPermissionGranted) {
                // 权限已授予，执行需要该权限的操作
                AddressManager.start(this);
            } else {
                // 权限被拒绝，处理拒绝的情况
            }

        }else if(requestCode == 1003){
            Log.d("AppActivity", "check camera permission");

            boolean cameraPermissionGranted = isPermissionGranted(permissions, grantResults[0], new String[]{Manifest.permission.CAMERA});

            Log.d("AppActivity", "cameraPermissionGranted = " + cameraPermissionGranted);

            JSONObject result = new JSONObject();
            try {
                if (cameraPermissionGranted) {
                    result.put("code", 0);
                    result.put("message", "camera permission granted");
                } else {
                    result.put("code", 1);
                    result.put("message", "camera permission denied");
                }
            } catch (JSONException e) {
                Log.e("AppActivity", "JSON error", e);
            }
            JsbBridge.sendToScript("CAMERAPermissionResult", result.toString());
        }
    }

    private static boolean isPermissionGranted(String[] permissions, int grantResult, String[] expectedPermissions ) {
        for (int i = 0; i < permissions.length; i++) {
            String permission = permissions[i];

            if(Arrays.asList(expectedPermissions).contains(permission)){
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    return true;
                }
            };
        }
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);
        SDKWrapper.shared().onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
        SDKWrapper.shared().onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);

        // 清理ImageLayerManager单例
        ImageLayerManager.clearInstance();
        imageLayerManager = null;
        overlayView = null;

        // 注销网络监控器，避免内存泄漏
        ConnectivityMonitor.unregister(this);

        // Workaround in https://stackoverflow.com/questions/16283079/re-launch-of-activity-on-home-button-but-only-the-first-time/16447508
        if (!isTaskRoot()) {
            return;
        }
        SDKWrapper.shared().onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d("AppActivity", "onActivityResult: requestCode = " + requestCode + ", resultCode = " + resultCode + ", data = " + data);
        Log.d("AppActivity",  data.getStringExtra("SCAN_RESULT"));
        if(data == null){
            Log.d("AppActivity", "data is null");
            return;
        }

        Log.d("AppActivity", "SCAN_RESULT: " + data.getStringExtra("SCAN_RESULT"));

        super.onActivityResult(requestCode, resultCode, data);
        SDKWrapper.shared().onActivityResult(requestCode, resultCode, data);

        if (requestCode == 1002 && resultCode == RESULT_OK) {
            String scannedResult = data.getStringExtra("SCAN_RESULT");
            if(scannedResult == null){
                return;
            }
            // 处理扫描结果
            Toast.makeText(this, "扫码结果: " + scannedResult, Toast.LENGTH_SHORT).show();
            String jsonData = "{\"code\":\"" + scannedResult.replaceAll("\"", "'") + "\"}";
            JsbBridge.sendToScript("QRCODEResult", jsonData);
        }

    }

    @Override
    protected void onNewIntent(Intent intent) {
        Log.e("SCHEME", "onNewIntent: " + intent.getDataString());

        super.onNewIntent(intent);
        SDKWrapper.shared().onNewIntent(intent);
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        SDKWrapper.shared().onRestart();
    }

    @Override
    protected void onStop() {
        super.onStop();
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        SDKWrapper.shared().onStop();
    }

    @Override
    public void onBackPressed() {
        SDKWrapper.shared().onBackPressed();
        super.onBackPressed();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        SDKWrapper.shared().onConfigurationChanged(newConfig);
        super.onConfigurationChanged(newConfig);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        SDKWrapper.shared().onRestoreInstanceState(savedInstanceState);
        super.onRestoreInstanceState(savedInstanceState);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        SDKWrapper.shared().onSaveInstanceState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onStart() {
        super.onStart();
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
        SDKWrapper.shared().onStart();
    }

    @Override
    public void onLowMemory() {
        SDKWrapper.shared().onLowMemory();
        super.onLowMemory();
    }

    public static Map<String, Object> parseCommand(String command) {
        // 定义结果 HashMap
        Map<String, Object> result = new HashMap<>();

        // 判断命令是否带参数
        if (command.contains("(") && command.contains(")")) {
            // 提取函数名（括号之前的部分）
            String functionName = command.substring(0, command.indexOf("(")).trim();
            result.put("function", functionName);

            // 提取括号中的参数
            String paramString = command.substring(command.indexOf("(") + 1, command.lastIndexOf(")")).trim();

            if (paramString.isEmpty()) {
                // 如果没有参数，将 param 设置为 null
                result.put("param", null);
            } else {
                // 如果有参数，解析为 HashMap
                result.put("param", parseParam(paramString));
            }
        }

        return result;
    }

    public static Map<String, Object> parseParam(String paramString) {
        // 去掉首尾的花括号
        paramString = paramString.trim();
        if (paramString.startsWith("{") && paramString.endsWith("}")) {
            paramString = paramString.substring(1, paramString.length() - 1).trim();
        }

        // 将单引号替换为双引号
        paramString = paramString.replace("'", "\"");

        Map<String, Object> paramMap = new HashMap<>();

        // 拆分每一对 key: value
        String[] pairs = paramString.split(",");
        for (String pair : pairs) {
            // 去掉空格
            pair = pair.trim();

            // 分割 key 和 value
            String[] keyValue = pair.split(":");
            if (keyValue.length == 2) {
                String key = keyValue[0].trim();
                String value = keyValue[1].trim();

                // 如果值是数字，则转换为数字类型
                if (value.matches("-?\\d+(\\.\\d+)?")) {
                    // 数字类型
                    paramMap.put(key, parseNumber(value));
                } else {
                    // 其他值都作为字符串处理
                    paramMap.put(key, value.replace("\"", ""));
                }
            }
        }

        return paramMap;
    }

    // 解析数字
    private static Object parseNumber(String value) {
        if (value.contains(".")) {
            return Double.parseDouble(value);
        } else {
            return Integer.parseInt(value);
        }
    }


    public static void main(String[] args) {
        String command = "test()";
        Map<String, Object> result = parseCommand(command);
        System.out.println(result);

        command = "test(123)";
        result = parseCommand(command);
        System.out.println(result);

        command = "test(123, 'hello', 3.14)";
        result = parseCommand(command);
        System.out.println(result);

        command = "connect()";
        result = parseCommand(command);
        System.out.println(result);

        command = "connect({id:1, name:'hello', age: 29.39})";
        result = parseCommand(command);
        System.out.println(result);



    }

    // 实现LifecycleOwner接口的getLifecycle方法
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }
}
