package com.ganbo2.qqmusicauto;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机后重新注册定时任务（闹钟在关机期间不会保留）。
 * 先用存储的 NTP 偏差排程，等网络就绪后再刷新偏差并重排，保证时间精准。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d("BootReceiver", "boot completed, rescheduling");
            AlarmScheduler.scheduleAll(context); // 用已存储偏差
            // 网络就绪后刷新 NTP 偏差并重排
            new Thread(() -> {
                Long off = NtpClient.getOffsetMs();
                if (off != null) {
                    Prefs.saveOffset(context, off);
                    AlarmScheduler.scheduleAll(context, off);
                }
            }).start();
        }
    }
}
