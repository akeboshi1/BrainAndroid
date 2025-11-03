package com.jujie.paipai.common;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * 设备与应用版本信息缓存，启动时由 AppActivity 写入或调用 init() 初始化。
 */
public final class DeviceInfo {
    public static String BRAND = "";          // Build.BRAND
    public static String MODEL = "";          // Build.MODEL
    public static int VERSION_CODE = 0;        // 兼容旧 int 版本号
    public static long LONG_VERSION_CODE = 0;  // 长版本号(API 28+)
    public static String VERSION_NAME = "";   // versionName

    // === 新增账号/机构相关字段 ===
    public static String MpNo = "";           // 门店 / 经营点编号
    public static String OrgCode = "";        // 机构代码
    public static String UserName = "";       // 当前用户名称
    // ============================

    private static boolean initialized = false;

    private DeviceInfo() {}

    /**
     * 可选调用：如果未在 Activity 手动赋值，可用此方法一次性初始化。
     */
    public static synchronized void init(Context ctx) {
        if (initialized) return;
        BRAND = Build.BRAND;
        MODEL = Build.MODEL;
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                LONG_VERSION_CODE = pi.getLongVersionCode();
                VERSION_CODE = (int) LONG_VERSION_CODE;
            } else {
                VERSION_CODE = pi.versionCode;
                LONG_VERSION_CODE = VERSION_CODE;
            }
            VERSION_NAME = pi.versionName;
        } catch (Exception ignored) {}
        initialized = true;
    }

    /**
     * 更新登录 / 账号上下文信息（空值安全处理）。
     */
    public static synchronized void updateAccount(String mpNo, String orgCode, String userName) {
        MpNo = mpNo == null ? "" : mpNo;
        OrgCode = orgCode == null ? "" : orgCode;
        UserName = userName == null ? "" : userName;
    }

    /**
     * 清空账号信息（退出登录时调用）。
     */
    public static synchronized void clearAccount() {
        MpNo = "";
        OrgCode = "";
        UserName = "";
    }
}
