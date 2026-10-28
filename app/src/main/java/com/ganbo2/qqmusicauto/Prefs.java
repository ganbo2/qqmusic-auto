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
    public static final String KEY_USE_COORD = "use_coord";
    public static final String KEY_COORD_X = "coord_x";
    public static final String KEY_COORD_Y = "coord_y";
    /** 定时裕度（毫秒）：到点后再等这么久才点击。0 = 整点立刻点。 */
    public static final String KEY_BUFFER = "buffer_ms";

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

    /** 是否用「坐标点击」模式（适合文字搜不到的图片按钮）。 */
    public static boolean getUseCoord(Context c) {
        return get(c).getBoolean(KEY_USE_COORD, false);
    }

    /** 点击横坐标，占屏幕宽度的百分比（0~100）。默认 50 = 屏幕水平中点。 */
    public static int getCoordX(Context c) {
        return get(c).getInt(KEY_COORD_X, 50);
    }

    /** 点击纵坐标，占屏幕高度的百分比（0~100）。默认 90 = 靠底部。 */
    public static int getCoordY(Context c) {
        return get(c).getInt(KEY_COORD_Y, 90);
    }

    /** 定时裕度（毫秒）：到点后再等这么久才点击。默认 300ms，0 = 整点立刻点。 */
    public static int getBufferMs(Context c) {
        int v = get(c).getInt(KEY_BUFFER, 300);
        if (v < 0) v = 0;
        if (v > 5000) v = 5000;
        return v;
    }
}
