package com.xbtshady.p7.engine;

import android.util.Log;

import com.xbtshady.p7.model.Question;

import org.json.JSONObject;

import java.util.Map;

/**
 * docs/2.0：truth 仅比对 answer.truth；玩家不填 liars。
 */
public final class Grader {

    private static final String TAG = "p7";

    public static final class Result {
        public final boolean ok;
        public final String signal;

        Result(boolean ok, String signal) {
            this.ok = ok;
            this.signal = signal;
        }
    }

    public static Result gradeTruth(Question q, TruthRenderer.Answer a) {
        JSONObject truth = q.answerTruth();
        if (truth == null) return new Result(false, null);

        boolean partial = a == null || a.people == null || a.people.size() < truth.length();
        boolean allSame = a != null && a.people != null && !a.people.isEmpty()
                && allValuesSame(a.people);

        boolean ok = false;
        if (a != null && a.people != null && a.people.size() == truth.length()) {
            boolean peopleOk = true;
            java.util.Iterator<String> it = truth.keys();
            while (it.hasNext()) {
                String name = it.next();
                Boolean v = a.people.get(name);
                if (v == null || v.booleanValue() != truth.optBoolean(name)) {
                    peopleOk = false;
                    break;
                }
            }
            ok = peopleOk;
        }

        String signal = allSame ? "allSame" : (partial ? "partial" : null);
        Log.i(TAG, "truth " + q.id + " ok=" + ok + " signal=" + signal);
        return new Result(ok, signal);
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
