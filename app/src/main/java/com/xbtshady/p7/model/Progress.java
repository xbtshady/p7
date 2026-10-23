package com.xbtshady.p7.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * progress.json。架构文档 §5。
 *
 * <p>两个派生值都从 records 现算，不落盘：
 * <ul>
 *   <li>某题"上次结果" = 该 qid <b>最早</b>那条记录的 ok；没有记录显示"未做"。重做不改变它。</li>
 *   <li>某级"已做数" = records 里属于该级的 qid <b>去重</b>后的数量。反复做同一题只算 1。</li>
 * </ul>
 */
public final class Progress {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_RECORDS = 5000;

    public int schemaVersion = SCHEMA_VERSION;
    public int bankVersion;
    public String auxTier = "standard";
    public String lastQid;
    public final List<Record> records = new ArrayList<>();

    /**
     * 某题的展示结果：该题最早一条记录的 ok。null = 未做。
     * 注意取的是<b>最早</b>，不是最新 —— 重做不改变列表显示的首次成绩（§5 / 前端设计 §2）。
     */
    public Boolean firstResultOf(String qid) {
        Boolean r = null;
        for (Record rec : records) {
            if (rec.qid.equals(qid)) {
                r = rec.ok;
                break; // records 追加有序，第一条即最早
            }
        }
        return r;
    }

    /** 某级已做的不同题号数。qidsOfLevel 为该级全部题号。 */
    public int doneCountOf(Set<String> qidsOfLevel) {
        Set<String> done = new java.util.HashSet<>();
        for (Record rec : records) {
            if (qidsOfLevel.contains(rec.qid)) done.add(rec.qid);
        }
        return done.size();
    }

    /** 写最旧（§5：容量上限 5000 条，超出丢最旧） */
    public void trim() {
        if (records.size() > MAX_RECORDS) {
            int drop = records.size() - MAX_RECORDS;
            records.subList(0, drop).clear();
        }
    }

    /** 找到最后一条指定 qid 的记录，用于卡点回写（§8.1 超时后回写 stuck） */
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
            JSONArray arr = new JSONArray();
            for (Record r : records) arr.put(r.toJson());
            o.put("records", arr);
        } catch (Throwable ignored) {
        }
        return o;
    }
}
