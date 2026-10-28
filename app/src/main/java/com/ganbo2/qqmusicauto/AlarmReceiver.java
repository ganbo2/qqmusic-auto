package com.ganbo2.qqmusicauto;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.util.List;

/**
 * 闹钟触发：先为同一个时间点（用 NTP 偏差）重排次日，再触发点击。
 * 默认路径（不自动打开 App）：直接发广播给无障碍服务，时间到「零等待」立即点击。
 * 若用户勾选「自动打开 QQ音乐」，才走前台服务去启动 App。
 */
public class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "AlarmReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "alarm fired");
        int index = intent.getIntExtra(AlarmScheduler.EXTRA_INDEX, -1);

        if (index >= 0) {
            List<String> times = AlarmScheduler.parseTimes(Prefs.getTimes(context));
            if (index < times.size()) {
                AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                if (am != null) {
                    long offset = Prefs.getOffset(context);
                    AlarmScheduler.scheduleOne(context, am, index, times.get(index), offset);
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
