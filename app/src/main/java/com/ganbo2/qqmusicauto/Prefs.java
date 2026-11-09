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
    public static final String KEY_SYNC_SOURCE = "ntp_sync_source";
    public static final String KEY_SYNC_RTT = "ntp_sync_rtt";
    public static final String KEY_SYNC_RELIABLE = "ntp_sync_reliable";
    public static final String KEY_SYNC_DETAIL = "ntp_sync_detail";
    public static final String KEY_SYNC_SPREAD = "ntp_sync_spread";
    public static final String KEY_SYNC_SAMPLES = "ntp_sync_samples";
    /** 最近一次「实测」得到的原始偏差：仅供展示，不等于排程采用的偏差。 */
    public static final String KEY_MEASURE_OFFSET = "last_measure_offset";
    public static final String KEY_MEASURE_AT = "last_measure_at";
    public static final String KEY_MEASURE_SOURCE = "last_measure_source";
    public static final String KEY_MEASURE_OK = "last_measure_ok";
    /** 当前排程采用的偏差是怎么来的（人话说明）。 */
    public static final String KEY_OFFSET_REASON = "offset_reason";
    /** true = 不做网络校时，直接用手机系统时钟（偏差恒为 0）。 */
    public static final String KEY_USE_SYSTEM_TIME = "use_system_time";
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

    /** 记录本次校时的来源与往返延迟，供界面判断偏差是否可信。 */
    public static void saveSyncInfo(Context c, String source, long rttMs, boolean reliable) {
        saveSyncInfo(c, source, rttMs, reliable, "", -1, 0);
    }

    /** 完整诊断信息：各源明细、多源极差、参与决策的样本数。 */
    public static void saveSyncInfo(Context c, String source, long rttMs, boolean reliable,
                                    String detail, long spreadMs, int sampleCount) {
        get(c).edit()
                .putString(KEY_SYNC_SOURCE, source)
                .putLong(KEY_SYNC_RTT, rttMs)
                .putBoolean(KEY_SYNC_RELIABLE, reliable)
                .putString(KEY_SYNC_DETAIL, detail)
                .putLong(KEY_SYNC_SPREAD, spreadMs)
                .putInt(KEY_SYNC_SAMPLES, sampleCount)
                .apply();
    }

    public static String getSyncSource(Context c) {
        return get(c).getString(KEY_SYNC_SOURCE, "尚未校时");
    }

    public static String getSyncDetail(Context c) {
        return get(c).getString(KEY_SYNC_DETAIL, "");
    }

    /** -1 表示只有一个样本或从未校时。 */
    public static long getSyncSpread(Context c) {
        return get(c).getLong(KEY_SYNC_SPREAD, -1);
    }

    public static int getSyncSampleCount(Context c) {
        return get(c).getInt(KEY_SYNC_SAMPLES, 0);
    }

    /** 记录一次校时尝试：实测原始偏差、来源、时刻、是否真的拿到结果。 */
    public static void saveMeasure(Context c, boolean attempted, long offsetMs,
                                   String source, long atMs) {
        get(c).edit()
                .putBoolean(KEY_MEASURE_OK, attempted)
                .putLong(KEY_MEASURE_OFFSET, offsetMs)
                .putString(KEY_MEASURE_SOURCE, source)
                .putLong(KEY_MEASURE_AT, atMs)
                .apply();
    }

    public static boolean wasMeasureAttempted(Context c) {
        return get(c).getBoolean(KEY_MEASURE_OK, false);
    }

    public static long getMeasuredOffset(Context c) {
        return get(c).getLong(KEY_MEASURE_OFFSET, 0);
    }

    public static long getMeasuredAt(Context c) {
        return get(c).getLong(KEY_MEASURE_AT, 0);
    }

    public static String getMeasuredSource(Context c) {
        return get(c).getString(KEY_MEASURE_SOURCE, "尚未校时");
    }

    public static void saveOffsetReason(Context c, String reason) {
        get(c).edit().putString(KEY_OFFSET_REASON, reason).apply();
    }

    public static String getOffsetReason(Context c) {
        return get(c).getString(KEY_OFFSET_REASON, "");
    }

    public static boolean getUseSystemTime(Context c) {
        return get(c).getBoolean(KEY_USE_SYSTEM_TIME, false);
    }

    public static void saveUseSystemTime(Context c, boolean v) {
        get(c).edit().putBoolean(KEY_USE_SYSTEM_TIME, v).apply();
    }

    /** -1 表示从未成功校时。 */
    public static long getSyncRtt(Context c) {
        return get(c).getLong(KEY_SYNC_RTT, -1);
    }

    public static boolean isSyncReliable(Context c) {
        return get(c).getBoolean(KEY_SYNC_RELIABLE, false);
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

    /** 定时裕度（毫秒）：正数=到点后延迟点击；负数=提前点击；0=整点立刻点。默认 300ms。 */
    public static int getBufferMs(Context c) {
        int v = get(c).getInt(KEY_BUFFER, 300);
        if (v < -5000) v = -5000;
        if (v > 5000) v = 5000;
        return v;
    }
}
