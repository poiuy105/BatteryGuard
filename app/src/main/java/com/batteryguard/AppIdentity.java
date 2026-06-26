package com.batteryguard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/**
 * app 唯一身份标识（无账号系统场景）。
 *
 * 首次调用时生成一个 128bit 随机 UUID 并持久化到 SharedPreferences，
 * 作为这台 app 实例的身份凭证，用于和 CH572 设备的 1对1 绑定。
 * 卸载重装会重新生成（视为新 app），此时需在设备端解绑后重新绑定。
 */
public class AppIdentity {

    private static final String PREFS_NAME = "BatteryGuardPrefs";
    private static final String KEY_APP_ID = "app_id";

    private static volatile byte[] cachedAppId = null;

    /**
     * 获取本机 app 的唯一标识（16 字节）。
     * 首次调用时生成并持久化，之后始终返回同一值。
     */
    public static byte[] getAppId(Context context) {
        if (cachedAppId != null) return cachedAppId;
        synchronized (AppIdentity.class) {
            if (cachedAppId != null) return cachedAppId;
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String idStr = prefs.getString(KEY_APP_ID, null);
            if (idStr == null) {
                idStr = UUID.randomUUID().toString();
                prefs.edit().putString(KEY_APP_ID, idStr).apply();
            }
            cachedAppId = uuidToBytes(idStr);
            return cachedAppId;
        }
    }

    /** UUID 字符串 → 16 字节（高 64 位 + 低 64 位，大端序拼接） */
    private static byte[] uuidToBytes(String idStr) {
        UUID uuid = UUID.fromString(idStr);
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        byte[] out = new byte[16];
        for (int i = 7; i >= 0; i--) {
            out[7 - i] = (byte) ((msb >>> (i * 8)) & 0xFF);
            out[15 - i] = (byte) ((lsb >>> (i * 8)) & 0xFF);
        }
        return out;
    }
}
