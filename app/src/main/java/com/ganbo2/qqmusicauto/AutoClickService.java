package com.ganbo2.qqmusicauto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 无障碍服务（无需 root）：收到触发广播后，
 * 1) 按配置依次执行导航步骤（打开 App / 等待 / 按文字点击 / 按坐标点击）
 * 2) 轮询查找目标并点击。
 *
 * 关键适配：很多 App（如 QQ音乐）把按钮画成「图片/自定义 View」，
 * 文字并不暴露给无障碍节点树，导致 findAccessibilityNodeInfosByText 搜不到。
 * 因此提供两条路径：
 *   - 文字路径：标准文字匹配 + 递归扫描（兼容 contentDescription / 子串 / 文字拆开）
 *   - 坐标路径：按屏幕百分比直接点（用户手动打开固定页面时最稳，解决图片按钮）
 * 默认策略：先试文字（约 3 秒），找不到就自动切到坐标兜底。
 */
public class AutoClickService extends AccessibilityService {
    public static final String ACTION_TRIGGER = "com.ganbo2.qqmusicauto.ACTION_TRIGGER";
    /** 区分「定时触发」与「手动测试触发」：测试触发不记录日志。 */
    public static final String EXTRA_IS_TEST = "is_test";
    private static final String TAG = "AutoClickService";

