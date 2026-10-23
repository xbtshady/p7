package com.xbtshady.p7.engine;

import android.util.Log;

import com.xbtshady.p7.model.Question;

import org.json.JSONObject;

import java.util.Map;

/**
 * 架构文档 §8 判定。
 *
 * <p>truth：每人填的真假与 answer.truth 全等，<b>且</b>填的撒谎人数等于 answer.liars。
 * <p>ledger：填的数字等于 answer.value。
 *
 * <p>signal 只写日志，不显示、不影响抽题。
 */
public final class Grader {

    private static final String TAG = "p7";

    public static final class Result {
        public final boolean ok;
        /** allSame / partial / timeout / null */
        public final String signal;

        Result(boolean ok, String signal) {
            this.ok = ok;
            this.signal = signal;
        }
    }

    public static Result gradeTruth(Question q, TruthRenderer.Answer a) {
        JSONObject truth = q.answerTruth();
        if (truth == null) return new Result(false, null);

        boolean partial = a == null || a.liars == null;
        boolean allSame = a != null && a.people != null && !a.people.isEmpty()
                && allValuesSame(a.people);

        boolean ok = false;
        if (a != null && a.people != null && a.liars != null) {
            boolean peopleOk = a.people.size() == truth.length();
            if (peopleOk) {
                java.util.Iterator<String> it = truth.keys();
                while (it.hasNext()) {
                    String name = it.next();
                    Boolean v = a.people.get(name);
                    if (v == null || v.booleanValue() != truth.optBoolean(name)) {
                        peopleOk = false;
                        break;
                    }
                }
            }
            // 人数也必须对，不是只比每人真假
            ok = peopleOk && a.liars.intValue() == q.answerLiars();
        }

        String signal = allSame ? "allSame" : (partial ? "partial" : null);
        Log.i(TAG, "truth " + q.id + " ok=" + ok + " signal=" + signal);
        return new Result(ok, signal);
    }

    public static Result gradeLedger(Question q, Integer value) {
        boolean partial = value == null;
        boolean ok = !partial && value.intValue() == q.answerValue();
        Log.i(TAG, "ledger " + q.id + " ok=" + ok + " signal=" + (partial ? "partial" : null));
        return new Result(ok, partial ? "partial" : null);
    }

    private static boolean allValuesSame(Map<String, Boolean> m) {
        Boolean first = null;
        for (Boolean v : m.values()) {
            if (first == null) first = v;
            else if (first.booleanValue() != v.booleanValue()) return false;
        }
        return true;
    }
}
