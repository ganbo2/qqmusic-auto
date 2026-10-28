package com.ganbo2.qqmusicauto;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 配置持久化：时间点、目标文字、包名、是否自动打开 App、导航步骤(JSON)。
 */
public class Prefs {
    public static final String NAME = "qqmusic_auto_config";
    public static final String KEY_TIMES = "times";
    public static final String KEY_TARGET = "target_text";
    public static final String KEY_PKG = "pkg";
    public static final String KEY_LAUNCH = "launch_app";
    public static final String KEY_STEPS = "steps";
    public static final String KEY_OFFSET = "ntp_offset";

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String getTimes(Context c) {
        return get(c).getString(KEY_TIMES, "08:00,12:30,20:00");
    }

    public static String getTarget(Context c) {
        return get(c).getString(KEY_TARGET, "立即提现");
    }

    public static String getPkg(Context c) {
        return get(c).getString(KEY_PKG, "com.tencent.qqmusic");
    }

    public static boolean getLaunch(Context c) {
        return get(c).getBoolean(KEY_LAUNCH, true);
    }

    public static String getSteps(Context c) {
        return get(c).getString(KEY_STEPS, defaultSteps());
    }

    public static long getOffset(Context c) {
        return get(c).getLong(KEY_OFFSET, 0);
    }

    public static void saveOffset(Context c, long off) {
        get(c).edit().putLong(KEY_OFFSET, off).apply();
    }

    /** 默认不导航：用户自己打开页面，时间到直接点。 */
    public static String defaultSteps() {
        return "[]";
    }
}
