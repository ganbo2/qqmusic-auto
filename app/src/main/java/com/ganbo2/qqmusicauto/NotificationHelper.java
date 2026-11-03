package com.ganbo2.qqmusicauto;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/**
 * 通知渠道与通知构建工具。
 * 常驻倒计时通知由 {@link CountdownService} 维护；点击结果提示与「标记成功/失败」按钮通知也走这里。
 */
public class NotificationHelper {
    public static final String CHANNEL_ID = "countdown_status";
    /** 常驻倒计时通知的固定 id（与 TriggerService 的 1001 区分，避免相互覆盖）。 */
    public static final int NOTIF_ID_STATUS = 1002;

    /** 标记结果的广播 Action。 */
    public static final String ACTION_MARK_SUCCESS = "com.ganbo2.qqmusicauto.MARK_SUCCESS";
    public static final String ACTION_MARK_FAILURE = "com.ganbo2.qqmusicauto.MARK_FAILURE";
    public static final String EXTRA_LOG_ID = "log_id";
    /** 标记通知的固定 id（同一 id 不断 notify 更新，避免堆积）。 */
    public static final int NOTIF_ID_MARK = 1003;

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

    /**
     * 弹出「请标记本次结果」的通知，带「✓成功 / ✗失败」两个操作按钮。
     * 点击按钮会向 {@link MarkReceiver} 发广播，把结果写回日志。
     */
    public static void notifyMark(Context c, int id, long logId, String body) {
        createChannel(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent markOk = new Intent(c, MarkReceiver.class);
        markOk.setAction(ACTION_MARK_SUCCESS);
        markOk.putExtra(EXTRA_LOG_ID, logId);
        PendingIntent piOk = PendingIntent.getBroadcast(c, 0, markOk,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent markNo = new Intent(c, MarkReceiver.class);
        markNo.setAction(ACTION_MARK_FAILURE);
        markNo.putExtra(EXTRA_LOG_ID, logId);
        PendingIntent piNo = PendingIntent.getBroadcast(c, 1, markNo,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(c, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("QQ音乐自动点击 · 请标记结果")
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .addAction(android.R.drawable.ic_dialog_info, "✓ 成功", piOk)
                .addAction(android.R.drawable.ic_dialog_alert, "✗ 失败", piNo)
                .setAutoCancel(true)
                .build();
        nm.notify(id, n);
    }
}

