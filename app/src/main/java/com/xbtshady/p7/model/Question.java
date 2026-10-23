package com.xbtshady.p7.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 一道题。对应 bank.json 的 questions[] 里的一项。
 *
 * <p>架构文档 §4.2：不引 Gson/Jackson，手取字段。§4.3：校验不过的题直接丢弃，不抛异常。
 *
 * <p>只持有数据和类型化 getter，不碰资产、不碰进度、不写存储（§4.4）。
 */
public final class Question {

    public static final String TYPE_TRUTH = "truth";
    public static final String TYPE_LEDGER = "ledger";
    public static final String MODE_ANSWER = "answer";

    public final String id;
    public final String type;
    public final int level;
    public final String mode;
    public final String prototype;

    private final JSONObject prompt;
    private final JSONObject answer;
    private final JSONObject solution;

    private Question(String id, String type, int level, String mode, String prototype,
                     JSONObject prompt, JSONObject answer, JSONObject solution) {
        this.id = id;
        this.type = type;
        this.level = level;
        this.mode = mode;
        this.prototype = prototype;
        this.prompt = prompt;
        this.answer = answer;
        this.solution = solution;
    }

    /**
     * 按 §4.3 校验。返回 null 表示这是一道坏题，调用方应丢弃并记录日志。
     * 任何解析异常都被吞掉并转成 null —— 一道坏题不能毁掉整个 app。
     */
    public static Question from(JSONObject o) {
        if (o == null) return null;
        try {
            String id = o.optString("id", "");
            if (id.isEmpty()) return null;

            String type = o.optString("type", "");
            if (!TYPE_TRUTH.equals(type) && !TYPE_LEDGER.equals(type)) return null;

            // §4.3 公共项
            if (!MODE_ANSWER.equals(o.optString("mode", ""))) return null;
            int level = o.optInt("level", -1);
            if (level < 1 || level > 5) return null;

            JSONObject prompt = o.optJSONObject("prompt");
            JSONObject answer = o.optJSONObject("answer");
            JSONObject solution = o.optJSONObject("solution");
            if (prompt == null || answer == null || solution == null) return null;

            if (TYPE_TRUTH.equals(type)) {
                if (!validateTruth(prompt, answer, solution)) return null;
            } else {
                if (!validateLedger(prompt, answer, solution)) return null;
            }

            String proto = o.optString("prototype", null);
            return new Question(id, type, level, MODE_ANSWER, proto, prompt, answer, solution);
        } catch (Throwable t) {
            return null;
        }
    }

    /** §4.3 truth 必过项 */
    private static boolean validateTruth(JSONObject prompt, JSONObject answer, JSONObject solution) {
        JSONArray people = prompt.optJSONArray("people");
        if (people == null || people.length() == 0) return false;

        List<String> names = new ArrayList<>();
        for (int i = 0; i < people.length(); i++) {
            JSONObject p = people.optJSONObject(i);
            if (p == null) return false;
            String name = p.optString("name", "");
            String says = p.optString("says", "");
            if (name.isEmpty() || says.isEmpty()) return false;
            names.add(name);
        }

        if (prompt.optString("constraint", "").isEmpty()) return false;

        JSONObject truth = answer.optJSONObject("truth");
        if (truth == null) return false;
        // 键必须与 people 完全一致
        if (truth.length() != names.size()) return false;
        int falseCount = 0;
        for (String n : names) {
            if (!truth.has(n)) return false;
            if (!truth.optBoolean(n)) falseCount++;
        }
        // 2.0：liars 若存在必须与 truth 中 false 个数一致，否则丢弃（禁止纠正入库）
        if (answer.has("liars")) {
            if (!isInt(answer.opt("liars"))) return false;
            if (answer.optInt("liars", -1) != falseCount) return false;
        }

        JSONArray lines = solution.optJSONArray("lines");
        return lines != null && lines.length() > 0;
    }

    /** §4.3 ledger 必过项 */
    private static boolean validateLedger(JSONObject prompt, JSONObject answer, JSONObject solution) {
        JSONArray narrative = prompt.optJSONArray("narrative");
        if (narrative == null || narrative.length() == 0) return false;

        String q = prompt.optString("question", "");
        if (!"net".equals(q) && !"total".equals(q)) return false;

        if (!isInt(answer.opt("value"))) return false;

        JSONArray ledger = solution.optJSONArray("ledger");
        return ledger != null && ledger.length() > 0;
    }

    private static boolean isInt(Object v) {
        return v instanceof Integer || v instanceof Long || v instanceof Double;
    }

    // ---- 类型化 getter，供 renderer 使用 ----

    /** truth: prompt.people 数组（每项含 name / says） */
    public JSONArray people() {
        return prompt.optJSONArray("people");
    }

    /** truth: "恰好两人撒谎" */
    public String constraint() {
        return prompt.optString("constraint", "");
    }

    /** ledger: 叙事句子数组 */
    public JSONArray narrative() {
        return prompt.optJSONArray("narrative");
    }

    /** ledger: "net" 或 "total" */
    public String ledgerQuestion() {
        return prompt.optString("question", "net");
    }

    /** truth: {A:true, B:false, ...} */
    public JSONObject answerTruth() {
        return answer.optJSONObject("truth");
    }

    /** truth: 撒谎人数 */
    public int answerLiars() {
        return answer.optInt("liars", -1);
    }

    /** ledger: 答案数值 */
    public int answerValue() {
        return answer.optInt("value", 0);
    }

    /** 两种题型都有：解析正文行 */
    public JSONArray solutionLines() {
        return solution.optJSONArray("lines");
    }

    /** ledger: 逐笔状态表（step / cash / hold） */
    public JSONArray solutionLedger() {
        return solution.optJSONArray("ledger");
    }

    public String solutionSummary() {
        return solution.optString("summary", "");
    }

    public boolean isTruth() {
        return TYPE_TRUTH.equals(type);
    }

    /** 人数（题库列表展示） */
    public int peopleCount() {
        JSONArray p = people();
        return p == null ? 0 : p.length();
    }

    /** 产品池：truth + answer mode */
    public boolean inProductPool() {
        return isTruth() && MODE_ANSWER.equals(mode);
    }

    /** 展示用的题型名 */
    public String typeLabel() {
        return TYPE_TRUTH.equals(type) ? "真假话" : "账本";
    }
}
