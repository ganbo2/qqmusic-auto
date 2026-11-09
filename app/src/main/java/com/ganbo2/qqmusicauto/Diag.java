package com.ganbo2.qqmusicauto;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 触发链路诊断：把「闹钟响了 / 广播送达无障碍服务 / 点击已记录」分别落盘。
 *
 * <p>定时点击不生效时，旧版没有任何中间证据，只能靠猜。现在界面能直接显示断点：
 * 没「闹钟」记录 = 排程/后台权限问题；有闹钟没广播 = 无障碍服务没运行；
 * 广播到了没日志 = 点击或日志写入失败。</p>
 */
public class Diag {
    private static final String NAME = "qqmusic_auto_diag";
    private static final String KEY_ALARM_AT = "alarm_at";
    private static final String KEY_ALARM_INDEX = "alarm_index";
    private static final String KEY_ALARM_A11Y = "alarm_a11y";
    private static final String KEY_ALARM_OFFSET = "alarm_offset";
    private static final String KEY_TRIGGER_AT = "trigger_at";
    private static final String KEY_TRIGGER_TEST = "trigger_test";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** 闹钟触发（静态接收器里调用，不依赖无障碍服务是否存活）。 */
    public static void alarmFired(Context c, int index, boolean a11y, long offset) {
        sp(c).edit()
                .putLong(KEY_ALARM_AT, System.currentTimeMillis())
                .putInt(KEY_ALARM_INDEX, index)
                .putBoolean(KEY_ALARM_A11Y, a11y)
                .putLong(KEY_ALARM_OFFSET, offset)
                .apply();
    }

    /** 点击广播送达无障碍服务（动态接收器里调用）。 */
    public static void broadcastReceived(Context c, boolean test) {
        sp(c).edit()
                .putLong(KEY_TRIGGER_AT, System.currentTimeMillis())
                .putBoolean(KEY_TRIGGER_TEST, test)
                .apply();
    }

    /** 无障碍服务是否已在系统设置里启用（覆盖安装后国产 ROM 常会重置授权）。 */
    public static boolean isAccessibilityEnabled(Context c) {
        try {
            int on = Settings.Secure.getInt(c.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (on != 1) return false;
            String list = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(list)) return false;
            String full = new ComponentName(c, AutoClickService.class).flattenToString();
            String shortName = c.getPackageName() + "/.AutoClickService";
            for (String s : list.split(":")) {
                if (full.equalsIgnoreCase(s) || shortName.equalsIgnoreCase(s)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static String time(long ms) {
        if (ms <= 0) return "无";
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return f.format(new Date(ms));
    }

    /** 给界面显示的诊断摘要。 */
    public static String summary(Context c) {
        SharedPreferences p = sp(c);
        long a = p.getLong(KEY_ALARM_AT, 0);
        long t = p.getLong(KEY_TRIGGER_AT, 0);
        StringBuilder sb = new StringBuilder();
        sb.append("\n触发诊断：闹钟 ").append(time(a));
        if (a > 0) {
            sb.append("（#").append(p.getInt(KEY_ALARM_INDEX, -1)).append(" · ")
                    .append(p.getBoolean(KEY_ALARM_A11Y, false) ? "无障碍已开" : "无障碍未开")
                    .append(" · 偏差 ").append(p.getLong(KEY_ALARM_OFFSET, 0)).append("ms）");
        }
        sb.append(" → 广播 ").append(time(t));
        if (t > 0) sb.append(p.getBoolean(KEY_TRIGGER_TEST, false) ? "（测试）" : "（定时）");
        sb.append("\n无障碍服务：")
                .append(isAccessibilityEnabled(c) ? "已开启" : "未开启 ← 定时点击无法执行");
        return sb.toString();
    }
}
