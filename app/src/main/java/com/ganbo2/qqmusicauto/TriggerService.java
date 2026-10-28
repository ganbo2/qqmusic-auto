package com.ganbo2.qqmusicauto;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * 前台服务：在定时触发时（1）打开目标 App（2）发出广播让无障碍服务执行点击。
 * 用前台服务是为了在 Android 8+ 后台限制下仍能启动目标 App 并给出通知。
 */
public class TriggerService extends android.app.Service {
    private static final String CHANNEL = "qqmusic_auto_channel";
    private static final int NOTIF_ID = 1001;
    private static final String TAG = "TriggerService";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        Notification n = new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("QQ音乐自动点击")
                .setContentText("正在执行定时点击...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();
        startForeground(NOTIF_ID, n);

        if (Prefs.getLaunch(this)) {
            String pkg = Prefs.getPkg(this);
            PackageManager pm = getPackageManager();
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                try {
                    startActivity(launch);
                    Log.d(TAG, "launched " + pkg);
                } catch (Exception e) {
                    Log.e(TAG, "launch failed", e);
                }
            } else {
                Log.w(TAG, "package not found: " + pkg);
            }
        }

        Intent trigger = new Intent(AutoClickService.ACTION_TRIGGER);
        trigger.setPackage(getPackageName());
        sendBroadcast(trigger);

        // 15 秒后自动停止前台服务
        new Handler(Looper.getMainLooper()).postDelayed(this::stopSelf, 15000);
        return START_NOT_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "QQ音乐自动点击", NotificationManager.IMPORTANCE_HIGH);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
}
