package com.xbtshady.p7.model;

import org.json.JSONObject;

/**
 * 一次提交。docs/2.0 架构 §5：只增不覆盖；带写入时的 bankVersion。
 */
public final class Record {

    public final String qid;
    public final long ts;
    public final boolean ok;
    public final long ms;
    public final String aux;
    /** 废弃：仅兼容旧 progress；新写入恒为 null */
    public String stuck;
    /** 写入时题库版本；旧数据缺省为 0 */
    public final int bankVersion;

    public Record(String qid, long ts, boolean ok, long ms, String aux, String stuck, int bankVersion) {
        this.qid = qid;
        this.ts = ts;
        this.ok = ok;
        this.ms = ms;
        this.aux = aux;
        this.stuck = stuck;
        this.bankVersion = bankVersion;
    }

    public static Record from(JSONObject o) {
        if (o == null) return null;
        try {
            String stuck = o.isNull("stuck") ? null : o.optString("stuck", null);
            return new Record(
                    o.optString("qid", ""),
                    o.optLong("ts", 0L),
                    o.optBoolean("ok", false),
                    o.optLong("ms", 0L),
                    o.optString("aux", "standard"),
                    stuck,
                    o.optInt("bankVersion", 0));
        } catch (Throwable t) {
            return null;
        }
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("qid", qid);
            o.put("ts", ts);
            o.put("ok", ok);
            o.put("ms", ms);
            o.put("aux", aux);
            o.put("stuck", stuck == null ? JSONObject.NULL : stuck);
            o.put("bankVersion", bankVersion);
        } catch (Throwable ignored) {
        }
        return o;
    }
}
