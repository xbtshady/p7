package com.xbtshady.p7.data;

import android.content.Context;
import android.util.Log;

import com.xbtshady.p7.model.Question;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 读 assets/bank.json，启动一次读入内存（架构文档 §4.2：60 题约 80KB，不做增量加载）。
 *
 * <p>§4.3：逐题校验，任一项不过就丢弃该题 + 记一行日志，绝不抛异常。
 * 全部被丢弃则首页显示"题库异常"，不崩溃。
 */
public final class BankRepo {

    private static final String TAG = "p7";
    private static final String ASSET = "bank.json";
    /** §4.5：app 只向下兼容，不向上猜 */
    private static final int SUPPORTED_BANK_SCHEMA = 1;

    public enum Status { OK, EMPTY, MISMATCH, ERROR }

    private Status status = Status.ERROR;
    private int bankVersion;
    /** 顺序 = bank.json 的数组顺序，即题号顺序（前端设计 §2：不重排） */
    private final List<Question> all = new ArrayList<>();
    private final List<String> broken = new ArrayList<>();

    public Status status() {
        return status;
    }

    public int bankVersion() {
        return bankVersion;
    }

    /** 校验时丢弃的题 id，仅用于日志，不显示 */
    public List<String> brokenIds() {
        return broken;
    }

    public void load(Context ctx) {
        all.clear();
        broken.clear();
        try {
            String json = readAsset(ctx, ASSET);
            JSONObject root = new JSONObject(json);

            int schema = root.optInt("schemaVersion", -1);
            if (schema > SUPPORTED_BANK_SCHEMA) {
                // §4.5：首页提示"题库与 app 版本不匹配"，不出题
                status = Status.MISMATCH;
                Log.w(TAG, "bank schemaVersion " + schema + " > " + SUPPORTED_BANK_SCHEMA);
                return;
            }
            bankVersion = root.optInt("bankVersion", 0);

            JSONArray arr = root.optJSONArray("questions");
            if (arr == null) {
                status = Status.ERROR;
                return;
            }

            Set<String> seen = new HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject raw = arr.optJSONObject(i);
                Question q = Question.from(raw);
                if (q == null) {
                    String id = raw == null ? "#" + i : raw.optString("id", "#" + i);
                    broken.add(id);
                    Log.w(TAG, "丢弃坏题 " + id);
                    continue;
                }
                // §4.3：id 不重复
                if (!seen.add(q.id)) {
                    broken.add(q.id);
                    Log.w(TAG, "丢弃重复 id " + q.id);
                    continue;
                }
                all.add(q);
            }

            status = all.isEmpty() ? Status.EMPTY : Status.OK;
            Log.i(TAG, "题库加载 " + all.size() + " 题，丢弃 " + broken.size() + " 题");
        } catch (Throwable t) {
            status = Status.ERROR;
            Log.e(TAG, "题库加载失败", t);
        }
    }

    public List<Question> ofLevel(int level) {
        List<Question> out = new ArrayList<>();
        for (Question q : all) {
            if (q.level == level && Question.MODE_ANSWER.equals(q.mode)) out.add(q);
        }
        return out;
    }

    public Question byId(String id) {
        for (Question q : all) {
            if (q.id.equals(id)) return q;
        }
        return null;
    }

    /** 该级全部题号，用于算"已做不同题号数" */
    public Set<String> idsOfLevel(int level) {
        Set<String> out = new LinkedHashSet<>();
        for (Question q : all) {
            if (q.level == level) out.add(q.id);
        }
        return out;
    }

    public List<Question> all() {
        return Collections.unmodifiableList(all);
    }

    private static String readAsset(Context ctx, String name) throws Exception {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
