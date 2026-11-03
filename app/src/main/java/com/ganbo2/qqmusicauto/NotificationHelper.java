package com.ganbo2.qqmusicauto;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/**
 * 通知渠道与「状态栏常驻倒计时」通知的构建工具。
 * 常驻通知由 {@link CountdownService} 维护；点击成功/未找到的即时提示也走这里。
 */
public class NotificationHelper {
    public static final String CHANNEL_ID = "countdown_status";
    /** 常驻倒计时通知的固定 id（与 TriggerService 的 1001 区分，避免相互覆盖）。 */
    public static final int NOTIF_ID_STATUS = 1002;

    public static void createChannel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "状态栏倒计时", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("显示下一次自动点击的北京时间与倒计时");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    /** 构建一条常驻通知：标题=QQ音乐自动点击，正文=下次触发时间+倒计时+校时偏差。 */
    public static Notification buildStatusNotification(Context c, String body) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle("QQ音乐自动点击")
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        return b.build();
    }

    /** 一次短暂的「已点击」提示（非持续，自动消失）。 */
    public static void notifyBrief(Context c, int id, String body) {
        createChannel(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification n = new NotificationCompat.Builder(c, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("QQ音乐自动点击")
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build();
        nm.notify(id, n);
    }
}
