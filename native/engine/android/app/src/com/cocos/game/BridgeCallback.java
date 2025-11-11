package com.cocos.game;

import static androidx.core.app.ActivityCompat.requestPermissions;
import static com.jujie.audiosdk.Constant.REQUEST_CHAT_AUDIO_PERMISSION;
import static com.jujie.audiosdk.Constant.REQUEST_RECORD_CAMERA_PERMISSION;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager; // 添加以使用 PackageManager.PERMISSION_GRANTED
import android.provider.Settings;
import android.util.Log;
import android.widget.FrameLayout;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.cocos.lib.JsbBridge;
import com.jujie.audiosdk.ASRManager;
import com.jujie.audiosdk.AddressManager;
import com.jujie.audiosdk.FSRManager;
import com.jujie.audiosdk.Helper;
import com.jujie.audiosdk.PaipaiCaptureActivity;
import com.jujie.paipai.chat.CocosChatListener;
import com.jujie.paipai.chat.VoiceChatClient;
import com.jujie.paipai.common.DeviceInfo;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 抽离的 JSB 回调逻辑，负责处理来自脚本层的所有指令。
 */
public class BridgeCallback implements JsbBridge.ICallback {

    private final AppActivity activity;
    private VoiceChatClient chatClient;

    public BridgeCallback(AppActivity activity) {
        this.activity = activity;
    }

    @Override
    public void onScript(String arg0, String arg1) {
        Log.d("BridgeCallback","on script: "+arg0 +","+arg1);

        // FSR 控制
        if (arg0.equals("FSR") && arg1.equals("start")) {
            FSRManager.start(activity);
            return;
        }

        if (arg0.equals("FSR") && arg1.equals("stop")) {
            FSRManager.stop();
            return;
        }

        if (arg0.equals("CHAT:RECORDING:GET_PERM") && arg1.equals("start")) {
            if (ContextCompat.checkSelfPermission(this.activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(this.activity, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_CHAT_AUDIO_PERMISSION);
                return;
            }else{
                JSONObject result = new JSONObject();
                try {
                    result.put("code", 0);
                    result.put("message", "audio permission granted");
                } catch (JSONException e) {
                    Log.e("BridgeCallback", "JSON error", e);
                }
                JsbBridge.sendToScript("CHAT:RECORDING:PERM", result.toString());
            }
        }

        if (arg0.equals("CHAT:START")) {
            Log.d("BridgeCallback","chat start params: "+arg1);

            try {
                JSONObject chatParams = new JSONObject(arg1);
                String token = chatParams.optString("token");
                String userNickName = chatParams.optString("userNickName");
                boolean isProduction = chatParams.optBoolean("isProduction", false);
                int characterId = chatParams.optInt("characterId", 1);

                if(chatClient == null){
                    VoiceChatClient.Listener listener = new CocosChatListener();
                    chatClient = new VoiceChatClient(this.activity, listener);
                }
//                String url = "wss://colapai.xinjiaxianglao.com/chat/voice-chat?token="+token+"&userNickName="+userNickName; // 默认测试环境
                Log.d("BridgeCallback","CHAT:START : "+ arg1);
                String qs = "?token=" + token+"&userNickName="+userNickName + "&characterId=" + characterId;
                String url = "wss://test.paipai.xinjiaxianglao.com/chat/voice-chat" + qs; // 默认测试环境
                if(isProduction){
                    url = "wss://colapai.xinjiaxianglao.com/chat/voice-chat" + qs; // 默认测试环境
                }

//                String versionName = DeviceInfo.VERSION_NAME;
//                if(!versionName.toLowerCase().endsWith("test")){
//                    url = "wss://colapai.xinjiaxianglao.com/chat/voice-chat?token="+token; // 生产环境
//                }
                chatClient.startChat(url);
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
            return;
        }

        if (arg0.equals("CHAT:STOP")) {
            if(chatClient != null){
                chatClient.stopChat();
                chatClient = null;
            }
            return;
        }

        if(arg0.equals("CHAT:RECORDING:START")){
            if(chatClient != null){
                chatClient.startRecording();
            } else {
                Log.e("BridgeCallback", "Chat client is not initialized");
                JsbBridge.sendToScript("CHAT:RECORDING:ERROR", "Chat client is not initialized");
            }
            return;
        }

        if (arg0.equals("CHAT:RECORDING:STOP")) {
            if(chatClient != null){
                chatClient.setEnableAsr(false); // 客户端关闭
                chatClient.stopRecording();
            } else {
                Log.e("BridgeCallback", "Chat client is not initialized");
                JsbBridge.sendToScript("CHAT:RECORDING:ERROR", "Chat client is not initialized");
            }
            return;
        }

        if (arg0.equals("CHAT:MODE:SWITCH") ) {
            try {
                JSONObject jsonObject = new JSONObject(arg1);
                String mode = jsonObject.optString("mode");
                String songName = jsonObject.optString("songName");
                if (chatClient != null) {
                    chatClient.switchMode(mode, jsonObject);
                } else {
                    Log.e("BridgeCallback", "Chat client is not initialized");
                    JsbBridge.sendToScript("CHAT:MODE:ERROR", "Chat client is not initialized");
                }
            }catch (JSONException e) {
                Log.e("BridgeCallback", "JSON error", e);
            }
            return;
        }

        if (arg0.equals("CHAT:SONG:PAUSE")) {
            if (chatClient != null) {
                chatClient.pauseSong();
            } else {
                Log.e("BridgeCallback", "Chat client is not initialized");
                JsbBridge.sendToScript("CHAT:SONG:ERROR", "Chat client is not initialized");
            }
            return;
        }

        if (arg0.equals("CHAT:SONG:RESUME")) {
            if (chatClient != null) {
                chatClient.resumeSong();
            } else {
                Log.e("BridgeCallback", "Chat client is not initialized");
                JsbBridge.sendToScript("CHAT:SONG:ERROR", "Chat client is not initialized");
            }
            return;
        }

        // 地址定位
        if (arg0.equals("ADDRESS") && arg1.equals("start")) {
            AddressManager.start(activity);
            return;
        }

        // 扫码
        if (arg0.equals("QRCODE") && arg1.equals("scan")) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity,
                        new String[]{Manifest.permission.CAMERA}, REQUEST_RECORD_CAMERA_PERMISSION);
            } else {
                Intent intent = new Intent(activity, PaipaiCaptureActivity.class);
                activity.startActivityForResult(intent, 1002);
            }
            return;
        }

