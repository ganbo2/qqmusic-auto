package com.ganbo2.qqmusicauto;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * 用 AlarmManager 注册多个每日时间点。
 * 关键：用户填写的是「北京时间」，且以网络校时为准。
 * 因此先把目标时间算成真实 UTC 毫秒，再用 NTP 偏差换算回系统时钟去 setExact，
 * 这样即使手机系统时间不准，闹钟也会在正确的真实北京时间响起。
 */
public class AlarmScheduler {
    public static final String EXTRA_INDEX = "index";
    /** 本次闹钟对应的「真实触发时刻」（含裕度），用于次日精确重排。 */
    public static final String EXTRA_FIRE_REAL_MS = "fire_real_ms";
    private static final String TAG = "AlarmScheduler";

    /** 解析 "08:00,12:30,20:00" 或换行/中文逗号分隔的时间字符串。 */
    public static List<String> parseTimes(String times) {
        List<String> list = new ArrayList<>();
        if (times == null) return list;
        for (String s : times.split("[,，\\n;]")) {
            s = s.trim();
            if (s.matches("\\d{1,2}:\\d{2}")) list.add(s);
        }
        return list;
    }

    /** 用已存储的偏差排程（开机/兜底用）。 */
    public static void scheduleAll(Context context) {
        scheduleAll(context, Prefs.getOffset(context));
    }

    public static void scheduleAll(Context context, long offsetMs) {
        // 先清掉所有历史闹钟（含已删除时间点遗留的），再按当前配置重新注册
        cancelAll(context);
        List<String> times = parseTimes(Prefs.getTimes(context));
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        for (int i = 0; i < times.size(); i++) {
            scheduleOne(context, am, i, times.get(i), offsetMs);
        }
        Log.d(TAG, "scheduleAll -> " + times.size() + " alarms, offset=" + offsetMs);
    }

    public static void scheduleOne(Context context, AlarmManager am, int index,
                                  String hhmm, long offsetMs) {
        long buffer = Prefs.getBufferMs(context);                // 用户设置的定时裕度（可为负）
        long realTarget = nextBeijingInstant(hhmm, offsetMs);   // 真实北京时间目标
        realTarget += buffer;                                    // 裕度：负数=提前，正数=延后
        // 保护：若加上负裕度后目标已落到过去（例如临近整点才保存），
        // setAlarmClock 传入过去时间会立即误触发一次，这里顺延到次日。
        long realNow = System.currentTimeMillis() + offsetMs;
        while (realTarget <= realNow + 1000L) realTarget += 86400_000L;
        arm(context, am, index, realTarget, offsetMs);
    }

    /**
     * 闹钟响后重排「次日」：直接在上一次真实触发时刻 +24h，而不是重新按当前时间推算。
     * 关键：若用「重新推算」，负裕度时算出的目标可能仍在过去，setAlarmClock 会立即再响，
     * 造成一次定时出现多条日志、连续多次点击。这里用精确时刻 +24h 彻底规避。
     */
    public static void scheduleNextDay(Context context, AlarmManager am, int index,
                                       long firedRealMs, long offsetMs) {
        long realNow = System.currentTimeMillis() + offsetMs;
        long next = firedRealMs + 86400_000L;
        // 兜底：若设备睡过头导致 +24h 仍在过去，继续往后推，绝不排出过去的时间
        while (next <= realNow + 1000L) next += 86400_000L;
        arm(context, am, index, next, offsetMs);
    }

    /** 用「真实目标时刻」注册一个闹钟。 */
    private static void arm(Context context, AlarmManager am, int index,
                            long realTargetMs, long offsetMs) {
        long systemTarget = realTargetMs - offsetMs;            // 换算回系统时钟
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.putExtra(EXTRA_INDEX, index);
        intent.putExtra(EXTRA_FIRE_REAL_MS, realTargetMs);
        PendingIntent pi = PendingIntent.getBroadcast(
                context, index, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // 用 AlarmClock 通道：无需「精确闹钟」权限、不受 Doze 合批、到点准时并自动点亮屏幕。
            // 完美适配没有精确闹钟权限入口的国产 ROM（如努比亚）。
            AlarmManager.AlarmClockInfo info =
                    new AlarmManager.AlarmClockInfo(systemTarget, null);
            am.setAlarmClock(info, pi);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, systemTarget, pi);
        }
        Log.d(TAG, "scheduled #" + index + " (system=" + systemTarget + ", real=" + realTargetMs + ")");
    }

    /** 计算下一次「北京时间 hhmm」对应的真实 UTC 毫秒时刻。 */
    private static long nextBeijingInstant(String hhmm, long offsetMs) {
        String[] p = hhmm.split(":");
        int h = Integer.parseInt(p[0]);
        int m = Integer.parseInt(p[1]);
        TimeZone bj = TimeZone.getTimeZone("Asia/Shanghai"); // 北京时间 = UTC+8
        Calendar cal = Calendar.getInstance(bj);
        long realNow = System.currentTimeMillis() + offsetMs;
        cal.setTimeInMillis(realNow);
        cal.set(Calendar.HOUR_OF_DAY, h);
        cal.set(Calendar.MINUTE, m);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= realNow) {
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        return cal.getTimeInMillis();
    }

    public static void cancelAll(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        // 取消 0~49 号所有历史闹钟：时间点删过之后，旧 requestCode 的闹钟仍会活着，
        // 必须一并清掉，否则会出现「莫名多点几次 / 多出几条日志」。
        for (int i = 0; i < 50; i++) {
            Intent intent = new Intent(context, AlarmReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(
                    context, i, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.cancel(pi);
        }
    }
}
