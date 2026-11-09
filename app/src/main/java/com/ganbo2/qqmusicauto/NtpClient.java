package com.ganbo2.qqmusicauto;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 网络校时：算出「真实北京时间 - 手机系统时钟」的偏差（毫秒）。
 *
 * <p>关键：单次 NTP 的 offset 依赖「上下行对称」假设，误差上限约为往返延迟的一半。
 * 跨境服务器或移动网络下 RTT 可达数秒，算出的偏差会虚高上千毫秒并直接影响点击时刻。
 * 因此这里改为：国内节点优先、多源采样、按 RTT 择优，并把来源与 RTT 一并返回供界面展示。
 * UDP 123 被运营商封锁时，降级到返回毫秒时间戳的 HTTPS 接口。</p>
 */
public class NtpClient {
    private static final String TAG = "NtpClient";

    // 国内节点排前面：拿到低 RTT 样本即可提前结束，不必再试跨境链路
    private static final String[] NTP_SERVERS = {
            "ntp.aliyun.com",
            "cn.ntp.org.cn",
            "ntp1.aliyun.com",
            "time.google.com"
    };
    private static final String[] HTTP_FALLBACKS = {
            "https://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp",
            "https://worldtimeapi.org/api/timezone/Asia/Shanghai"
    };
    private static final int NTP_PORT = 123;
    private static final int TIMEOUT = 2000;
    private static final long OFFSET_1900_TO_1970 = 2208988800L;
    /** RTT 低于此值就足够精确，无需继续尝试其它服务器。 */
    private static final long GOOD_RTT_MS = 120;
    /** RTT 高于此值的样本视为不可信（误差可能达数百毫秒）。 */
    public static final long RELIABLE_RTT_MS = 300;

    public static class Result {
        public long offsetMs;          // 真实时间 - 系统时间
        public long rttMs;             // 本次校时往返延迟
        public String source;          // 校时来源，用于界面展示
        public boolean reliable;       // 该偏差是否可信
        public boolean secondPrecision; // 兜底接口只给到秒，精度天然不足
    }

    /** 兼容旧调用：只要偏差值。 */
    public static Long getOffsetMs() {
        Result r = sync();
        return r == null ? null : r.offsetMs;
    }

    /** 真实北京时间（毫秒）= 系统时间 + 偏差。 */
    public static long realNowMs(long offsetMs) {
        return System.currentTimeMillis() + offsetMs;
    }

    /** 多源采样，返回 RTT 最小的那次；全部失败返回 null。 */
    public static Result sync() {
        List<Result> samples = new ArrayList<>();
        for (String server : NTP_SERVERS) {
            try {
                Result r = queryNtp(server);
                samples.add(r);
                if (r.rttMs <= GOOD_RTT_MS) break;
            } catch (Exception e) {
                Log.w(TAG, "ntp failed: " + server + " -> " + e.getMessage());
            }
        }
        if (samples.isEmpty()) {
            for (String url : HTTP_FALLBACKS) {
                try {
                    Result r = queryHttp(url);
                    samples.add(r);
                    if (r.rttMs <= GOOD_RTT_MS && !r.secondPrecision) break;
                } catch (Exception e) {
                    Log.w(TAG, "http time failed: " + url + " -> " + e.getMessage());
                }
            }
        }
        if (samples.isEmpty()) return null;
        samples.sort((a, b) -> Long.compare(a.rttMs, b.rttMs));
        Result best = samples.get(0);
        best.reliable = best.rttMs <= RELIABLE_RTT_MS && !best.secondPrecision;
        Log.d(TAG, "best: " + best.source + " offset=" + best.offsetMs
                + "ms rtt=" + best.rttMs + "ms reliable=" + best.reliable);
        return best;
    }

    private static Result queryNtp(String host) throws Exception {
        InetAddress address = InetAddress.getByName(host);
        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(TIMEOUT);
        try {
            byte[] buf = new byte[48];
            buf[0] = 0x1B; // LI=0, VN=3, Mode=3 (客户端)
            long requestTime = System.currentTimeMillis();
            long requestTicks = System.nanoTime();
            socket.send(new DatagramPacket(buf, buf.length, address, NTP_PORT));

            DatagramPacket resp = new DatagramPacket(new byte[48], 48);
            socket.receive(resp);
            long responseTicks = System.nanoTime();
            byte[] r = resp.getData();

            // 校验响应：Mode 必须为 4（服务器），stratum 需在 1~15，避免错包算出离谱偏差
            int mode = r[0] & 0x07;
            int stratum = r[1] & 0xFF;
            if (mode != 4 || stratum < 1 || stratum > 15) {
                throw new Exception("bad ntp reply (mode=" + mode + ", stratum=" + stratum + ")");
            }

            // Transmit Timestamp：服务器发出响应的时刻（1900 起算的 64 位定点数）
            long seconds = read32(r, 40);
            long fraction = read32(r, 44);
            long serverTransmitMs = (seconds - OFFSET_1900_TO_1970) * 1000
                    + (fraction * 1000L) / 0x100000000L;

            long rttMs = TimeUnit.NANOSECONDS.toMillis(responseTicks - requestTicks);
            long localMid = requestTime + rttMs / 2; // 往返中点，抵消单程延迟

            Result res = new Result();
            res.offsetMs = serverTransmitMs - localMid;
            res.rttMs = rttMs;
            res.source = "NTP " + host;
            return res;
        } finally {
            socket.close();
        }
    }

    /** HTTPS 兜底：优先用返回 13 位毫秒时间戳的接口，并做往返中点补偿。 */
    private static Result queryHttp(String urlStr) throws Exception {
        long t0 = System.currentTimeMillis();
        long ticks0 = System.nanoTime();
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setRequestProperty("User-Agent", "qqmusic-auto");
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        conn.disconnect();
        long ticks1 = System.nanoTime();
        long rttMs = TimeUnit.NANOSECONDS.toMillis(ticks1 - ticks0);

        String s = sb.toString();
        long serverMs = -1;
        boolean secondPrecision = false;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"t\"\\s*:\\s*\"?(\\d{13})").matcher(s);
        if (m.find()) {
            serverMs = Long.parseLong(m.group(1));
        } else {
            int i = s.indexOf("\"unixtime\"");
            if (i >= 0) {
                int c = s.indexOf(':', i) + 1;
                int end = s.indexOf(',', c);
                if (end < 0) end = s.indexOf('}', c);
                serverMs = Long.parseLong(s.substring(c, end).trim()) * 1000L;
                secondPrecision = true; // 秒级时间戳，自带最多 999ms 截断误差
            }
        }
        if (serverMs < 0) throw new Exception("no timestamp in response");

        long localMid = t0 + rttMs / 2;
        Result res = new Result();
        res.offsetMs = serverMs - localMid;
        res.rttMs = rttMs;
        res.source = (secondPrecision ? "HTTP秒级 " : "HTTP ") + new URL(urlStr).getHost();
        res.secondPrecision = secondPrecision;
        return res;
    }

    private static long read32(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 4; i++) {
            v = (v << 8) | (b[off + i] & 0xFFL);
        }
        return v;
    }
}
