#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
摸清 train-00000-of-00001.parquet 的底：构成、句式、解析覆盖率、
数据集本身的质量（是否有多解 / 官方解是否自洽），以及适配 p7 协议的卡点。
只统计不写题。
"""
import collections
import itertools
import os
import re
import sys

import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import import_knights as K  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
pq = os.path.join(ROOT, "train-00000-of-00001.parquet")


def main():
    lo = int(next((a.split("=")[1] for a in sys.argv if a.startswith("--min=")), 3))
    hi = int(next((a.split("=")[1] for a in sys.argv if a.startswith("--max=")), 14))
    df = pd.read_parquet(pq)
    df = df[df["input"].map(
        lambda s: lo <= len(K.split_body(s)[0] or []) <= hi)]
    n_total = len(df)
    print("本次范围：%d–%d 人" % (lo, hi))

    people_n = collections.Counter()     # 人数分布
    shape = collections.Counter()        # 句式：连接词 + 原子个数
    liars_hist = collections.Counter()   # 撒谎人数分布
    fail = collections.Counter()         # 解析失败环节
    uniq = collections.Counter()         # 解的个数
    mismatch = 0                         # 官方解与枚举解不一致
    official_bad = 0                     # 官方解本身不自洽
    taut = collections.Counter()         # 恒真 / 恒假句
    parsed = 0

    for _, row in df.iterrows():
        names, rest = K.split_body(row["input"])
        if not names:
            fail["开头/人名解析失败"] += 1
            continue
        people_n[len(names)] += 1

        speeches = K.extract_speeches(names, rest)
        if not speeches or len(speeches) != len(names) \
                or {s for s, _ in speeches} != set(names):
            fail["发言切分失败"] += 1
            continue

        nodes = {}
        for sp, cl in speeches:
            nd = K.parse_claim(cl, names)
            if nd is None:
                nodes = None
                break
            nodes[sp] = nd
        if nodes is None:
            fail["发言句式解析失败"] += 1
            continue

        official = K.parse_solution(row["output"], names)
        if official is None:
            fail["官方解解析失败"] += 1
            continue

        parsed += 1
        liars_hist[sum(1 for v in official.values() if not v)] += 1

        for s in names:
            op, atoms = nodes[s]
            shape["%s/%d" % (op, len(atoms))] += 1
            inv = sorted({n for n, _ in atoms})
            vals = {K.ev(nodes[s], dict(zip(inv, b)))
                    for b in itertools.product([True, False], repeat=len(inv))}
            if len(vals) == 1:
                taut["恒真" if vals.pop() else "恒假"] += 1

        sols = K.count_solutions(names, nodes, limit=3)
        uniq[min(len(sols), 3)] += 1
        if len(sols) == 1:
            if sols[0] != official:
                mismatch += 1
        elif official not in sols:
            official_bad += 1

    print("=" * 62)
    print("一、规模")
    print("  总题数 %d" % n_total)
    print("  人数分布：" + ", ".join("%d人×%d" % (k, people_n[k])
                                    for k in sorted(people_n)))

    print("=" * 62)
    print("二、句式（连接词 / 这句话里提到几个人）")
    for k, v in shape.most_common():
        print("  %-10s %d" % (k, v))

    print("=" * 62)
    print("三、我的解析器覆盖率")
    print("  完整解析 %d / %d（%.1f%%）" % (parsed, n_total, 100.0 * parsed / n_total))
    for k, v in fail.most_common():
        print("  卡在「%s」 %d 条" % (k, v))

    print("=" * 62)
    print("四、数据集质量（只对解析成功的 %d 条做的检查）" % parsed)
    print("  解的个数：" + ", ".join(
        "%s解×%d" % (("唯一" if k == 1 else ("多(≥3)" if k >= 3 else "%d" % k)), v)
        for k, v in sorted(uniq.items())))
    print("  官方解不自洽（题目本身就矛盾） %d 条" % official_bad)
    print("  官方解只是多个解之一 %d 条" % mismatch)
    print("  废话句（恒真/恒假）：" + ", ".join("%s×%d" % (k, v) for k, v in taut.items()))
    print("  撒谎人数分布：" + ", ".join("%d人×%d" % (k, liars_hist[k])
                                        for k in sorted(liars_hist)))

    print("=" * 62)
    print("五、p7 协议适配卡点")
    print("  · Question.java 要求 prompt.constraint 非空 → 数据集没有约束句，需要补")
    print("  · 判定要求 answer.liars 与 truth 一致 → 数据集的 output 能直接给出，OK")
    print("  · solution.lines 必填 → 数据集 output 带推理链，可转成中文解析")
    print("  · 最多 14 人 → 答题页要 14 行真假选择 + 撒谎人数输入框，靠滚动")


if __name__ == "__main__":
    main()
