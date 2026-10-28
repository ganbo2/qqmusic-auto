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
    private static final String TAG = "AutoClickService";

    private boolean running = false;
    private Handler handler;
    private BroadcastReceiver receiver;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        handler = new Handler(Looper.getMainLooper());
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_TRIGGER.equals(intent.getAction())) {
                    startAutoClick();
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

    private void startAutoClick() {
        if (running) return;
        running = true;
        Log.d(TAG, "startAutoClick");
        executeSteps(new Runnable() {
            @Override
            public void run() {
                startTargetPolling();
            }
        });
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
    private void startTargetPolling() {
        final String target = Prefs.getTarget(this);
        final boolean useCoord = Prefs.getUseCoord(this);
        final int xPct = Prefs.getCoordX(this);
        final int yPct = Prefs.getCoordY(this);

        // 纯坐标模式：直接点一次（适合图片按钮，最稳）
        if (useCoord) {
            tapCoordinatePercent(xPct, yPct);
            Log.d(TAG, "coord-mode tap (" + xPct + "%," + yPct + "%)");
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
                    running = false;
                    return;
                }
                tries[0]++;
                if (tries[0] >= fallbackAfter) {
                    Log.d(TAG, "text not found, fallback to coord tap (" + xPct + "%," + yPct + "%)");
                    tapCoordinatePercent(xPct, yPct);
                    running = false;
                    return;
                }
                if (tries[0] >= maxTries) {
                    Log.d(TAG, "target not found within timeout: " + target);
                    running = false;
                    return;
                }
                handler.postDelayed(this, 500);
            }
        };
        handler.post(poll);
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
