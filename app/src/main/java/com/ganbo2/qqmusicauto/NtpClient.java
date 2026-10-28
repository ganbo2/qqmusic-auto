package com.ganbo2.qqmusicauto;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/**
 * 通过网络获取「最精准的北京时间」，并算出与手机系统时钟的偏差（毫秒）。
 * 优先用 NTP（UDP 123，多个服务器回退），失败再用 HTTP 时间接口兜底。
 * 返回值的含义：真实时间(毫秒) - System.currentTimeMillis()
 */
public class NtpClient {
    private static final String TAG = "NtpClient";

    private static final String[] NTP_SERVERS = {
            "time.google.com",
            "cn.ntp.org.cn",
            "ntp.aliyun.com",
            "pool.ntp.org"
    };
    private static final int NTP_PORT = 123;
    private static final int TIMEOUT = 4000;
    private static final long OFFSET_1900_TO_1970 = 2208988800L;

    /** 返回与系统时钟的偏差（毫秒）；全部失败返回 null。 */
    public static Long getOffsetMs() {
        for (String server : NTP_SERVERS) {
            try {
                long offset = queryNtp(server);
                Log.d(TAG, "ntp offset from " + server + " = " + offset + " ms");
                return offset;
            } catch (Exception e) {
                Log.w(TAG, "ntp failed: " + server + " -> " + e.getMessage());
            }
        }
        // NTP 全部失败，尝试 HTTP 兜底
        try {
            long offset = queryHttp();
            Log.d(TAG, "http offset = " + offset + " ms");
            return offset;
        } catch (Exception e) {
            Log.w(TAG, "http time failed: " + e.getMessage());
        }
        return null;
    }

    /** 真实北京时间（毫秒）= 系统时间 + 偏差。 */
    public static long realNowMs(long offsetMs) {
        return System.currentTimeMillis() + offsetMs;
    }

    private static long queryNtp(String host) throws Exception {
        InetAddress address = InetAddress.getByName(host);
        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(TIMEOUT);
        try {
            byte[] buf = new byte[48];
            buf[0] = 0x1B; // LI=0, VN=3, Mode=3 (客户端)
            long requestTime = System.currentTimeMillis();
            long requestTicks = System.nanoTime();
            socket.send(new DatagramPacket(buf, buf.length, address, NTP_PORT));

            socket.receive(new DatagramPacket(buf, buf.length));
            long responseTicks = System.nanoTime();
            long responseTime = System.currentTimeMillis();

            // 传输时间戳（服务器发出回复的时刻），64 位定点，前 32 位为 1900 起秒数
            long seconds = read32(buf, 40);
            long fraction = read32(buf, 44);
            long t3 = ((seconds - OFFSET_1900_TO_1970) * 1000)
                    + ((fraction * 1000L) / 0x100000000L);

            long roundTripNs = responseTicks - requestTicks;
            long roundTripMs = TimeUnit.NANOSECONDS.toMillis(roundTripNs);
            long localMid = requestTime + roundTripMs / 2; // 往返中点（系统时钟）
            return t3 - localMid; // 与系统时钟的偏差
        } finally {
            socket.close();
        }
    }

    private static long queryHttp() throws Exception {
        URL u = new URL("https://worldtimeapi.org/api/timezone/Asia/Shanghai");
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setRequestProperty("User-Agent", "qqmusic-auto");
        BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        String s = sb.toString();
        int i = s.indexOf("\"unixtime\"");
        if (i < 0) throw new Exception("no unixtime field");
        int c = s.indexOf(':', i) + 1;
        int end = s.indexOf(',', c);
        if (end < 0) end = s.indexOf('}', c);
        long unix = Long.parseLong(s.substring(c, end).trim());
        long serverMs = unix * 1000L;
        return serverMs - System.currentTimeMillis();
    }

    private static long read32(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 4; i++) {
            v = (v << 8) | (b[off + i] & 0xFFL);
        }
        return v;
    }
}