        // 设备信息（目前仅返回 deviceId，可扩展加入 DeviceInfo 字段）
        if(arg0.equals("DEVICE") && arg1.equals("info")){
            String androidId = Settings.Secure.getString(
                    activity.getContentResolver(),
                    Settings.Secure.ANDROID_ID
            );
            Helper.uuid = androidId;
            HashMap<String, String> map = new HashMap<>();
            map.put("deviceId", androidId);
            JSONObject jsonObject = new JSONObject(map);
            JsbBridge.sendToScript("DEVICEInfo", jsonObject.toString());
            return;
        }

        // 获取APK版本信息
        if(arg0.equals("VERSION") && arg1.equals("info")){
            String versionInfo = activity.getVersionInfo();
            JsbBridge.sendToScript("VERSIONInfo", versionInfo);
            Log.d("BridgeCallback", "VERSIONInfo: " + versionInfo);
            return;
        }

        // 微信支付（跳转小程序）
        if (arg0.equals("PAYMENT:WXPAY")) {
            try {
                JSONObject jsonObject = new JSONObject(arg1);
                String orderId = jsonObject.getString("order_id");
                activity.sendWXMiniReq("/pages/orders/index?order_id=" + orderId);
            } catch (JSONException e) {
                Log.e("BridgeCallback", "parse wxpay json fail." );
            }
            return;
        }

