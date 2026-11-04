package com.ganbo2.qqmusicauto;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 点击日志：每次触发点击后记录一条（真实触发时间、校时偏差、裕度、结果待标记）。
 * 用户可手动标记结果（成功/失败）或删除、清空。最多保留 {@link #MAX} 条。
 */
public class LogStore {
    public static final String KEY_LOG = "click_log";
    private static final int MAX = 100;

    /** 结果：0 = 待标记，1 = 成功，2 = 失败。 */
    public static final int RESULT_UNKNOWN = 0;
    public static final int RESULT_SUCCESS = 1;
    public static final int RESULT_FAILURE = 2;

    public static class Entry {
        public long id;
        public long realTimeMs;   // 真实（网络）触发时刻
        public long offsetMs;     // 校时偏差
        public int bufferMs;      // 裕度
        public int result;        // RESULT_*
        public String detail;     // 点击方式等说明
    }

    private static SharedPreferences get(Context c) {
        return c.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
    }

    /** 新增一条待标记日志，返回其 id。 */
    public static long add(Context c, long realTimeMs, long offsetMs, int bufferMs, String detail) {
        List<Entry> list = list(c);
        while (list.size() >= MAX) {
            // 移除最旧的一条（id 最小）
            long minId = Long.MAX_VALUE;
            for (Entry e : list) if (e.id < minId) minId = e.id;
            List<Entry> next = new ArrayList<>();
            for (Entry e : list) if (e.id != minId) next.add(e);
            list = next;
        }
        long nextId = list.isEmpty() ? 1 : list.get(list.size() - 1).id + 1;
        Entry e = new Entry();
        e.id = nextId;
        e.realTimeMs = realTimeMs;
        e.offsetMs = offsetMs;
        e.bufferMs = bufferMs;
        e.result = RESULT_UNKNOWN;
        e.detail = detail == null ? "" : detail;
        list.add(e);
        save(c, list);
        return nextId;
    }

    /** 标记某条日志的结果。 */
    public static void mark(Context c, long id, int result) {
        List<Entry> list = list(c);
        boolean changed = false;
        for (Entry e : list) {
            if (e.id == id) { e.result = result; changed = true; }
        }
        if (changed) save(c, list);
    }

    /** 删除某条日志。 */
    public static void remove(Context c, long id) {
        List<Entry> list = list(c);
        List<Entry> out = new ArrayList<>();
        for (Entry e : list) if (e.id != id) out.add(e);
        save(c, out);
    }

    /** 清空全部日志。 */
    public static void clear(Context c) {
        get(c).edit().putString(KEY_LOG, "[]").apply();
    }

    /** 读取全部日志（按 id 升序）。 */
    public static List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<>();
        String json = get(c).getString(KEY_LOG, null);
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Entry e = new Entry();
                e.id = o.getLong("id");
                e.realTimeMs = o.getLong("realTimeMs");
                e.offsetMs = o.getLong("offsetMs");
                e.bufferMs = o.optInt("bufferMs", 0);
                e.result = o.optInt("result", RESULT_UNKNOWN);
                e.detail = o.optString("detail", "");
                out.add(e);
            }
        } catch (Exception ex) {
            android.util.Log.e("LogStore", "load failed", ex);
        }
        return out;
    }

    /** 导出全部日志为 JSON 字符串（用于备份/迁移）。 */
    public static String exportJson(Context c) {
        List<Entry> list = list(c);
        JSONArray arr = new JSONArray();
        for (Entry e : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", e.id);
                o.put("realTimeMs", e.realTimeMs);
                o.put("offsetMs", e.offsetMs);
                o.put("bufferMs", e.bufferMs);
                o.put("result", e.result);
                o.put("detail", e.detail == null ? "" : e.detail);
                arr.put(o);
            } catch (Exception ex) {
                // skip
            }
        }
        return arr.toString();
    }

    /**
     * 从 JSON 导入日志：与现有日志合并（跳过重复 id），自动追加新 id。
     * 返回导入的条数。
     */
    public static int importJson(Context c, String json) {
        try {
            JSONArray arr = new JSONArray(json);
            List<Entry> list = list(c);
            List<Entry> toAdd = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Entry e = new Entry();
                e.id = o.getLong("id");
                e.realTimeMs = o.getLong("realTimeMs");
                e.offsetMs = o.getLong("offsetMs");
                e.bufferMs = o.optInt("bufferMs", 0);
                e.result = o.optInt("result", RESULT_UNKNOWN);
                e.detail = o.optString("detail", "");
                // 检查是否已存在
                boolean exists = false;
                for (Entry ex : list) {
                    if (ex.id == e.id) { exists = true; break; }
                }
                if (!exists) toAdd.add(e);
            }
            // 分配新 id，避免冲突
            long maxId = list.isEmpty() ? 0 : list.get(list.size() - 1).id;
            for (Entry e : toAdd) {
                maxId = Math.max(maxId, e.id);
            }
            for (Entry e : toAdd) {
                e.id = ++maxId;
                list.add(e);
            }
            // 限制最大条数
            while (list.size() > MAX) {
                list.remove(0);
            }
            save(c, list);
            return toAdd.size();
        } catch (Exception ex) {
            android.util.Log.e("LogStore", "import failed", ex);
            return 0;
        }
    }

    private static void save(Context c, List<Entry> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : list) {
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("realTimeMs", e.realTimeMs);
                o.put("offsetMs", e.offsetMs);
                o.put("bufferMs", e.bufferMs);
                o.put("result", e.result);
                o.put("detail", e.detail == null ? "" : e.detail);
                arr.put(o);
            }
            get(c).edit().putString(KEY_LOG, arr.toString()).apply();
        } catch (Exception ex) {
            android.util.Log.e("LogStore", "save failed", ex);
        }
    }
}
