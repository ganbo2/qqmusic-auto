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
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 网络校时：算出「真实北京时间 - 手机系统时钟」的偏差（毫秒）。
 *
 * <p>关键：单次 NTP 的 offset 依赖「上下行对称」假设，误差上限约为往返延迟的一半。
 * 跨境服务器或移动网络下 RTT 可达数秒，算出的偏差会虚高上千毫秒并直接影响点击时刻。
 * 因此这里：国内节点优先、多源采样、按 RTT 过滤后取中位数，并给出「多源一致性」指标。</p>
 *
 * <p>注意：offset 大（比如 +1800ms）本身不代表校时不准，它通常就是手机系统时钟真的慢了
 * 1.8 秒。判断是否可信要看 RTT 与多源极差，而不是看偏差数值大小。</p>
 *
 * <p>UDP 123 被运营商封锁时，降级到返回毫秒时间戳的 HTTPS 接口。</p>
 */
public class NtpClient {
    private static final String TAG = "NtpClient";

    // 国内节点排前面
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
    /** HTTP 兜底采样次数：首次含 DNS/TLS 握手，后续可复用连接，取最优样本。 */
    private static final int HTTP_SAMPLES = 3;
    private static final int HTTP_TIMEOUT = 1500;
    private static final long OFFSET_1900_TO_1970 = 2208988800L;
    /** RTT 低于此值算低延迟样本；凑够 3 个即可停止，避免无谓等待。 */
    private static final long GOOD_RTT_MS = 120;
    /** RTT 高于此值的 NTP 样本不参与中位数计算。 */
    public static final long RELIABLE_RTT_MS = 300;
    /** HTTP 兜底含 DNS/TLS 握手，天然更慢，放宽以便至少能进入诊断明细。 */
    private static final long HTTP_MAX_RTT_MS = 2500;
    /** 多个独立源的 offset 极差超过此值，认为链路不对称，结果不可信。 */
    private static final long CONSISTENT_SPREAD_MS = 200;

    public static class Result {
        public long offsetMs;           // 真实时间 - 系统时间
        public long rttMs;              // 代表样本的往返延迟
        public String source;           // 校时来源
        public boolean reliable;        // 该偏差是否可用于排程
        public boolean secondPrecision; // 兜底接口只给到秒
        public long spreadMs = -1;      // 多源 offset 极差；-1 = 只有一个样本
        public int sampleCount;         // 参与决策的样本数
        public String detail = "";      // 每个源的明细，供界面诊断
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

