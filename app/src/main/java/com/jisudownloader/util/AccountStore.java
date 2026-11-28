package com.jisudownloader.util;

import android.content.Context;
import android.content.SharedPreferences;

public final class AccountStore {
    private static final String PREF = "pan_accounts";
    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
    public static void save(Context c, String platform, String cookie) {
        sp(c).edit().putString(platform, cookie).apply();
    }
    public static String get(Context c, String platform) {
        return sp(c).getString(platform, "");
    }
    public static void logout(Context c, String platform) {
        sp(c).edit().remove(platform).apply();
    }
    public static boolean isLogin(Context c, String platform) {
        String v = get(c, platform);
        return v != null && !v.isEmpty();
    }
}
