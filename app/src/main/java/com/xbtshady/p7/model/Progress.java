package com.xbtshady.p7.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * progress.json。docs/2.0 架构 §5。
 */
public final class Progress {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_RECORDS = 10_000;
    public static final int FIRST_RATE_WINDOW = 50;

    public static final String STATUS_ALL = "all";
    public static final String STATUS_TODO = "todo";
    public static final String STATUS_TRIED = "tried";
    public static final String STATUS_SOLVED = "solved";

    public int schemaVersion = SCHEMA_VERSION;
    public int bankVersion;
    public String auxTier = "standard";
    public String lastQid;
    /** 概览默认难度：0=全部，1–5 */
    public int filterLevel = 2;
    public String filterStatus = STATUS_ALL;
    public final List<Record> records = new ArrayList<>();

    /** 未做 / 尝试 / 已对 */
    public enum QStatus { TODO, TRIED, SOLVED }

    public QStatus statusOf(String qid) {
        boolean any = false;
        boolean anyOk = false;
        for (Record rec : records) {
            if (!rec.qid.equals(qid)) continue;
            any = true;
            if (rec.ok) anyOk = true;
        }
        if (!any) return QStatus.TODO;
        if (anyOk) return QStatus.SOLVED;
        return QStatus.TRIED;
    }

    public boolean matchesStatusFilter(String qid, String filter) {
        if (filter == null || STATUS_ALL.equals(filter)) return true;
        QStatus s = statusOf(qid);
        if (STATUS_TODO.equals(filter)) return s == QStatus.TODO;
        if (STATUS_TRIED.equals(filter)) return s == QStatus.TRIED;
        if (STATUS_SOLVED.equals(filter)) return s == QStatus.SOLVED;
        return true;
    }

    public int solvedCountOf(Set<String> qidsOfLevel) {
        int n = 0;
        for (String id : qidsOfLevel) {
            if (statusOf(id) == QStatus.SOLVED) n++;
        }
        return n;
    }

    public int solvedTotal(Set<String> allIds) {
        return solvedCountOf(allIds);
    }

    /**
     * 首次正确率：各题首次记录按 ts 降序，取前 N，ok 比例；每题至多一次。
     * @return 0..1，无首次记录时返回 NaN
     */
    public double firstAttemptRate(int n) {
        Map<String, Record> first = new HashMap<>();
        for (Record rec : records) {
            if (!first.containsKey(rec.qid)) first.put(rec.qid, rec);
        }
        if (first.isEmpty()) return Double.NaN;
        List<Record> list = new ArrayList<>(first.values());
        Collections.sort(list, (a, b) -> Long.compare(b.ts, a.ts));
        int take = Math.min(n, list.size());
        int ok = 0;
        for (int i = 0; i < take; i++) {
            if (list.get(i).ok) ok++;
        }
        return ok / (double) take;
    }

    public int submissionsLast7Days() {
        long cut = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
        int n = 0;
        for (Record rec : records) {
            if (rec.ts >= cut) n++;
        }
        return n;
    }

    /** 最近提交，新→旧，最多 limit 条 */
    public List<Record> recent(int limit) {
        List<Record> out = new ArrayList<>();
        for (int i = records.size() - 1; i >= 0 && out.size() < limit; i--) {
            out.add(records.get(i));
        }
        return out;
    }

    /** 全部提交新→旧的一页 */
    public List<Record> historyPage(int offset, int limit) {
        List<Record> out = new ArrayList<>();
        int fromEnd = records.size() - 1 - offset;
        for (int i = fromEnd; i >= 0 && out.size() < limit; i--) {
            out.add(records.get(i));
        }
        return out;
    }

    public int historyCount() {
        return records.size();
    }

    /** 某题全部提交，新→旧，最多 limit 条 */
    public List<Record> recordsOf(String qid, int limit) {
        List<Record> out = new ArrayList<>();
        if (qid == null) return out;
        for (int i = records.size() - 1; i >= 0 && out.size() < limit; i--) {
            Record rec = records.get(i);
            if (qid.equals(rec.qid)) out.add(rec);
        }
        return out;
    }

    public void trim() {
        if (records.size() > MAX_RECORDS) {
            int drop = records.size() - MAX_RECORDS;
            records.subList(0, drop).clear();
        }
    }

    public Record lastRecordOf(String qid) {
        Record r = null;
        for (Record rec : records) {
            if (rec.qid.equals(qid)) r = rec;
        }
        return r;
    }

    public static Progress from(JSONObject o) {
        Progress p = new Progress();
        if (o == null) return p;
        try {
            p.schemaVersion = o.optInt("schemaVersion", SCHEMA_VERSION);
            p.bankVersion = o.optInt("bankVersion", 0);
            p.auxTier = o.optString("auxTier", "standard");
            p.lastQid = o.isNull("lastQid") ? null : o.optString("lastQid", null);
            p.filterLevel = o.optInt("filterLevel", 2);
            p.filterStatus = o.optString("filterStatus", STATUS_ALL);

            JSONArray arr = o.optJSONArray("records");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    Record r = Record.from(arr.optJSONObject(i));
                    if (r != null) p.records.add(r);
                }
            }
        } catch (Throwable ignored) {
        }
        return p;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("schemaVersion", schemaVersion);
            o.put("bankVersion", bankVersion);
            o.put("auxTier", auxTier);
            o.put("lastQid", lastQid == null ? JSONObject.NULL : lastQid);
            o.put("filterLevel", filterLevel);
            o.put("filterStatus", filterStatus);
            JSONArray arr = new JSONArray();
            for (Record r : records) arr.put(r.toJson());
            o.put("records", arr);
        } catch (Throwable ignored) {
        }
        return o;
    }
}
