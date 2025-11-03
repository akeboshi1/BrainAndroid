package com.jujie.paipai.wxapi;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.tencent.mm.opensdk.constants.ConstantsAPI;
import com.tencent.mm.opensdk.modelbase.BaseReq;
import com.tencent.mm.opensdk.modelbase.BaseResp;
import com.tencent.mm.opensdk.modelbiz.WXLaunchMiniProgram;
import com.tencent.mm.opensdk.openapi.IWXAPI;
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler;
import com.tencent.mm.opensdk.openapi.WXAPIFactory;

public class WXEntryActivity extends Activity implements IWXAPIEventHandler {

    // 替换为你在微信开放平台「移动应用」的 AppID，比如 "wx1234567890abcdef"
    private static final String WECHAT_APP_ID = "wx763bc34e94cf5aaf";

    private IWXAPI api;
    private static final String TAG = "WXEntryActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = WXAPIFactory.createWXAPI(this, WECHAT_APP_ID, false);
        // 非常关键：把当前 Intent 交给微信 SDK 解析
        boolean handled = api.handleIntent(getIntent(), this);
        if (!handled) {
            // 理论上很少走到这里，打印一下方便排查
            Log.w(TAG, "WeChat intent not handled");
            finish();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (api == null) {
            api = WXAPIFactory.createWXAPI(this, WECHAT_APP_ID, false);
        }
        api.handleIntent(intent, this);
    }

    // 微信会话向App发起的请求（一般用不到）
    @Override
    public void onReq(BaseReq req) {
        Log.d(TAG, "onReq: " + req.getType());
        finish(); // 无特别处理直接关掉
    }

    // App 需要处理的微信回调都在这里
    @Override
    public void onResp(BaseResp resp) {
        Log.d(TAG, "onResp: type=" + resp.getType() + ", errCode=" + resp.errCode);

        if (resp.getType() == ConstantsAPI.COMMAND_LAUNCH_WX_MINIPROGRAM) {
            // 小程序通过 <button open-type="launchApp"> 回跳
            WXLaunchMiniProgram.Resp r = (WXLaunchMiniProgram.Resp) resp;
            String extMsg = r.extMsg; // 对应小程序按钮的 app-parameter
            Log.d(TAG, "MiniProgram extMsg: " + extMsg);

            // 这里演示：弹个 Toast，然后路由到你的目标页面
            if (extMsg != null && !extMsg.isEmpty()) {
                //Toast.makeText(this, "From MiniProgram: " + extMsg, Toast.LENGTH_SHORT).show();
            }

            // 跳转到你的落地页（例如支付结果页）
            // 把 extMsg 传过去自己解析（常见是 JSON）
            Intent go = new Intent(this, com.cocos.game.AppActivity.class); // ← 改成你的目标 Activity
            go.putExtra("mini_ext_msg", extMsg);
            go.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(go);
        }

        // 处理完就结束当前回调页，避免在返回栈里停留
        finish();
    }
}
