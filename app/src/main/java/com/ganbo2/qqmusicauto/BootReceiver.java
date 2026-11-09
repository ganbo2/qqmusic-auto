package com.ganbo2.qqmusicauto;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机后重新注册定时任务（闹钟在关机期间不会保留）。
 * 先用当前偏差快速恢复闹钟，等网络就绪后按 TimePolicy 统一策略刷新并重排，
 * 避免开机时把历史遗留的可疑偏差（如 +1800ms）继续用于排程。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d("BootReceiver", "boot completed, rescheduling");
            if (Prefs.getUseSystemTime(context)) {
                Prefs.saveOffset(context, 0);
                Prefs.saveSyncInfo(context, "手机系统时间（用户指定）", 0, true, "偏差恒为 0", 0, 1);
                Prefs.saveMeasure(context, false, 0, "未校时（系统时间模式）", 0);
                Prefs.saveOffsetReason(context, TimePolicy.systemMode().reason);
                AlarmScheduler.scheduleAll(context, 0);
                return;
            }
            // 先用当前偏差快速恢复闹钟；若历史偏差可疑，先归零，别让脏值排进开机闹钟
            long bootOffset = Prefs.getOffset(context);
            if (Math.abs(bootOffset) > TimePolicy.SUSPICIOUS_OFFSET_MS) {
                bootOffset = 0;
                Prefs.saveOffset(context, 0);
                Prefs.saveOffsetReason(context, "开机检测到历史偏差可疑，已归零");
            }
            AlarmScheduler.scheduleAll(context, bootOffset);
            new Thread(() -> {
                TimePolicy.Decision d = TimePolicy.decide(context, NtpClient.sync());
                TimePolicy.apply(context, d);
            }).start();
        }
    }
}
