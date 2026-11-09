package com.ganbo2.qqmusicauto;

import android.Manifest;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
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
    private CheckBox cbUseSystemTime;
    private TextView tvStatus;
    private CheckBox cbUseCoord;
    private EditText etCoordX;
    private EditText etCoordY;
    private EditText etBuffer;
    private LinearLayout containerLog;
    private TextView tvCountdown;
    private TextView tvVersion;
    /** 日志列表分页：一次最多渲染 50 条，避免 500 条全展开导致打开界面卡顿。 */
    private static final int LOG_PAGE_SIZE = 50;
    private int logShown = LOG_PAGE_SIZE;
    private TextView tvLogCount;
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
                    logShown = LOG_PAGE_SIZE;
                    renderLog();
                    Toast.makeText(this, "成功恢复 " + n + " 条日志", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "恢复失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });

    // 用系统「另存为」写出备份，走 SAF，无需任何存储权限，
    // 避免国产 ROM 上直接写公共 Download 目录被分区存储拒绝。
    private final ActivityResultLauncher<String> exportLogLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                if (uri == null) return;
                try {
                    java.io.OutputStream out = getContentResolver().openOutputStream(uri);
                    if (out == null) {
                        Toast.makeText(this, "无法创建文件", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    out.write(LogStore.exportJson(this).getBytes("UTF-8"));
                    out.flush();
                    out.close();
                    Toast.makeText(this, "已备份日志，记住保存位置，重装后可恢复", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "备份失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        containerTimes = findViewById(R.id.containerTimes);
        etTarget = findViewById(R.id.etTarget);
        cbLaunch = findViewById(R.id.cbLaunch);
        cbUseSystemTime = findViewById(R.id.cbUseSystemTime);
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
        cbUseSystemTime.setChecked(Prefs.getUseSystemTime(this));
        try {
            // 已开启系统「自动设置时间」时，直接用系统时钟比任何网络测量都稳
            int auto = Settings.Global.getInt(getContentResolver(), Settings.Global.AUTO_TIME, 0);
            if (auto == 1) {
                cbUseSystemTime.setText("只用手机系统时间（已检测到开启「自动设置时间」，建议勾选：偏差恒为 0）");
            }
        } catch (Exception ignored) {}
        cbUseCoord.setChecked(Prefs.getUseCoord(this));
        etCoordX.setText(String.valueOf(Prefs.getCoordX(this)));
        etCoordY.setText(String.valueOf(Prefs.getCoordY(this)));
        etBuffer.setText(String.valueOf(Prefs.getBufferMs(this)));

        renderTimes();
        refreshNetworkTime(); // 启动即校时
        renderLog();         // 显示点击日志
        tickCountdown();
        countdownHandler.post(countdownTick);

        findViewById(R.id.btnResync).setOnClickListener(v -> {
            if (Prefs.getUseSystemTime(this)) {
                applySystemTimeMode();
                Toast.makeText(this, "当前为「只用手机系统时间」模式，偏差恒为 0，无需网络校时",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, "正在多源校时...", Toast.LENGTH_SHORT).show();
            refreshNetworkTime();
        });
        findViewById(R.id.btnResetOffset).setOnClickListener(v -> {
            Prefs.saveOffset(this, 0);
            Prefs.saveSyncInfo(this, "手动归零", -1, false, "", -1, 0);
            Prefs.saveMeasure(this, false, 0, "手动归零", 0);
            Prefs.saveOffsetReason(this, "手动归零：偏差已清 0，并按手机系统时间重新排程");
            AlarmScheduler.scheduleAll(this, 0);
            updateStatus(0);
            Toast.makeText(this,
                    "偏差已归零并按 0 重排闹钟；下次校时可信时会自动补偿",
                    Toast.LENGTH_LONG).show();
        });
        cbUseSystemTime.setOnCheckedChangeListener((btn, checked) -> {
            Prefs.saveUseSystemTime(this, checked);
            if (checked) {
                // 关键：把历史遗留的脏偏差清成 0，并立刻按系统时钟重排闹钟
                applySystemTimeMode();
                Toast.makeText(this, "已切换为只用系统时间：偏差归零，并按系统时钟重新排程",
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "已恢复网络校时，正在多源校时...", Toast.LENGTH_SHORT).show();
                refreshNetworkTime();
            }
        });
        findViewById(R.id.btnScrollTop).setOnClickListener(v -> {
            android.widget.ScrollView mainScroll = findViewById(R.id.mainScroll);
            if (mainScroll != null) mainScroll.smoothScrollTo(0, 0);
        });
        findViewById(R.id.btnAccessibility).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.btnAddTime).setOnClickListener(v -> showTimePicker());
        findViewById(R.id.btnSave).setOnClickListener(v -> saveAndSchedule());
        findViewById(R.id.btnTest).setOnClickListener(v -> testNow());
        findViewById(R.id.btnRefreshLog).setOnClickListener(v -> {
            logShown = LOG_PAGE_SIZE;
            renderLog();
        });
        findViewById(R.id.btnClearLog).setOnClickListener(v -> {
            LogStore.clear(this);
            logShown = LOG_PAGE_SIZE;
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

    /** 只用系统时间：偏差恒为 0，清除历史脏偏差并立即按系统时钟重排闹钟。 */
    private void applySystemTimeMode() {
        Prefs.saveOffset(this, 0);
        Prefs.saveSyncInfo(this, "手机系统时间（用户指定）", 0, true, "偏差恒为 0", 0, 1);
        Prefs.saveMeasure(this, false, 0, "未校时（系统时间模式）", 0);
        Prefs.saveOffsetReason(this, TimePolicy.systemMode().reason);
        AlarmScheduler.scheduleAll(this, 0);
        updateStatus(0);
    }

    /** 后台多源校时：实测值与排程采用值分开记录；不可信时回退到系统时间（0）。 */
    private void refreshNetworkTime() {
        if (Prefs.getUseSystemTime(this)) {
            Prefs.saveOffset(this, 0);
            Prefs.saveSyncInfo(this, "手机系统时间（用户指定）", 0, true, "偏差恒为 0", 0, 1);
            Prefs.saveMeasure(this, false, 0, "未校时（系统时间模式）", 0);
            Prefs.saveOffsetReason(this, TimePolicy.systemMode().reason);
            runOnUiThread(() -> updateStatus(0));
            return;
        }
        new Thread(() -> {
            NtpClient.Result res = NtpClient.sync();
            TimePolicy.Decision d = TimePolicy.decide(MainActivity.this, res);
            TimePolicy.apply(MainActivity.this, d);
            runOnUiThread(() -> {
                updateStatus(d.offsetMs);
                if (res != null && !Prefs.isSyncReliable(MainActivity.this)) {
                    Toast.makeText(MainActivity.this,
                            "本次校时不可信，已按手机系统时间（偏差 0）排程；详见状态区说明",
                            Toast.LENGTH_LONG).show();
                } else if (res == null) {
                    Toast.makeText(MainActivity.this,
                            "网络校时失败，详见状态区说明；建议换网络后点「重新校时」",
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    /**
     * 状态区必须把「本次实测值」和「排程采用值」分开显示。
     * 旧版把沿用下来的历史偏差写成「与手机时钟偏差」，导致 +1800ms 脏值
     * 看起来像是刚测出来的，用户无法判断该不该继续用这个数。
     */
    private void updateStatus(long offset) {
        long realNow = NtpClient.realNowMs(offset);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        String bj = sdf.format(new Date(realNow));

        if (Prefs.getUseSystemTime(this)) {
            tvStatus.setText("手机系统时间：" + bj
                    + "\n本次实测：未进行（系统时间模式不校时）"
                    + "\n排程采用：0 ms"
                    + "\n" + Prefs.getOffsetReason(this)
                    + "\n说明：点击时刻完全跟随系统时钟，依赖系统「自动设置时间」保持准确");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("网络北京时间：").append(bj);

        // ① 本次实测：无论可信与否都如实显示，带来源与时刻
        if (Prefs.wasMeasureAttempted(this)) {
            SimpleDateFormat ts = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
            ts.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            sb.append("\n本次实测：")
                    .append(String.format(Locale.CHINA, "%+d", Prefs.getMeasuredOffset(this)))
                    .append(" ms（").append(Prefs.getMeasuredSource(this))
                    .append(" · ").append(ts.format(new Date(Prefs.getMeasuredAt(this))))
                    .append("）")
                    .append(Prefs.isSyncReliable(this) ? " · 可信" : " · 不可信/未采用");
        } else {
            sb.append("\n本次实测：校时未完成或失败（无可用时间源）");
        }
        if (!Prefs.isSyncReliable(this)
                && Math.abs(Prefs.getMeasuredOffset(this)) > TimePolicy.SUSPICIOUS_OFFSET_MS) {
            sb.append("\n说明：本次链路延迟过大，这个大数值可能是测量伪影，不是手机时钟真偏了这么多，已忽略");
        }

        // ② 真正影响点击时刻的数字：排程采用的偏差
        sb.append("\n排程采用：")
                .append(offset == 0 ? "0 ms（手机系统时间）"
                        : String.format(Locale.CHINA, "%+d ms", offset));
        String reason = Prefs.getOffsetReason(this);
        if (reason != null && !reason.isEmpty()) {
            sb.append("\n").append(reason);
        }

        String detail = Prefs.getSyncDetail(this);
        if (detail != null && !detail.isEmpty()) {
            sb.append("\n各源实测：\n").append(detail);
        }
        if (Math.abs(offset) > TimePolicy.SUSPICIOUS_OFFSET_MS) {
            sb.append("\n提示：多源一致测出手机时钟偏了 ").append(offset)
                    .append("ms，APP 已自动补偿；也可在系统设置里关开一次「自动设置时间」让系统时钟归位");
        }
        tvStatus.setText(sb.toString());
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
        // 并列显示手机系统时钟，偏差真假肉眼可辨（两行差值应等于状态区的偏差值）
        String sysText = sdf.format(new Date(System.currentTimeMillis()));

        // 计算最近一个尚未到来的触发时刻（含裕度）
        StringBuilder sb = new StringBuilder();
        sb.append("北京时间 ").append(nowText);
        sb.append("\n手机时钟 ").append(sysText);

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
                target += buffer;
                // 裕度加完后再判断是否已过去（负裕度时可能落回过去），过了就取次日
                while (target <= nowReal) target += 86400_000L;
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
                String offsetTxt = (offset == 0) ? "排程偏差 0" :
                        String.format(Locale.CHINA, "排程偏差 %+dms", offset);
                long rtt = Prefs.getSyncRtt(this);
                if (rtt >= 0 && !Prefs.isSyncReliable(this)
                        && !Prefs.getUseSystemTime(this) && Prefs.wasMeasureAttempted(this)) {
                    offsetTxt += "（本次校时未采信）";
                }
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
        new Thread(() -> {
            final long fOffset;
            final String note;
            if (Prefs.getUseSystemTime(MainActivity.this)) {
                Prefs.saveOffset(MainActivity.this, 0);
                Prefs.saveSyncInfo(MainActivity.this, "手机系统时间（用户指定）", 0, true,
                        "偏差恒为 0", 0, 1);
                Prefs.saveMeasure(MainActivity.this, false, 0, "未校时（系统时间模式）", 0);
                TimePolicy.Decision d = TimePolicy.systemMode();
                Prefs.saveOffsetReason(MainActivity.this, d.reason);
                fOffset = 0;
                note = "\n只用系统时间（偏差 0）";
            } else {
                // 与界面自动校时、开机恢复走同一套决策，避免各处策略不一致
                TimePolicy.Decision d = TimePolicy.decide(MainActivity.this, NtpClient.sync());
                fOffset = d.offsetMs;
                note = "\n" + d.reason;
                Prefs.saveOffset(MainActivity.this, fOffset);
                Prefs.saveOffsetReason(MainActivity.this, d.reason);
            }
            runOnUiThread(() -> {
                // scheduleAll 内部会先清空所有历史闹钟再重新注册（时间点或裕度可能刚改过）
                AlarmScheduler.scheduleAll(MainActivity.this, fOffset);
                updateStatus(fOffset);
                Toast.makeText(MainActivity.this,
                        "已启用 " + times.size() + " 个定时点击（排程偏差 " + fOffset
                                + "ms，" + (bufferMs < 0 ? "提前 " + Math.abs(bufferMs) + "ms 点击" : "到点后延迟 " + bufferMs + "ms 再点")
                                + "）" + note,
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

    /**
     * 渲染点击日志：按真实触发时间倒序（最新在上），分页展开。
     * 上限放宽到 500 条后，若一次性把全部条目生成成 TextView，打开界面和点击标记都会明显卡顿，
     * 因此默认只渲染最近 50 条，底部提供「加载更多 / 显示全部」。
     */
    private void renderLog() {
        containerLog.removeAllViews();
        tvLogCount = null;
        List<LogStore.Entry> list = LogStore.list(this);
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("（暂无日志。到点自动点击后会自动记录一条，点击该条可标记成功/失败）");
            containerLog.addView(empty);
            return;
        }
        // 按真实触发时间倒序：最近一次点击排最上面。
        // 用时间而非 id，是因为恢复日志时导入的旧记录会被重新分配更大的 id。
        list.sort((a, b) -> Long.compare(b.realTimeMs, a.realTimeMs));

        final int total = list.size();
        final int shown = Math.min(logShown, total);

        tvLogCount = new TextView(this);
        tvLogCount.setTextSize(12);
        tvLogCount.setPadding(12, 4, 12, 10);
        containerLog.addView(tvLogCount);
        updateLogCountText(total, shown);

        java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.CHINA);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        for (int i = 0; i < shown; i++) {
            addLogRow(list.get(i), sdf);
        }

        if (shown < total) {
            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            Button more = new Button(this);
            more.setText("加载更多（还有 " + (total - shown) + " 条）");
            more.setTextSize(12);
            more.setMinWidth(0);
            more.setMinimumWidth(0);
            more.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            more.setOnClickListener(v -> {
                logShown += LOG_PAGE_SIZE;
                renderLog();
            });
            bar.addView(more);
            Button all = new Button(this);
            all.setText("显示全部 " + total + " 条");
            all.setTextSize(12);
            all.setMinWidth(0);
            all.setMinimumWidth(0);
            all.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            all.setOnClickListener(v -> {
                logShown = LogStore.MAX;
                renderLog();
                Toast.makeText(this, "已展开全部 " + total + " 条（条数多时可能略卡）",
                        Toast.LENGTH_SHORT).show();
            });
            bar.addView(all);
            containerLog.addView(bar);
        } else if (total > LOG_PAGE_SIZE) {
            TextView end = new TextView(this);
            end.setText("— 已显示全部 " + total + " 条 —");
            end.setTextSize(12);
            end.setPadding(12, 6, 12, 12);
            containerLog.addView(end);
        }
    }

    private void updateLogCountText(int total, int shown) {
        if (tvLogCount == null) return;
        tvLogCount.setText("共 " + total + " 条 · 当前显示 " + shown + " 条 · 最多保留 "
                + LogStore.MAX + " 条（最新在上）");
    }

    private String buildRowText(LogStore.Entry e, int result,
                                java.text.SimpleDateFormat sdf) {
        String res = result == LogStore.RESULT_SUCCESS ? "成功"
                : result == LogStore.RESULT_FAILURE ? "失败" : "未标记";
        return "#" + e.id + "  " + sdf.format(new java.util.Date(e.realTimeMs))
                + "  偏差 " + (e.offsetMs > 0 ? "+" : "") + e.offsetMs
                + "ms  裕度 " + e.bufferMs + "ms\n"
                + "    " + e.detail + "  → [" + res + "]（点击切换：成功→失败→成功）";
    }

    private int rowBg(int result) {
        if (result == LogStore.RESULT_SUCCESS) return 0xFFE8F5E9;
        if (result == LogStore.RESULT_FAILURE) return 0xFFFFEBEE;
        return 0xFFE8E8E8;
    }

    private void addLogRow(final LogStore.Entry e, final java.text.SimpleDateFormat sdf) {
        final long id = e.id;
        final int[] cur = { e.result };
        final TextView row = new TextView(this);
        row.setText(buildRowText(e, cur[0], sdf));
        row.setPadding(12, 8, 12, 8);
        row.setBackground(new android.graphics.drawable.ColorDrawable(rowBg(cur[0])));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 5;
        row.setLayoutParams(lp);
        // 标记成功/失败：只更新这一行，不重建整个列表（500 条时全表重建会明显卡顿）
        row.setOnClickListener(v -> {
            int next = (cur[0] == LogStore.RESULT_SUCCESS)
                    ? LogStore.RESULT_FAILURE : LogStore.RESULT_SUCCESS;
            LogStore.mark(this, id, next);
            cur[0] = next;
            row.setText(buildRowText(e, next, sdf));
            row.setBackground(new android.graphics.drawable.ColorDrawable(rowBg(next)));
            Toast.makeText(this, "已标记日志#" + id + " 为「"
                    + (next == LogStore.RESULT_SUCCESS ? "成功" : "失败") + "」",
                    Toast.LENGTH_SHORT).show();
        });
        // 长按删除：只移除这一行并刷新计数
        row.setOnLongClickListener(v -> {
            LogStore.remove(this, id);
            containerLog.removeView(row);
            int total = LogStore.list(this).size();
            if (total == 0) {
                renderLog();
            } else {
                updateLogCountText(total, Math.min(logShown, total));
            }
            Toast.makeText(this, "已删除日志#" + id, Toast.LENGTH_SHORT).show();
            return true;
        });
        containerLog.addView(row);
    }

    /** 备份日志：弹出系统「另存为」，由用户选保存位置（建议 Download）。 */
    private void exportLogs() {
        exportLogLauncher.launch("qqmusic_log_backup_" + System.currentTimeMillis() + ".json");
    }

    /** 从文件恢复日志（合并模式，不覆盖现有日志）。 */
    private void importLogs() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        importLogLauncher.launch(intent.getType());
    }
}
