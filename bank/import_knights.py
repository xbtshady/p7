#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把 RedaAlami/knights-knaves-puzzles（train-00000-of-00001.parquet）转成 p7 的 bank.json。

做四件事：
1. 解析 input：抽出人名清单和每个人说的话（英文逻辑句）
2. 解析 output：抽出官方解（每人 knight / knave）
3. 把英文句翻成中文，并**用暴力枚举重新验证**：2^n 种真假组合里必须恰好
   一组自洽，且等于官方解。验证不过的题直接丢弃（p7 §4.3 的规矩：宁可少题不要坏题）
4. 按人数与句式复杂度分到 L1–L5，写出 p7 协议的 questions[]

只取 3–5 人的题：人数再多就不是"脑内推演"而是"写程序枚举"了。
"""

import argparse
import collections
import itertools
import json
import os
import re

import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

# ---------------------------------------------------------------- 中文译名表
# 数据集里出现过的 51 个英文人名，逐个给固定中文译名（同一名字在所有题里译法一致）
CN_NAME = {
    "Abigail": "阿比盖尔", "Aiden": "艾登", "Alexander": "亚历山大", "Amelia": "阿米莉亚",
    "Aria": "阿里亚", "Aurora": "奥罗拉", "Ava": "艾娃", "Avery": "埃弗里",
    "Benjamin": "本杰明", "Charlotte": "夏洛特", "Chloe": "克洛伊", "Daniel": "丹尼尔",
    "David": "大卫", "Elizabeth": "伊丽莎白", "Ella": "艾拉", "Emily": "艾米丽",
    "Emma": "艾玛", "Ethan": "伊桑", "Evelyn": "伊芙琳", "Grace": "格蕾丝",
    "Harper": "哈珀", "Henry": "亨利", "Isabella": "伊莎贝拉", "Jack": "杰克",
    "Jackson": "杰克逊", "Jacob": "雅各布", "James": "詹姆斯", "Joseph": "约瑟夫",
    "Liam": "利亚姆", "Lily": "莉莉", "Logan": "洛根", "Lucas": "卢卡斯",
    "Luke": "卢克", "Mason": "梅森", "Matthew": "马修", "Mia": "米娅",
    "Michael": "迈克尔", "Noah": "诺亚", "Oliver": "奥利弗", "Olivia": "奥利维娅",
    "Owen": "欧文", "Penelope": "佩内洛普", "Riley": "莱利", "Samuel": "塞缪尔",
    "Scarlett": "斯嘉丽", "Sebastian": "塞巴斯蒂安", "Sofia": "索菲亚",
    "Sophia": "索菲娅", "Victoria": "维多利亚", "William": "威廉", "Zoey": "佐伊",
}

CN_NUM = {0: "零", 1: "一", 2: "两", 3: "三", 4: "四", 5: "五", 6: "六", 7: "七",
          8: "八", 9: "九", 10: "十", 11: "十一", 12: "十二", 13: "十三", 14: "十四"}

# ---------------------------------------------------------------- 解析 input
HEAD = re.compile(
    r"^A very special island is inhabited only by knights and knaves\. "
    r"Knights always tell the truth, and knaves always lie\. ")
TAIL = re.compile(r" So who is a knight and who is a knave\?$")
MEET = re.compile(r"You meet (\d+) inhabitants: (.+?)\.")

# 引导语：说话人在引号之前
VERB_BEFORE = (r"was heard saying|said|says|stated|asserted|commented|remarked|"
               r"mentioned|expressed|told you|told|noted|claimed|declared|"
               r"replied|added|answered|exclaimed|whispered|mused|observed")
# 说话人在引号之后
VERB_AFTER = r"said|mentioned|declared|claimed|remarked|stated|noted|commented|asserted|replied|added"

PREFIX_PAT = re.compile(
    r"(?:In a statement by|According to|In|As)\s+([A-Z][a-z]+)"
    r"(?:'s words|put it)?\s*[:,]?\s*(?:that\s+)?"
    r"|(?<![\w'])([A-Z][a-z]+)\s+(?:" + VERB_BEFORE + r")\b\s*[:,]?\s*(?:that\s+)?")
SUFFIX_PAT = re.compile(r"^\s*[,]?\s*(?:[A-Z][a-z]+)?\s*[-–]?\s*(?:(?:[A-Z][a-z]+)\s+)?")
QUOTED = re.compile(r'"([^"]+)"|“([^”]+)”')


def split_body(s):
    """去掉固定开头结尾，返回 (names, rest)"""
    s = HEAD.sub("", s)
    s = TAIL.sub("", s)
    m = MEET.search(s)
    if not m:
        return None, None
    raw = re.sub(r",\s*and\s+", ", ", m.group(2))
    names = [x.strip() for x in raw.split(",") if x.strip()]
    rest = s[m.end():].strip()
    return names, rest


# 无引号的从句：X told you that <claim>. / X expressed that <claim>.
THAT_PAT = re.compile(
    r"(?<![\w'])([A-Z][a-z]+)\s+(?:" + VERB_BEFORE + r")\s+that\s+([^.]+?)\s*\.")


def extract_speeches(names, rest):
    """抽出 [(speaker, claim_en), ...]。

    三种形态都要覆盖：
      X said, "..."      —— 引号前是引导语
      "...", X mentioned —— 引号后是引导语
      X told you that …  —— 没有引号
    """
    out = []
    covered = []                      # 已认领的区间，避免重复
    thats = []                        # 无引号从句的结束位置

    # 1) 无引号从句
    for m in THAT_PAT.finditer(rest):
        if m.group(1) not in names:
            return None
        out.append((m.group(1), m.group(2).strip()))
        covered.append((m.start(), m.end()))
        thats.append(m.end())

    # 2) 引号块
    prev_end = 0
    for q in QUOTED.finditer(rest):
        if any(a <= q.start() < b for a, b in covered):
            continue
        # 英文引号用法会把句末逗号包进引号里："...," X said.
        claim = (q.group(1) or q.group(2)).strip().rstrip(".,").strip()
        speaker = None
        # 2a) 先看引号前面：X said, "..." / In a statement by X: "..."
        #     窗口是上一个引号结束到现在，很干净，取最后一个引导语（它才是紧挨着的）。
        #     必须先看前面 —— 句号会被包进引号里（"...". 后面直接跟下一句的说话人）
        # 无引号从句的引导语（William expressed that …）不属于后面的引号，
        # 窗口起点要跳过它
        win = prev_end
        for t in thats:
            if t <= q.start():
                win = max(win, t)
        for mm in PREFIX_PAT.finditer(rest[win:q.start()]):
            nm = mm.group(1) or mm.group(2)
            if nm in names:
                speaker = nm

        # 2b) 前面没有引导语，说话人才在引号后面："...," X mentioned.  /  "..." - X.
        #     认领之后要把这段引导语一并消费掉，否则它会留在下一段的"前文"里，
        #     被误当成下一句话的引导语
        if speaker is None:
            after = rest[q.end():q.end() + 60]
            m = re.match(r"\s*[,]?\s*([A-Z][a-z]+)\s+(?:" + VERB_AFTER + r")\b", after)
            if m and m.group(1) in names:
                speaker = m.group(1)
                prev_end = q.end() + m.end()
            if speaker is None:
                m = re.match(r"\s*[-–]\s*([A-Z][a-z]+)\b", after)
                if m and m.group(1) in names:
                    speaker = m.group(1)
                    prev_end = q.end() + m.end()

        if speaker is None or speaker not in names:
            return None
        out.append((speaker, claim))
        prev_end = max(prev_end, q.end())
    return out or None


# ---------------------------------------------------------------- 解析发言 -> AST
ATOM = re.compile(r"^([A-Z][a-z]+) is (not )?a (knight|knave)$")
SPLIT = re.compile(r"(, | and | or | if and only if )")


def parse_atom(t, names):
    m = ATOM.match(t.strip().rstrip(".").strip())
    if not m or m.group(1) not in names:
        return None
    # True 表示"X 是说真话的人"
    val = (m.group(3) == "knight")
    if m.group(2):
        val = not val
    return (m.group(1), val)


def parse_claim(cl, names):
    """返回 ('atom'|'and'|'or'|'iff'|'impl', [atoms])"""
    cl = cl.strip().rstrip(".").strip()
    if cl.startswith("If "):
        body = cl[3:]
        if " then " not in body:
            return None
        a, b = body.split(" then ", 1)
        A, B = parse_atom(a, names), parse_atom(b, names)
        if A is None or B is None:
            return None
        return ("impl", [A, B])

    parts = SPLIT.split(cl)
    atoms_txt = [p for i, p in enumerate(parts) if i % 2 == 0]
    seps = [p for i, p in enumerate(parts) if i % 2 == 1]
    atoms = [parse_atom(t, names) for t in atoms_txt]
    if any(a is None for a in atoms):
        return None
    if not seps:
        return ("atom", atoms) if len(atoms) == 1 else None

    ops = {s.strip() for s in seps if s.strip() != ","}
    if len(ops) != 1:
        return None
    op = ops.pop()
    if op == "and":
        op = "and"
    elif op == "or":
        op = "or"
    elif op == "if and only if":
        if len(atoms) != 2:
            return None
        op = "iff"
    else:
        return None
    if op == "iff" and len(atoms) != 2:
        return None
    return (op, atoms)


def count_solutions(names, nodes, limit=2):
    """数自洽解，最多数到 limit 个就收工。逐人赋值，句子涉及的人一旦齐了就立刻验。"""
    found = []
    assign = {}

    def ok():
        for s in names:
            if s not in assign:
                continue
            atoms = nodes[s][1]
            if all(n in assign for n, _ in atoms) and ev(nodes[s], assign) != assign[s]:
                return False
        return True

    def dfs(i):
        if len(found) >= limit:
            return
        if i == len(names):
            found.append(dict(assign))
            return
        for v in (True, False):
            assign[names[i]] = v
            if ok():
                dfs(i + 1)
            del assign[names[i]]

    dfs(0)
    return found


def ev(node, assign):
    op, atoms = node
    vals = [(assign[n] if v else not assign[n]) for n, v in atoms]
    if len(vals) == 1:
        return vals[0]
    if op == "and":
        return all(vals)
    if op == "or":
        return any(vals)
    if op == "iff":
        return vals[0] == vals[1]
    if op == "impl":
        return (not vals[0]) or vals[1]
    raise ValueError(op)


# ---------------------------------------------------------------- AST -> 中文
def atom_cn(name, val, cn):
    return cn[name] + ("说真话" if val else "撒谎")


def claim_cn(node, cn):
    op, atoms = node
    if len(atoms) == 1:
        return atom_cn(atoms[0][0], atoms[0][1], cn)
    if op == "impl":
        return "如果" + atom_cn(*atoms[0], cn=cn) + "，那么" + atom_cn(*atoms[1], cn=cn)
    if op == "iff":
        return atom_cn(*atoms[0], cn=cn) + "，当且仅当" + atom_cn(*atoms[1], cn=cn)
    # 三项以上：前面用顿号，最后一项前用连接词
    head = "，".join(atom_cn(*a, cn=cn) for a in atoms[:-1])
    if op == "and":
        return head + "，而且" + atom_cn(*atoms[-1], cn=cn)
    return head + "，或者" + atom_cn(*atoms[-1], cn=cn)


# ---------------------------------------------------------------- 解析 output
SOL = re.compile(r"\*\*Solution:\*\*\s*(.+?)(?:\n|$)", re.S)
SOL_ITEM = re.compile(r"([A-Z][a-z]+) is a (knight|knave)")


def parse_solution(out_text, names):
    m = SOL.search(out_text)
    if not m:
        return None
    assign = {}
    for mm in SOL_ITEM.finditer(m.group(1)):
        if mm.group(1) not in names:
            return None
        assign[mm.group(1)] = (mm.group(2) == "knight")
    if set(assign) != set(names):
        return None
    return assign


# ---------------------------------------------------------------- 主流程
def build(parquet, args):
    df = pd.read_parquet(parquet)
    stat = collections.Counter()
    pool = []          # (level_key, entry)

    for idx, row in df.iterrows():
        stat["total"] += 1
        names, rest = split_body(row["input"])
        if not names or len(names) < args.min_people or len(names) > args.max_people:
            stat["skip_size"] += 1
            continue
        if any(n not in CN_NAME for n in names):
            stat["skip_name"] += 1
            continue

        speeches = extract_speeches(names, rest)
        if not speeches or len(speeches) != len(names):
            stat["skip_parse_speech"] += 1
            continue
        if {s for s, _ in speeches} != set(names):
            stat["skip_parse_speech"] += 1
            continue

        nodes = {}
        for sp, cl in speeches:
            nd = parse_claim(cl, names)
            if nd is None:
                nodes = None
                break
            nodes[sp] = nd
        if nodes is None:
            stat["skip_parse_claim"] += 1
            continue

        official = parse_solution(row["output"], names)
        if official is None:
            stat["skip_solution"] += 1
            continue

        # 必须恰好一组自洽（带剪枝的深度优先，找到第 2 组就停）
        sols = count_solutions(names, nodes, limit=2)
        if len(sols) != 1:
            stat["skip_not_unique"] += 1
            continue
        if sols[0] != official:
            stat["skip_mismatch"] += 1
            continue

        liars = sum(1 for v in official.values() if not v)

        # 分级看人数：人越多越难（L1 三個人，L5 十人以上）
        n = len(names)
        level = 1 if n <= 3 else 2 if n == 4 else 3 if n <= 6 else 4 if n <= 9 else 5
        # 同级内部再按句式复杂度排：iff/impl 比 and/or 绕
        score = sum(2 if nd[0] in ("iff", "impl") else (0 if nd[0] == "atom" else 1)
                    for nd in nodes.values())
        stat["level_%d" % level] += 1
        pool.append((level, score, {
            "names": names,
            "nodes": nodes,
            "official": official,
            "liars": liars,
            "row": int(idx),
        }))
    return pool, stat


def to_question(item, qid, level):
    names = item["names"]
    nodes = item["nodes"]
    official = item["official"]
    liars = item["liars"]
    cn = {n: CN_NAME[n] for n in names}

    people = [{"name": cn[n], "says": claim_cn(nodes[n], cn)} for n in names]

    lines = ["把 %d 个人的真假组合全部代进去（共 %d 种），"
             "只有一组能让「说真话者句句真、撒谎者句句假」同时成立。"
             % (len(names), 2 ** len(names))]
    for n in names:
        t = official[n]
        e = ev(nodes[n], official)
        lines.append("%s是%s的人，他说的「%s」确实是%s——%s，对得上。"
                     % (cn[n], "说真话" if t else "撒谎",
                        claim_cn(nodes[n], cn),
                        "真话" if e else "假话",
                        "说真话者的陈述为真" if t else "撒谎者的陈述为假"))

    summary = "、".join(cn[n] + ("真" if official[n] else "假") for n in names) \
        + "，撒谎 %d 人" % liars

    return {
        "id": qid,
        "type": "truth",
        "level": level,
        "mode": "answer",
        "prototype": "knights-knaves",
        "prompt": {
            "people": people,
            # 数据集的题解本来就唯一，不需要额外约束 —— 写中性提示，不泄露答案
            # App 题面用固定经典开场+设问；constraint 仅作数据备注
            "constraint": "骑士总说真话，无赖总说假话；判定每人身份，唯一解",
        },
        "answer": {
            "truth": {cn[n]: official[n] for n in names},
            "liars": liars,
        },
        "solution": {"lines": lines, "summary": summary},
        "_src": "knights-knaves-dataset#%d" % item["row"],
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--parquet", default=os.path.join(ROOT, "train-00000-of-00001.parquet"))
    ap.add_argument("--per-level", type=int, default=0, help="0 表示每级全取")
    ap.add_argument("--min-people", type=int, default=3)
    ap.add_argument("--max-people", type=int, default=14)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--out", default=os.path.join(HERE, "knights_src.json"))
    args = ap.parse_args()

    pool, stat = build(args.parquet, args)
    print("统计：")
    for k in sorted(stat):
        print("  %-20s %d" % (k, stat[k]))

    import random
    rnd = random.Random(args.seed)
    rnd.shuffle(pool)
    pool.sort(key=lambda x: (x[0], x[1]))      # 先分级，级内按复杂度升序

    questions = []
    seq = collections.Counter()
    for level, score, item in pool:
        seq[level] += 1
        questions.append(to_question(item, "L%d-T1-%04d" % (level, seq[level]), level))

    for level in range(1, 6):
        ns = [len(q["prompt"]["people"]) for q in questions if q["level"] == level]
        print("L%d 取 %d 题（人数 %d–%d）" % (level, len(ns), min(ns), max(ns)) if ns
              else "L%d 取 0 题" % level)

    bank = {"schemaVersion": 1, "bankVersion": 5, "questions": questions}
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(bank, f, ensure_ascii=False, indent=1)
    print("写出 %s，共 %d 题" % (args.out, len(questions)))


if __name__ == "__main__":
    main()
