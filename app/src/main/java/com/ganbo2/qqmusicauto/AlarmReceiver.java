package com.ganbo2.qqmusicauto;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import java.util.List;

/**
 * 闹钟触发：先为同一个时间点重排次日，再触发点击。
 * 重排用「本次真实触发时刻 +24h」，不重新按当前时间推算，
 * 避免负裕度（提前点击）时排出一个已过去的时间，导致闹钟立即二次触发、
 * 一次定时出现多条日志 / 连续多次点击。
 * 默认路径（不自动打开 App）：直接发广播给无障碍服务，时间到「零等待」立即点击。
 * 若用户勾选「自动打开 QQ音乐」，才走前台服务去启动 App。
 */
public class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "AlarmReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "alarm fired");
        // 保持 CPU 唤醒 5 秒，确保手势在点按窗口内执行完毕（setAlarmClock 已自动点亮屏幕）
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "qqmusicauto:alarm");
            wl.acquire(5000);
        }
        int index = intent.getIntExtra(AlarmScheduler.EXTRA_INDEX, -1);
        long firedRealMs = intent.getLongExtra(AlarmScheduler.EXTRA_FIRE_REAL_MS, 0L);

        if (index >= 0) {
            List<String> times = AlarmScheduler.parseTimes(Prefs.getTimes(context));
            if (index < times.size()) {
                AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                if (am != null) {
                    long offset = Prefs.getOffset(context);
                    if (firedRealMs > 0L) {
                        // 精确 +24h 重排，避免负裕度时排出「过去时间」导致闹钟立即二次触发
                        AlarmScheduler.scheduleNextDay(context, am, index, firedRealMs, offset);
                    } else {
                        AlarmScheduler.scheduleOne(context, am, index, times.get(index), offset);
                    }
                }
            }
        }

        if (Prefs.getLaunch(context)) {
            Intent svc = new Intent(context, TriggerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } else {
            // 直接触发点击，不经过打开 App，满足「时间到马上点」
            Intent trigger = new Intent(AutoClickService.ACTION_TRIGGER);
            trigger.setPackage(context.getPackageName());
            context.sendBroadcast(trigger);
        }
    }
}
