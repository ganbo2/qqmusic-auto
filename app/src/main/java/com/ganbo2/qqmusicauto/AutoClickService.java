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
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 无障碍服务（无需 root）：收到触发广播后，
 * 1) 按配置依次执行导航步骤（打开 App / 等待 / 按文字点击 / 按坐标点击）
 * 2) 轮询查找目标文字节点并点击（找不到则向上回溯到可点击父节点）
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

    /** 轮询查找目标文字并点击，最多 15 秒（30 次 × 500ms）。 */
    private void startTargetPolling() {
        final String target = Prefs.getTarget(this);
        final int[] tries = {0};
        final int maxTries = 30;
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (clickNodeByText(target)) {
                    Log.d(TAG, "clicked target: " + target);
                    running = false;
                    return;
                }
                tries[0]++;
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

    private boolean clickNodeByText(String text) {
        if (text == null || text.isEmpty()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        boolean found = false;
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
            for (AccessibilityNodeInfo n : nodes) {
                if (tryClick(n)) {
                    found = true;
                    break;
                }
            }
            for (AccessibilityNodeInfo n : nodes) {
                if (n != null) n.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "clickNode error", e);
        } finally {
            root.recycle();
        }
        return found;
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

    private void tapCoordinate(int x, int y) {
        if (x <= 0 || y <= 0) return;
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription gd = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60))
                .build();
        dispatchGesture(gd, null, null);
    }
}
