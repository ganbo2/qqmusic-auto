package com.ganbo2.qqmusicauto;

import android.content.Context;

/**
 * 时钟偏差的唯一决策入口：设置页、开机广播都走这里，避免各处策略不一致。
 *
 * <p>旧版在「校时不可信 / 校时失败」时会无限期沿用上一次存下来的偏差，
 * 于是早期 HTTP 兜底算出的 +1800ms 脏值会一直显示、并一直参与排程。
 * 现在只有「可信的多源 NTP 结果」才写入偏差，其余情况一律回退到 0
 * —— 手机开启「自动设置时间」时，系统时钟比一次不可信的网络测量更可靠。</p>
 *
 * <p>同时把「本次实测值」和「排程采用值」分别落盘，界面才能把两者分开显示，
 * 不再把历史值误读成本次新测出来的偏差。</p>
 */
public class TimePolicy {
    /** 超过这个量级的历史偏差视为可疑值，不再沿用。 */
    public static final long SUSPICIOUS_OFFSET_MS = 800;

    public static class Decision {
        public final long offsetMs;
        public final String reason;
        public final boolean fromMeasure;

        Decision(long offsetMs, String reason, boolean fromMeasure) {
            this.offsetMs = offsetMs;
            this.reason = reason;
            this.fromMeasure = fromMeasure;
        }
    }

    /** 「只用手机系统时间」模式：偏差恒 0，不做任何测量。 */
    public static Decision systemMode() {
        return new Decision(0, "用户指定只用手机系统时间，偏差恒为 0", false);
    }

    /** 根据一次校时结果决定排程采用的偏差，并把诊断信息落盘。 */
    public static Decision decide(Context c, NtpClient.Result res) {
        long old = Prefs.getOffset(c);
        if (res == null) {
            Prefs.saveSyncInfo(c, "校时失败", -1, false, "", -1, 0);
            Prefs.saveMeasure(c, false, 0, "校时失败（无可用时间源）", System.currentTimeMillis());
            if (Math.abs(old) > SUSPICIOUS_OFFSET_MS) {
                return new Decision(0,
                        "本次校时失败；历史偏差 " + old + "ms 可疑，已按手机系统时间（0）排程", false);
            }
            return new Decision(old, "本次校时失败，沿用上次可信偏差 " + old + "ms", false);
        }

        Prefs.saveSyncInfo(c, res.source, res.rttMs, res.reliable,
                res.detail, res.spreadMs, res.sampleCount);
        Prefs.saveMeasure(c, true, res.offsetMs, res.source, System.currentTimeMillis());

        if (res.reliable) {
            return new Decision(res.offsetMs,
                    "本次多源校时可信，采用实测值 " + res.offsetMs + "ms", true);
        }
        return new Decision(0,
                "本次校时不可信（往返 " + res.rttMs + "ms，实测 " + res.offsetMs
                        + "ms 未采用）；已按手机系统时间（偏差 0）排程。"
                        + "已开启「自动设置时间」时这是最稳的基准", false);
    }

    /** 落盘决策；仅当偏差真的变化时才重排闹钟，避免每次打开界面都动已注册的闹钟。 */
    public static void apply(Context c, Decision d) {
        long old = Prefs.getOffset(c);
        Prefs.saveOffset(c, d.offsetMs);
        Prefs.saveOffsetReason(c, d.reason);
        if (old != d.offsetMs) {
            AlarmScheduler.scheduleAll(c, d.offsetMs);
        }
    }
}