    private boolean running = false;
    private Handler handler;
    private BroadcastReceiver receiver;
    /** 上次「定时」触发的单调时钟，用于短时间去重，防止重复点击产生多条日志。 */
    private long lastTriggerElapsed = 0L;
    private static final long DEDUP_WINDOW_MS = 5000L;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        handler = new Handler(Looper.getMainLooper());
        // 系统可能多次回调 onServiceConnected，必须先注销旧的接收器，
        // 否则会累积多个实例，一次广播被处理多次 → 点击多次、日志多条。
        if (receiver != null) {
            try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        }
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_TRIGGER.equals(intent.getAction())) {
                    boolean test = intent.getBooleanExtra(EXTRA_IS_TEST, false);
                    // 记录「广播已送达」，与闹钟触发记录配合即可定位链路断点
                    Diag.broadcastReceived(context, test);
                    startAutoClick(test);
                }
            }
        };
        IntentFilter f = new IntentFilter(ACTION_TRIGGER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }
        Log.d(TAG, "service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 点击逻辑由 startAutoClick 主动轮询驱动，这里无需处理事件
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (receiver != null) unregisterReceiver(receiver);
        super.onDestroy();
    }

    private void startAutoClick(boolean test) {
        if (running) return;
        if (!test) {
            // 5 秒内的重复定时触发一律忽略（测试点击不受限，方便反复调试）
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastTriggerElapsed < DEDUP_WINDOW_MS) {
                Log.d(TAG, "duplicate trigger ignored (within " + DEDUP_WINDOW_MS + "ms)");
                return;
            }
            lastTriggerElapsed = now;
        }
        running = true;
        Log.d(TAG, "startAutoClick" + (test ? " (test)" : ""));
        executeSteps(() -> startTargetPolling(test));
    }

    /** 依次执行导航步骤，全部完成后回调 onDone。 */
    private void executeSteps(final Runnable onDone) {
        String stepsJson = Prefs.getSteps(this);
        JSONArray steps;
        try {
            steps = new JSONArray(stepsJson);
        } catch (Exception e) {
            Log.e(TAG, "bad steps json", e);
            onDone.run();
            return;
        }
        final int[] i = {0};
        Runnable next = new Runnable() {
            @Override
            public void run() {
                if (i[0] >= steps.length()) {
                    onDone.run();
                    return;
                }
                try {
                    JSONObject step = steps.getJSONObject(i[0]);
                    String type = step.optString("type", "wait");
                    if ("launch".equals(type)) {
                        String pkg = step.optString("pkg", Prefs.getPkg(AutoClickService.this));
                        launchApp(pkg);
                    } else if ("node".equals(type)) {
                        clickNodeByText(step.optString("text", ""));
                    } else if ("coord".equals(type)) {
                        tapCoordinate(step.optInt("x"), step.optInt("y"));
                    }
                    // type=="wait" 仅用于延时
                    int delay = step.optInt("ms", type.equals("wait") ? 2000 : 800);
                    i[0]++;
                    handler.postDelayed(this, delay);
                } catch (Exception e) {
                    Log.e(TAG, "step error", e);
                    i[0]++;
                    handler.postDelayed(this, 500);
                }
            }
        };
        handler.post(next);
    }

    /** 轮询查找目标并点击。策略见类注释。 */
    private void startTargetPolling(final boolean test) {
        final String target = Prefs.getTarget(this);
        final boolean useCoord = Prefs.getUseCoord(this);
        final int xPct = Prefs.getCoordX(this);
        final int yPct = Prefs.getCoordY(this);

        // 纯坐标模式：直接点一次（适合图片按钮，最稳）
        if (useCoord) {
            tapCoordinatePercent(xPct, yPct);
            Log.d(TAG, "coord-mode tap (" + xPct + "%," + yPct + "%)");
            notifyResult("已按坐标点击 (" + xPct + "%," + yPct + "%)");
            recordLog("坐标点击 (" + xPct + "%," + yPct + "%)", test);
            running = false;
            return;
        }

        final int[] tries = {0};
        final int maxTries = 30;          // 文字路径最多 15 秒
        final int fallbackAfter = 6;      // 约 3 秒搜不到就改用坐标兜底
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (clickNodeByText(target)) {
                    Log.d(TAG, "clicked target by text: " + target);
                    notifyResult("已点击「" + target + "」");
                    recordLog("文字点击「" + target + "」", test);
                    running = false;
                    return;
                }
                tries[0]++;
                if (tries[0] >= fallbackAfter) {
                    Log.d(TAG, "text not found, fallback to coord tap (" + xPct + "%," + yPct + "%)");
                    tapCoordinatePercent(xPct, yPct);
                    notifyResult("文字未找到，已按坐标点击 (" + xPct + "%," + yPct + "%)");
                    recordLog("文字未找到，坐标兜底 (" + xPct + "%," + yPct + "%)", test);
                    running = false;
                    return;
                }
                if (tries[0] >= maxTries) {
                    Log.d(TAG, "target not found within timeout: " + target);
                    notifyResult("未找到「" + target + "」按钮，请确认页面已打开");
                    recordLog("未找到「" + target + "」按钮（超时）", test);
                    running = false;
                    return;
                }
                handler.postDelayed(this, 500);
            }
        };
        handler.post(poll);
    }

    /** 手势派发是异步的，稍等 800ms 再弹出结果提示，确保点击已生效。 */
    private void notifyResult(String msg) {
        handler.postDelayed(() -> NotificationHelper.notifyBrief(AutoClickService.this,
                NotificationHelper.NOTIF_ID_STATUS + 1, msg), 800);
    }

    /**
     * 记录一条点击日志（真实触发时间、校时偏差、裕度），并弹出「请标记结果」通知。
     * 通知带 ✓成功 / ✗失败 按钮，点击即写回结果。
     */
    private void recordLog(String detail, boolean test) {
        if (test) {
            Log.d(TAG, "test trigger, skip recordLog");
            return;
        }
        try {
            long offset = Prefs.getOffset(this);
            long realNow = NtpClient.realNowMs(offset);
            // 最后一道防线：与上一条日志间隔过短（<5秒）视为重复触发，不再落库。
            // 时间点最小粒度是分钟，正常定时任务不会落在这个窗口内，不会误伤。
            List<LogStore.Entry> existing = LogStore.list(this);
            if (!existing.isEmpty()) {
                LogStore.Entry last = existing.get(existing.size() - 1);
                if (Math.abs(realNow - last.realTimeMs) < DEDUP_WINDOW_MS) {
                    Log.d(TAG, "duplicate log suppressed (delta="
                            + Math.abs(realNow - last.realTimeMs) + "ms)");
                    return;
                }
            }
            int buffer = Prefs.getBufferMs(this);
            long logId = LogStore.add(this, realNow, offset, buffer, detail);
            final String body = "已记录日志#" + logId + "（点击 " + detail + "）\n"
                    + "真实时间 " + fmtTime(realNow) + " · 偏差 " + offset + "ms · 裕度 " + buffer + "ms\n"
                    + "请查看结果：是成功还是失败？";
            handler.postDelayed(() -> NotificationHelper.notifyMark(
                    AutoClickService.this, NotificationHelper.NOTIF_ID_MARK, logId, body), 800);
        } catch (Exception e) {
            Log.e(TAG, "recordLog failed", e);
            // 旧版在这里静默失败：用户只看到「没日志没通知」，完全不知道原因
            NotificationHelper.notifyWarn(this, "点击日志写入失败：" + e.getMessage());
        }
    }

    private static String fmtTime(long ms) {
        java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.CHINA);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        return sdf.format(new java.util.Date(ms));
    }

    /**
     * 文字/描述查找并点击。
     * 同时兼容：标准文字匹配、contentDescription、子串包含、文字被拆到子节点的情况。
     */
    private boolean clickNodeByText(String text) {
        if (text == null || text.isEmpty()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        boolean found = false;
        try {
            List<AccessibilityNodeInfo> matches = new ArrayList<>();
            collectMatches(root, text.toLowerCase(), matches);
            for (AccessibilityNodeInfo n : matches) {
                if (tryClick(n)) { found = true; break; }
            }
            for (AccessibilityNodeInfo n : matches) {
                if (n != null) n.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "clickNode error", e);
        } finally {
            root.recycle();
        }
        return found;
    }

    /** 递归收集文字或描述包含 target 的节点（拷贝出来，避免父节点回收后失效）。 */
    private void collectMatches(AccessibilityNodeInfo node, String target, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        boolean self = false;
        if (t != null && t.toString().toLowerCase().contains(target)) self = true;
        else if (d != null && d.toString().toLowerCase().contains(target)) self = true;
        if (self) out.add(AccessibilityNodeInfo.obtain(node));
        for (int i = 0; i < node.getChildCount(); i++) {
            collectMatches(node.getChild(i), target, out);
        }
    }

    /** 优先点自身，自身不可点时回溯到第一个可点击的父节点。 */
    private boolean tryClick(AccessibilityNodeInfo n) {
        if (n == null) return false;
        if (n.isClickable() && n.isEnabled()) {
            return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        AccessibilityNodeInfo p = n.getParent();
        boolean r = false;
        if (p != null) {
            if (p.isClickable() && p.isEnabled()) {
                r = p.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            p.recycle();
        }
        return r;
    }

    /**
     * 按屏幕百分比坐标点击（x/y 为 0~100 的整数）。
     * 解决「图片按钮文字搜不到」的问题：用户手动打开固定页面，按钮位置固定，直接点最稳。
     */
    private void tapCoordinatePercent(int xPct, int yPct) {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm == null) return;
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            int x = (int) (dm.widthPixels * clampPct(xPct) / 100f);
            int y = (int) (dm.heightPixels * clampPct(yPct) / 100f);
            tapCoordinate(x, y);
        } catch (Exception e) {
            Log.e(TAG, "coord tap error", e);
        }
    }

    private int clampPct(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private void launchApp(String pkg) {
        android.content.pm.PackageManager pm = getPackageManager();
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
            } catch (Exception e) {
                Log.e(TAG, "launch", e);
            }
        }
    }

    /** 用无障碍手势在屏幕坐标 (x,y) 点一下（60ms 短按）。 */
    private void tapCoordinate(int x, int y) {
        if (x <= 0 || y <= 0) return;
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription gd = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0,
                        Math.max(1, ViewConfiguration.getTapTimeout())))
                .build();
        dispatchGesture(gd, null, null);
    }
}
