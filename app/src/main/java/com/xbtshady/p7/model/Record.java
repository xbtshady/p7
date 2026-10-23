package com.xbtshady.p7.model;

import org.json.JSONObject;

/**
 * 一次提交的记录。架构文档 §5：records 只增不覆盖。
 *
 * <p>只有两种情况写：点"提交"、挑战档倒计时归零。
 * 退出 / 返回键 / 杀进程 / 挑战档还停在"第一步"输入阶段 —— 都不算一次作答（§8.1）。
 */
public final class Record {

    public final String qid;
    public final long ts;
    public final boolean ok;
    /** 实际用时，毫秒；超时记录等于时限 90000 */
    public final long ms;
    /** loose / standard / challenge */
    public final String aux;
    /** 自报卡点：hold / update / convert / null。可回写（超时记录选中卡点后） */
    public String stuck;

    public Record(String qid, long ts, boolean ok, long ms, String aux, String stuck) {
        this.qid = qid;
        this.ts = ts;
        this.ok = ok;
        this.ms = ms;
        this.aux = aux;
        this.stuck = stuck;
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
                    stuck);
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
        } catch (Throwable ignored) {
        }
        return o;
    }
}