    /**
     * 多源采样：过滤高 RTT / 秒级样本后取 offset 中位数。
     * 中位数可消除单个源因上下行不对称产生的偏差；多源极差用来判断结果是否自洽。
     * 全部失败返回 null。
     */
    public static Result sync() {
        List<Result> samples = new ArrayList<>();
        int ntpFailures = 0;
        for (String server : NTP_SERVERS) {
            try {
                Result r = queryNtp(server);
                samples.add(r);
                long fast = 0;
                for (Result s : samples) if (s.rttMs <= GOOD_RTT_MS) fast++;
                if (fast >= 3) break;
            } catch (Exception e) {
                ntpFailures++;
                Log.w(TAG, "ntp failed: " + server + " -> " + e.getMessage());
            }
            // 国内前两台都连不上，多半是 UDP 123 被运营商封了，不必再等跨境节点各 2 秒
            if (ntpFailures >= 2 && samples.isEmpty()) break;
        }
        // NTP 样本不足两个时用 HTTP 兜底；毫秒级接口内部已多次采样，成功即可停止
        if (samples.size() < 2) {
            for (String url : HTTP_FALLBACKS) {
                try {
                    Result r = queryHttp(url);
                    samples.add(r);
                    if (!r.secondPrecision) break;
                } catch (Exception e) {
                    Log.w(TAG, "http time failed: " + url + " -> " + e.getMessage());
                }
            }
        }
        if (samples.isEmpty()) return null;

        // 先拼明细（后面会改动代表样本的 offsetMs，明细要保留各源原始值）
        StringBuilder detail = new StringBuilder();
        for (Result s : samples) {
            if (detail.length() > 0) detail.append("\n");
            detail.append(String.format(Locale.CHINA, "%s  %+dms / 往返%dms%s",
                    s.source, s.offsetMs, s.rttMs,
                    s.secondPrecision ? "（秒级）" : ""));
        }

        List<Result> good = new ArrayList<>();
        for (Result s : samples) {
            long limit = s.source.startsWith("HTTP") ? HTTP_MAX_RTT_MS : RELIABLE_RTT_MS;
            if (s.rttMs <= limit && !s.secondPrecision) good.add(s);
        }

        // 只有 NTP 样本用了正确的「往返中点」补偿，才可能作为精确偏差；
        // HTTP 兜底拿到的是区间下界，天然低估（少了下行传输时间），
        // 直接采用会让闹钟系统性偏晚，因此只作诊断、不参与排程。
        List<Result> ntpGood = new ArrayList<>();
        for (Result s : good) {
            if (!s.source.startsWith("HTTP")) ntpGood.add(s);
        }

        Result best;
        if (ntpGood.size() >= 2) {
            List<Long> offs = new ArrayList<>();
            for (Result s : ntpGood) offs.add(s.offsetMs);
            java.util.Collections.sort(offs);
            long median = offs.get(offs.size() / 2);
            long spread = offs.get(offs.size() - 1) - offs.get(0);
            // 取 offset 最接近中位数的样本作为 RTT / 来源的代表
            Result rep = ntpGood.get(0);
            for (Result s : ntpGood) {
                if (Math.abs(s.offsetMs - median) < Math.abs(rep.offsetMs - median)) rep = s;
            }
            best = rep;
            best.offsetMs = median;
            best.spreadMs = spread;
            best.sampleCount = ntpGood.size();
            best.reliable = spread <= CONSISTENT_SPREAD_MS;
        } else {
            // NTP 样本不足两个：挑一个代表样本把明细带出去，但一律标记不可信，
            // 由 TimePolicy 回退到手机系统时间（偏差 0），绝不拿单源 HTTP 下界去排程。
            List<Result> pool = good.isEmpty() ? samples : good;
            pool.sort((a, b) -> Long.compare(a.rttMs, b.rttMs));
            best = pool.get(0);
            best.spreadMs = -1;
            best.sampleCount = pool.size();
            best.reliable = false;
        }
        best.detail = detail.toString();
        Log.d(TAG, "best: " + best.source + " offset=" + best.offsetMs
                + "ms rtt=" + best.rttMs + "ms samples=" + best.sampleCount
                + " spread=" + best.spreadMs + " reliable=" + best.reliable);
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

    /**
     * HTTPS 兜底。注意：服务器时间戳是「收到请求后生成响应时」给出的，靠近往返末尾；
     * 而 rtt 里还包含 DNS/TCP/TLS 握手。若按往返中点补偿，会把约 rtt/2 的握手时间
     * 错算成时钟偏差（实测 rtt=2050ms 时虚高 +1023ms，移动网络下可达 +1800ms）。
     *
     * <p>改用区间下界：时间戳必然早于客户端收到响应，因此
     * {@code trueOffset >= serverMs - (t0 + rtt)}。多次采样取最紧的下界，
     * 得到不会虚高的估计（但会低估一个下行传输时间，几十~几百毫秒）。
     * 因此该结果只能用于诊断「系统时钟是否明显偏慢」，不能当精确偏差采用；
     * 直接采用会让闹钟系统性偏晚，见 {@link NtpClient#sync()} 的可靠性判定。</p>
     */
    private static Result queryHttp(String urlStr) throws Exception {
        String host = new URL(urlStr).getHost();
        long bestLower = Long.MIN_VALUE;
        long minRtt = Long.MAX_VALUE;
        boolean secondPrecision = false;
        int ok = 0;
        for (int i = 0; i < HTTP_SAMPLES; i++) {
            long t0 = System.currentTimeMillis();
            long ticks0 = System.nanoTime();
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(HTTP_TIMEOUT);
                conn.setReadTimeout(HTTP_TIMEOUT);
                conn.setRequestProperty("User-Agent", "qqmusic-auto");
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                long ticks1 = System.nanoTime();
                long rttMs = TimeUnit.NANOSECONDS.toMillis(ticks1 - ticks0);

                String s = sb.toString();
                long serverMs = -1;
                boolean sec = false;
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"t\"\\s*:\\s*\"?(\\d{13})").matcher(s);
                if (m.find()) {
                    serverMs = Long.parseLong(m.group(1));
                } else {
                    int p = s.indexOf("\"unixtime\"");
                    if (p >= 0) {
                        int c = s.indexOf(':', p) + 1;
                        int end = s.indexOf(',', c);
                        if (end < 0) end = s.indexOf('}', c);
                        serverMs = Long.parseLong(s.substring(c, end).trim()) * 1000L;
                        sec = true; // 秒级时间戳，自带最多 999ms 截断误差
                    }
                }
                if (serverMs < 0) throw new Exception("no timestamp in response");
                secondPrecision |= sec;

                long lower = serverMs - (t0 + rttMs);
                if (lower > bestLower) bestLower = lower;
                if (rttMs < minRtt) minRtt = rttMs;
                ok++;
            } catch (Exception e) {
                Log.w(TAG, "http sample failed: " + host + " -> " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        if (ok == 0) throw new Exception("all http samples failed");

        Result res = new Result();
        res.offsetMs = bestLower;
        res.rttMs = minRtt;
        res.source = (secondPrecision ? "HTTP秒级 " : "HTTP ") + host + "（" + ok + "次采样）";
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
