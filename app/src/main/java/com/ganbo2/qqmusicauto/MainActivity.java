package com.ganbo2.qqmusicauto;

import android.Manifest;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 设置界面：
 * - 用 TimePicker 手动添加/删除「北京时间」点击时刻（可多个）
 * - 启动即网络校时（NTP），显示当前北京时间及与系统时钟偏差
 * - 保存时重新校时并按最新偏差排程，保证即使系统时间不准也能准点
 */
public class MainActivity extends AppCompatActivity {
    private LinearLayout containerTimes;
    private EditText etTarget;
    private CheckBox cbLaunch;
    private TextView tvStatus;
    private CheckBox cbUseCoord;
    private EditText etCoordX;
    private EditText etCoordY;
    private EditText etBuffer;
    private LinearLayout containerLog;
    private TextView tvCountdown;
    private TextView tvVersion;
    private final List<String> times = new ArrayList<>();
    private final Handler countdownHandler = new Handler(Looper.getMainLooper());
    private final Runnable countdownTick = new Runnable() {
        @Override
        public void run() {
            tickCountdown();
            countdownHandler.postDelayed(this, 200);
        }
    };

    private final ActivityResultLauncher<String> notifPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});

    private final ActivityResultLauncher<String> importLogLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) {
                        Toast.makeText(this, "无法读取文件", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(in));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    int n = LogStore.importJson(this, sb.toString());
                    renderLog();
                    Toast.makeText(this, "成功恢复 " + n + " 条日志", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "恢复失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        containerTimes = findViewById(R.id.containerTimes);
        etTarget = findViewById(R.id.etTarget);
        cbLaunch = findViewById(R.id.cbLaunch);
        tvStatus = findViewById(R.id.tvStatus);
        cbUseCoord = findViewById(R.id.cbUseCoord);
        etCoordX = findViewById(R.id.etCoordX);
        etCoordY = findViewById(R.id.etCoordY);
        etBuffer = findViewById(R.id.etBuffer);
        containerLog = findViewById(R.id.containerLog);
        tvCountdown = findViewById(R.id.tvCountdown);
        tvVersion = findViewById(R.id.tvVersion);

        // 显示版本号
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            tvVersion.setText("版本：" + pi.versionName + " (v" + pi.versionCode + ")");
        } catch (Exception ex) {
            tvVersion.setText("版本：未知");
        }

        SharedPreferences sp = Prefs.get(this);
        for (String t : AlarmScheduler.parseTimes(Prefs.getTimes(this))) times.add(t);
        etTarget.setText(sp.getString(Prefs.KEY_TARGET, "立即提现"));
        cbLaunch.setChecked(sp.getBoolean(Prefs.KEY_LAUNCH, false));
        cbUseCoord.setChecked(Prefs.getUseCoord(this));
        etCoordX.setText(String.valueOf(Prefs.getCoordX(this)));
        etCoordY.setText(String.valueOf(Prefs.getCoordY(this)));
        etBuffer.setText(String.valueOf(Prefs.getBufferMs(this)));

        renderTimes();
        refreshNetworkTime(); // 启动即校时
        renderLog();         // 显示点击日志
        tickCountdown();
        countdownHandler.post(countdownTick);

        findViewById(R.id.btnAccessibility).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.btnAddTime).setOnClickListener(v -> showTimePicker());
        findViewById(R.id.btnSave).setOnClickListener(v -> saveAndSchedule());
        findViewById(R.id.btnTest).setOnClickListener(v -> testNow());
        findViewById(R.id.btnRefreshLog).setOnClickListener(v -> renderLog());
        findViewById(R.id.btnClearLog).setOnClickListener(v -> {
            LogStore.clear(this);
            renderLog();
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnExportLog).setOnClickListener(v -> exportLogs());
        findViewById(R.id.btnImportLog).setOnClickListener(v -> importLogs());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        countdownHandler.removeCallbacks(countdownTick);
    }

    /** 后台获取网络时间，更新状态栏并存储偏差。 */
    private void refreshNetworkTime() {
        new Thread(() -> {
            Long off = NtpClient.getOffsetMs();
            long offset = (off == null) ? Prefs.getOffset(MainActivity.this) : off;
            Prefs.saveOffset(MainActivity.this, offset);
            final long fOffset = offset;
            runOnUiThread(() -> updateStatus(fOffset));
        }).start();
    }

    private void updateStatus(long offset) {
        long realNow = NtpClient.realNowMs(offset);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        String bj = sdf.format(new Date(realNow));
        String diff = (offset > 0 ? "+" : "") + offset + " ms";
        if (offset == 0) diff = "（未校时，暂用系统时间）";
        tvStatus.setText("网络北京时间：" + bj + "\n与手机时钟偏差：" + diff);
    }

    /**
     * 每秒多次刷新：显示精确到毫秒的实时北京时间 + 到下次触发点的倒计时 + 校时偏差。
     * 倒计时放在 APP 内（保存与测试按钮之间），不再占用状态栏。
     */
    private void tickCountdown() {
        long offset = Prefs.getOffset(this);
        long nowReal = NtpClient.realNowMs(offset);

        // 实时北京时间，精确到毫秒
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA);
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        String nowText = sdf.format(new Date(nowReal));

        // 计算最近一个尚未到来的触发时刻（含裕度）
        StringBuilder sb = new StringBuilder();
        sb.append("北京时间 ").append(nowText);

        List<String> times = AlarmScheduler.parseTimes(Prefs.getTimes(this));
        if (times.isEmpty()) {
            sb.append("\n尚未设置时间点，请在上方添加");
        } else {
            int buffer = Prefs.getBufferMs(this);
            long best = Long.MAX_VALUE;
            for (String hhmm : times) {
                String[] p = hhmm.split(":");
                TimeZone bj = TimeZone.getTimeZone("Asia/Shanghai");
                java.util.Calendar cal = java.util.Calendar.getInstance(bj);
                cal.setTimeInMillis(nowReal);
                cal.set(java.util.Calendar.HOUR_OF_DAY, Integer.parseInt(p[0]));
                cal.set(java.util.Calendar.MINUTE, Integer.parseInt(p[1]));
                cal.set(java.util.Calendar.SECOND, 0);
                cal.set(java.util.Calendar.MILLISECOND, 0);
                long target = cal.getTimeInMillis();
                if (target <= nowReal) target += 86400_000L; // 过了就取下一天
                target += buffer;
                if (target < best) best = target;
            }
            if (best == Long.MAX_VALUE) {
                sb.append("\n尚未设置时间点，请在上方添加");
            } else {
                SimpleDateFormat nsdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA);
                nsdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
                String next = nsdf.format(new Date(best));
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
                String offsetTxt = (offset == 0) ? "校时偏差 未校准" :
                        String.format(Locale.CHINA, "校时偏差 %+dms", offset);
                sb.append("\n下次点击 ").append(next).append("（").append(rel).append("）· ").append(offsetTxt);
            }
        }
        if (tvCountdown != null) tvCountdown.setText(sb.toString());
    }

    private void renderTimes() {
        containerTimes.removeAllViews();
        for (int i = 0; i < times.size(); i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView tv = new TextView(this);
            tv.setText(times.get(i));
            tv.setTextSize(20);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button del = new Button(this);
            del.setText("删除");
            del.setOnClickListener(v -> {
                times.remove(idx);
                renderTimes();
            });
            row.addView(tv);
            row.addView(del);
            containerTimes.addView(row);
        }
        if (times.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("（尚未添加时间，请点下方按钮）");
            containerTimes.addView(empty);
        }
    }

    private void showTimePicker() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int h = c.get(java.util.Calendar.HOUR_OF_DAY);
        int m = c.get(java.util.Calendar.MINUTE);
        TimePickerDialog d = new TimePickerDialog(this, (view, hourOfDay, minute) -> {
            String t = String.format(Locale.CHINA, "%02d:%02d", hourOfDay, minute);
            if (!times.contains(t)) times.add(t);
            renderTimes();
        }, h, m, DateFormat.is24HourFormat(this));
        d.setTitle("选择点击时间（北京时间）");
        d.show();
    }

    private void saveAndSchedule() {
        String target = etTarget.getText().toString().trim();
        if (target.isEmpty()) {
            Toast.makeText(this, "目标文字不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        if (times.isEmpty()) {
            Toast.makeText(this, "请至少添加一个时间", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean useCoord = cbUseCoord.isChecked();
        int coordX, coordY;
        try {
            coordX = Integer.parseInt(etCoordX.getText().toString().trim());
            coordY = Integer.parseInt(etCoordY.getText().toString().trim());
        } catch (NumberFormatException ex) {
            Toast.makeText(this, "坐标百分比必须是 0~100 的数字", Toast.LENGTH_SHORT).show();
            return;
        }
        if (coordX < 0 || coordX > 100 || coordY < 0 || coordY > 100) {
            Toast.makeText(this, "坐标百分比必须在 0~100 之间", Toast.LENGTH_SHORT).show();
            return;
        }

        int bufferMs;
        try {
            bufferMs = Integer.parseInt(etBuffer.getText().toString().trim());
        } catch (NumberFormatException ex) {
            Toast.makeText(this, "裕度必须是数字（毫秒，-5000~5000）", Toast.LENGTH_SHORT).show();
            return;
        }
        if (bufferMs < -5000 || bufferMs > 5000) {
            Toast.makeText(this, "裕度必须在 -5000~5000 毫秒之间（负数=提前点击）", Toast.LENGTH_SHORT).show();
            return;
        }

        Collections.sort(times);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(times.get(i));
        }
        SharedPreferences.Editor e = Prefs.get(this).edit();
        e.putString(Prefs.KEY_TIMES, sb.toString());
        e.putString(Prefs.KEY_TARGET, target);
        e.putBoolean(Prefs.KEY_LAUNCH, cbLaunch.isChecked());
        e.putBoolean(Prefs.KEY_USE_COORD, useCoord);
        e.putInt(Prefs.KEY_COORD_X, coordX);
        e.putInt(Prefs.KEY_COORD_Y, coordY);
        e.putInt(Prefs.KEY_BUFFER, bufferMs);
        e.putString(Prefs.KEY_STEPS, Prefs.defaultSteps());
        e.apply();

        Toast.makeText(this, "正在网络校时并启用 " + times.size() + " 个定时点击...", Toast.LENGTH_SHORT).show();
        // 重新校时后按最新偏差排程
        new Thread(() -> {
            Long off = NtpClient.getOffsetMs();
            long offset = (off == null) ? Prefs.getOffset(MainActivity.this) : off;
            Prefs.saveOffset(MainActivity.this, offset);
            final long fOffset = offset;
            runOnUiThread(() -> {
                AlarmScheduler.cancelAll(MainActivity.this);
                AlarmScheduler.scheduleAll(MainActivity.this, fOffset);
                updateStatus(fOffset);
                Toast.makeText(MainActivity.this,
                        "已启用 " + times.size() + " 个定时点击（校时偏差 " + fOffset
                                + "ms，" + (bufferMs < 0 ? "提前 " + Math.abs(bufferMs) + "ms 点击" : "到点后延迟 " + bufferMs + "ms 再点")
                                + "）",
                        Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private void testNow() {
        saveAndSchedule();
        Intent trigger = new Intent(AutoClickService.ACTION_TRIGGER);
        trigger.setPackage(getPackageName());
        trigger.putExtra(AutoClickService.EXTRA_IS_TEST, true);
        sendBroadcast(trigger);
        Toast.makeText(this, "已立即触发一次点击（测试，不记录日志；请确保「立即提现」页面已打开）", Toast.LENGTH_SHORT).show();
    }

    /** 渲染点击日志列表：每条可点标记成功/失败，长按删除。 */
    private void renderLog() {
        containerLog.removeAllViews();
        List<LogStore.Entry> list = LogStore.list(this);
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("（暂无日志。到点自动点击后会自动记录一条，可在通知栏点「✓成功 / ✗失败」标记）");
            containerLog.addView(empty);
            return;
        }
        java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.CHINA);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        for (final LogStore.Entry e : list) {
            final long id = e.id;
            final int cur = e.result;
            TextView row = new TextView(this);
            String res;
            if (cur == LogStore.RESULT_SUCCESS) res = "✅ 成功";
            else if (cur == LogStore.RESULT_FAILURE) res = "❌ 失败";
            else res = "❓ 未标记";
            row.setText("#" + id + "  " + sdf.format(new java.util.Date(e.realTimeMs))
                    + "  偏差 " + (e.offsetMs > 0 ? "+" : "") + e.offsetMs
                    + "ms  裕度 " + e.bufferMs + "ms\n"
                    + "    " + e.detail + "  → " + res + "（点击切换：成功→失败→成功）");
            row.setPadding(12, 8, 12, 8);
            row.setBackground(new android.graphics.drawable.ColorDrawable(0xFFE8E8E8));
            row.setOnClickListener(v -> {
                int next;
                if (cur == LogStore.RESULT_UNKNOWN || cur == LogStore.RESULT_FAILURE) {
                    next = LogStore.RESULT_SUCCESS;
                } else {
                    next = LogStore.RESULT_FAILURE;
                }
                LogStore.mark(this, id, next);
                renderLog();
                String msg = next == LogStore.RESULT_SUCCESS ? "已标记日志#" + id + " 为「成功」"
                        : "已标记日志#" + id + " 为「失败」";
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            });
            row.setOnLongClickListener(v -> {
                LogStore.remove(this, id);
                renderLog();
                Toast.makeText(this, "已删除日志#" + id, Toast.LENGTH_SHORT).show();
                return true;
            });
            containerLog.addView(row);
        }
    }

    /** 备份日志到下载目录，方便卸载前导出、重装后恢复。 */
    private void exportLogs() {
        try {
            String json = LogStore.exportJson(this);
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File file = new File(dir, "qqmusic_log_backup_" + System.currentTimeMillis() + ".json");
            FileWriter fw = new FileWriter(file);
            fw.write(json);
            fw.close();
            Toast.makeText(this, "已备份到 " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "备份失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 从文件恢复日志（合并模式，不覆盖现有日志）。 */
    private void importLogs() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        importLogLauncher.launch(intent.getType());
    }
}
