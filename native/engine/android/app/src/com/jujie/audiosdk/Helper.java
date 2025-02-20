package com.jujie.audiosdk;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

public class Helper {

    private static final String PREFS_NAME = "app_prefs";
    private static final String UUID_KEY = "app_uuid";

    public static String uuid = "";

    // 获取应用的 UUID
    public static String getAppUUID(Context context) {
        // 获取 SharedPreferences
        SharedPreferences sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // 检查是否已经存在 UUID
        String uuid = sharedPreferences.getString(UUID_KEY, null);

        // 返回 UUID
        return uuid;
    }

    public static String generateAppUUID(Context context) {
        // 获取 SharedPreferences
        SharedPreferences sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String uuid = UUID.randomUUID().toString();

        // 将生成的 UUID 保存到 SharedPreferences 中
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(UUID_KEY, uuid);
        editor.apply();

        return uuid;
    }



}
