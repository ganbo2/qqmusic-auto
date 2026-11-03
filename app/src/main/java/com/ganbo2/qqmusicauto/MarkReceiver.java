package com.ganbo2.qqmusicauto;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 接收「标记成功/失败」广播，把结果写回日志，并刷新状态栏倒计时。
 * 由 {@link NotificationHelper} 的通知操作按钮触发。
 */
public class MarkReceiver extends BroadcastReceiver {
    private static final String TAG = "MarkReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        long logId = intent.getLongExtra(NotificationHelper.EXTRA_LOG_ID, -1);
        if (logId < 0) {
            Log.w(TAG, "no log id");
            return;
        }
        int result;
        String label;
        if (NotificationHelper.ACTION_MARK_SUCCESS.equals(action)) {
            result = LogStore.RESULT_SUCCESS;
            label = "已标记「成功」";
        } else if (NotificationHelper.ACTION_MARK_FAILURE.equals(action)) {
            result = LogStore.RESULT_FAILURE;
            label = "已标记「失败」";
        } else {
            return;
        }
        LogStore.mark(context, logId, result);
        // 清掉那条标记通知，并提示已标记结果
        android.app.NotificationManager nm =
                (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NotificationHelper.NOTIF_ID_MARK);
        NotificationHelper.notifyBrief(context,
                NotificationHelper.NOTIF_ID_STATUS + 2, label);
    }
}