        // 摄像头预览
        if (arg0.equals("CAMERA") && arg1.equals("start")) {
            activity.runOnUiThread(() -> {
                if (activity.cameraXManager == null) {
                    activity.cameraXManager = new com.jujie.rendersdk.CameraXManager(activity, activity);
                    activity.cameraView = activity.cameraXManager.createCameraView();
                    activity.addContentView(activity.cameraView, new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                    ));
                }
                activity.cameraXManager.showCameraPreview();
            });
            return;
        }
        if (arg0.equals("CAMERA") && arg1.equals("stop")) {
            activity.runOnUiThread(() -> {
                if (activity.cameraXManager != null) {
                    activity.cameraXManager.hideCameraPreview();
                }
            });
            return;
        }
        if (arg0.equals("CAMERA") && arg1.equals("getPremission")) { // 原拼写保持
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.CAMERA}, 1003);
            } else {
                JSONObject result = new JSONObject();
                try {
                    result.put("code", 0);
                    result.put("message", "camera permission granted");
                } catch (JSONException e) {
                    Log.e("BridgeCallback", "JSON error", e);
                }
                JsbBridge.sendToScript("CAMERAPermissionResult", result.toString());
            }
            return;
        }

        // 录像
        if (arg0.equals("CAMERARECORDER") && arg1.equals("start")) {
            if (activity.cameraXManager != null) {
                activity.cameraXManager.startRecording();
            } else {
                Log.e("BridgeCallback", "CameraXManager not initialized");
            }
            return;
        }
        if (arg0.equals("CAMERARECORDER") && arg1.equals("stop")) {
            if (activity.cameraXManager != null) {
                activity.cameraXManager.stopRecording();
            }
            return;
        }
        if (arg0.equals("CAMERARECORDER") && arg1.equals("stopWithoutSave")) {
            if (activity.cameraXManager != null) {
                activity.cameraXManager.stopRecordingWithoutSave();
            }
            return;
        }

        // 叠层
        if (arg0.equals("CAMERAOVERLAY")) {
            String[] paths = arg1.split(",", -1);
            String topPath = paths.length > 0 ? paths[0] : null;
            String bottomPath = (paths.length > 1 && !paths[1].isEmpty()) ? paths[1] : null;
            activity.runOnUiThread(() -> {
                activity.imageLayerManager = com.jujie.rendersdk.ImageLayerManager.getInstance(activity);
                if (activity.cameraXManager != null) {
                    FrameLayout container = activity.cameraXManager.getCameraContainer();
                    if (container != null) {
                        activity.overlayView = activity.imageLayerManager.createOverlayView(container);
                        activity.imageLayerManager.showOverlay(topPath, bottomPath);
                    } else {
                        Log.e("BridgeCallback", "Camera container is null");
                    }
                } else {
                    Log.e("BridgeCallback", "CameraXManager is null, please start camera first");
                }
            });
            return;
        }
        if (arg0.equals("CAMERAOVERLAYHIDE")) {
            activity.runOnUiThread(() -> {
                com.jujie.rendersdk.ImageLayerManager manager = com.jujie.rendersdk.ImageLayerManager.getInstance(activity);
                if (manager != null) manager.hideOverlay();
            });
            return;
        }

        // 视频上传
        if (arg0.equals("POSTVIDEO")) {
            try {
                JSONObject jsonObject = new JSONObject(arg1);
                String absolutePath = jsonObject.getString("absolutePath");
                String task_id = jsonObject.getString("task_id");
                String activity_id = jsonObject.getString("activity_id");
                String token = jsonObject.getString("token");
                int group_size = jsonObject.getInt("group_size");
                boolean is_production = jsonObject.getBoolean("is_production");
                new Thread(() -> PostVideoData.postVideoForScore(new File(absolutePath), task_id, activity_id, token, group_size, is_production)).start();
            } catch (JSONException e) {
                Log.e("BridgeCallback", "POSTVIDEO JSON parse error: " + e.getMessage());
                JSONObject error = new JSONObject();
                try {
                    error.put("code", 1);
                    error.put("message", "JSON parse error: " + e.getMessage());
                } catch (JSONException je) {
                    Log.e("BridgeCallback", "Error creating error JSON", je);
                }
                JsbBridge.sendToScript("POSTVIDEODATAERROR", error.toString());
            }
            return;
        }

        // 退出应用
        if (arg0.equals("APP") && arg1.equals("exit")) {
            activity.runOnUiThread(() -> {
                JSONObject result = new JSONObject();
                try {
                    result.put("code", 0);
                    result.put("message", "app exit confirmed");
                } catch (JSONException e) {
                    Log.e("BridgeCallback", "JSON error", e);
                }
                JsbBridge.sendToScript("APPExitResult", result.toString());
                new android.os.Handler().postDelayed(() -> {
                    activity.finish();
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(0);
                }, 100);
            });
            return;
        }

        if (arg0.equals("LOGIN")) {

            Log.d("BridgeCallback", "LOGIN: " + arg1);

            try {
                JSONObject userInfo = new JSONObject(arg1);
                String mpNo = "" ;
                if(userInfo.has("mp_no"))
                    mpNo = userInfo.getString("mp_no");
                String orgCode = "";
                if(userInfo.has("org_code"))
                    orgCode = userInfo.getString("org_code");

                String userName = "";
                if(userInfo.has("username"))
                    userName =userInfo.getString("username");

                Log.d("BridgeCallback", "Updating user info: mpNo=" + mpNo + ", orgCode=" + orgCode + ", userName=" + userName);
                DeviceInfo.updateAccount(mpNo, orgCode, userName);

            } catch (JSONException e) {
                Log.e("BridgeCallback", "JSON error", e);
            }
        }
    }

    // 解析命令：形如 connect({id:1, name:'hello'})
    private static Map<String, Object> parseCommand(String command) {
        Map<String, Object> result = new HashMap<>();
        if (command.contains("(") && command.contains(")")) {
            String functionName = command.substring(0, command.indexOf("(")).trim();
            result.put("function", functionName);
            String paramString = command.substring(command.indexOf("(") + 1, command.lastIndexOf(")")).trim();
            if (paramString.isEmpty()) {
                result.put("param", null);
            } else {
                result.put("param", parseParam(paramString));
            }
        }
        return result;
    }

    private static Map<String, Object> parseParam(String paramString) {
        paramString = paramString.trim();
        if (paramString.startsWith("{") && paramString.endsWith("}")) {
            paramString = paramString.substring(1, paramString.length() - 1).trim();
        }
        paramString = paramString.replace("'", "\"");
        Map<String, Object> map = new HashMap<>();
        if (paramString.isEmpty()) return map;
        String[] pairs = paramString.split(",");
        for (String pair : pairs) {
            pair = pair.trim();
            String[] kv = pair.split(":");
            if (kv.length == 2) {
                String key = kv[0].trim();
                String value = kv[1].trim();
                if (value.matches("-?\\d+(\\.\\d+)?")) {
                    map.put(key, value.contains(".") ? Double.parseDouble(value) : Integer.parseInt(value));
                } else {
                    map.put(key, value.replace("\"", ""));
                }
            }
        }
        return map;
    }
}
