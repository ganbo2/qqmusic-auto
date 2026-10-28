package com.ganbo2.qqmusicauto;

import android.Manifest;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
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
    private final List<String> times = new ArrayList<>();

    private final ActivityResultLauncher<String> notifPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});

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

        findViewById(R.id.btnAccessibility).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.btnAddTime).setOnClickListener(v -> showTimePicker());
        findViewById(R.id.btnSave).setOnClickListener(v -> saveAndSchedule());
        findViewById(R.id.btnTest).setOnClickListener(v -> testNow());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
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
            Toast.makeText(this, "裕度必须是数字（毫秒，0~5000）", Toast.LENGTH_SHORT).show();
            return;
        }
        if (bufferMs < 0 || bufferMs > 5000) {
            Toast.makeText(this, "裕度必须在 0~5000 毫秒之间", Toast.LENGTH_SHORT).show();
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
                                + "ms，到点后延迟 " + bufferMs + "ms 再点）",
                        Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private void testNow() {
        saveAndSchedule();
        Intent trigger = new Intent(AutoClickService.ACTION_TRIGGER);
        trigger.setPackage(getPackageName());
        sendBroadcast(trigger);
        Toast.makeText(this, "已立即触发一次点击（请确保「立即提现」页面已打开）", Toast.LENGTH_SHORT).show();
    }
}
