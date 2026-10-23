package com.xbtshady.p7.data;

import android.content.Context;
import android.util.Log;

import com.xbtshady.p7.model.Progress;
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
 * 读 assets/bank.json。产品池仅 truth + answer（docs/2.0）。
 */
public final class BankRepo {

    private static final String TAG = "p7";
    private static final String ASSET = "bank.json";
    private static final int SUPPORTED_BANK_SCHEMA = 1;

    public enum Status { OK, EMPTY, MISMATCH, ERROR }

    private Status status = Status.ERROR;
    private int bankVersion;
    private final List<Question> all = new ArrayList<>();
    private final List<String> broken = new ArrayList<>();

    public Status status() {
        return status;
    }

    public int bankVersion() {
        return bankVersion;
    }

    public void load(Context ctx) {
        all.clear();
        broken.clear();
        try {
            String json = readAsset(ctx, ASSET);
            JSONObject root = new JSONObject(json);

            int schema = root.optInt("schemaVersion", -1);
            if (schema > SUPPORTED_BANK_SCHEMA) {
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

    public List<Question> all() {
        return Collections.unmodifiableList(all);
    }

    public Question byId(String id) {
        for (Question q : all) {
            if (q.id.equals(id)) return q;
        }
        return null;
    }

    public Set<String> idsOfLevel(int level) {
        Set<String> out = new LinkedHashSet<>();
        for (Question q : all) {
            if (q.level == level && q.inProductPool()) out.add(q.id);
        }
        return out;
    }

    public Set<String> allProductIds() {
        Set<String> out = new LinkedHashSet<>();
        for (Question q : all) {
            if (q.inProductPool()) out.add(q.id);
        }
        return out;
    }

    public int countOfLevel(int level) {
        return idsOfLevel(level).size();
    }

    /**
     * @param level 0=全部，1–5
     * @param statusFilter Progress.STATUS_*
     */
    public List<Question> matching(int level, String statusFilter, Progress progress) {
        List<Question> out = new ArrayList<>();
        for (Question q : all) {
            if (!q.inProductPool()) continue;
            if (level >= 1 && q.level != level) continue;
            if (progress != null && !progress.matchesStatusFilter(q.id, statusFilter)) continue;
            out.add(q);
        }
        return out;
    }

    public List<Question> page(int level, String statusFilter, Progress progress, int offset, int limit) {
        List<Question> pool = matching(level, statusFilter, progress);
        if (offset < 0) offset = 0;
        if (offset >= pool.size()) return Collections.emptyList();
        int end = Math.min(offset + limit, pool.size());
        return new ArrayList<>(pool.subList(offset, end));
    }

    public int countMatching(int level, String statusFilter, Progress progress) {
        return matching(level, statusFilter, progress).size();
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
