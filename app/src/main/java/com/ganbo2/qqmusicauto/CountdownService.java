package com.ganbo2.qqmusicauto;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 常驻前台服务：在通知栏持续显示「下一次自动点击的北京时间 + 倒计时 + 校时偏差」。
 * 每 30 秒刷新一次文本。保存/开机/触发后调用 {@link #updateNow(Context)} 立即刷新。
 */
public class CountdownService extends Service {
    private static final String TAG = "CountdownService";
    private static volatile boolean running = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationHelper.createChannel(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            startForegroundCompat();
            startRefreshLoop();
        } else {
            updateNow(this);
        }
        return START_STICKY;
    }

    /** 以 dataSync 类型启动前台服务（Android 14+ 需要声明 foregroundServiceType）。 */
    private void startForegroundCompat() {
        Notification n = NotificationHelper.buildStatusNotification(this, "计算下次触发时间...");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NotificationHelper.NOTIF_ID_STATUS, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NotificationHelper.NOTIF_ID_STATUS, n);
        }
    }

    private void startRefreshLoop() {
        Handler h = new Handler(Looper.getMainLooper());
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                updateNow(CountdownService.this);
                h.postDelayed(this, 30_000);
            }
        };
        h.postDelayed(r, 0);
    }

    /** 立即刷新一次状态栏文本（供外部调用）。 */
    public static void updateNow(Context c) {
        Notification n = NotificationHelper.buildStatusNotification(c, buildBody(c));
        android.app.NotificationManager nm =
                (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NotificationHelper.NOTIF_ID_STATUS, n);
    }

    /** 组装状态栏正文：下次触发时间（北京时间）+ 距现在倒计时 + 校时偏差。public 供界面复用。 */
    public static String buildBody(Context c) {
        long offset = Prefs.getOffset(c);
        long nowReal = NtpClient.realNowMs(offset);          // 当前真实（网络）时间
        List<String> times = AlarmScheduler.parseTimes(Prefs.getTimes(c));
        int buffer = Prefs.getBufferMs(c);

        if (times.isEmpty()) {
            return "尚未设置时间点，请到 App 里添加";
        }

        // 取最近一个「尚未到来」的真实触发时刻（含裕度）
        long best = Long.MAX_VALUE;
        for (String hhmm : times) {
            String[] p = hhmm.split(":");
            TimeZone bj = TimeZone.getTimeZone("Asia/Shanghai");
            Calendar cal = Calendar.getInstance(bj);
            cal.setTimeInMillis(nowReal);
            cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(p[0]));
            cal.set(Calendar.MINUTE, Integer.parseInt(p[1]));
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            long target = cal.getTimeInMillis();
            if (target <= nowReal) target += 86400_000L;      // 过了就取下一天
            target += buffer;
            if (target < best) best = target;
        }

        long diff = best - nowReal;
        String rel;
        if (diff <= 0) {
            rel = "即将触发";
        } else {
            long h = diff / 3600_000L;
            long m = (diff % 3600_000L) / 60_000L;
            long s = (diff % 60_000L) / 1000L;
            rel = String.format(Locale.CHINA, "还有 %d小时%d分%d秒", h, m, s);
        }

        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.CHINA);
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        String next = sdf.format(new java.util.Date(best));

        String offsetTxt = (offset == 0)
                ? "校时偏差 0ms"
                : String.format(Locale.CHINA, "校时偏差 %+dms", offset);

        return String.format(Locale.CHINA, "下次点击 %s（%s） · %s", next, rel, offsetTxt);
    }

    /** 供 MainActivity 调用：确保前台常驻已启动（若未启动则拉起）。 */
    public static void ensureRunning(Context c) {
        NotificationHelper.createChannel(c);
        Intent i = new Intent(c, CountdownService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
        updateNow(c);
    }

    /** 是否正在前台常驻。 */
    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }
}
