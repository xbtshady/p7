package com.xbtshady.p7.engine;

import com.xbtshady.p7.model.Question;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 架构文档 §7 抽题。
 *
 * <pre>
 * 1. pool = 该 level 全部 mode=answer 的题
 * 2. pool.size &gt; 1 且 lastQid 在池里 → 去掉 lastQid
 * 3. 等概率随机
 * 4. 池为空（配置错）→ 返回 null（EMPTY）
 * </pre>
 *
 * 没有解锁、没有已答排除、没有难度偏向。
 */
public final class Picker {

    private final Random rnd = new Random();

    public Question pick(List<Question> pool, String lastQid) {
        if (pool == null || pool.isEmpty()) return null;

        List<Question> cands = new ArrayList<>();
        for (Question q : pool) {
            // 池里只有 1 题时不排除，否则永远抽不到
            if (pool.size() > 1 && lastQid != null && q.id.equals(lastQid)) continue;
            cands.add(q);
        }
        if (cands.isEmpty()) return null;
        return cands.get(rnd.nextInt(cands.size()));
    }
}
