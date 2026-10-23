/**
 * 结构搜索：为各级真假话题找「结构合格」的断言组合。
 * 合格 = 自洽解 >= 2 且 k 只命中 1 组 且 可删断言 <= maxDroppable 且 闸门数 >= minGates
 * 用法：node bank/probe.js [level]
 */
const fs = require('fs');
const path = require('path');

const CN = ['零', '一', '两', '三', '四', '五', '六', '七', '八', '九', '十'];
const cn = n => CN[n] || String(n);

function val(c, asg, liars) {
  switch (c.kind) {
    case 'honest': return asg[c.target] === undefined ? null : asg[c.target];
    case 'liar': return asg[c.target] === undefined ? null : asg[c.target] === false;
    case 'count': return liars === undefined ? null : liars === c.k;
    case 'cond': {
      const a = val(c.if, asg, liars);
      if (a === false) return true;
      const b = val(c.then, asg, liars);
      if (a === null || b === null) return null;
      return !a || b;
    }
    default: throw new Error('unknown ' + c.kind);
  }
}

function solve(people) {
  const names = people.map(p => p.name);
  const out = [];
  const asg = {};
  const rec = i => {
    if (i === names.length) {
      const liars = names.filter(n => asg[n] === false).length;
      for (const p of people) for (const c of p.claims) {
        if (val(c, asg, liars) !== asg[p.name]) return;
      }
      out.push({ truth: { ...asg }, liars });
      return;
    }
    for (const v of [true, false]) {
      asg[names[i]] = v;
      // 剪枝：只检查已确定的人
      let bad = false;
      for (const p of people) {
        if (asg[p.name] === undefined) continue;
        for (const c of p.claims) {
          const r = val(c, asg, undefined);
          if (r !== null && r !== asg[p.name]) { bad = true; break; }
        }
        if (bad) break;
      }
      if (!bad) rec(i + 1);
    }
    delete asg[names[i]];
  };
  rec(0);
  return out;
}

const keyOf = (names, s) => names.map(n => n + (s.truth[n] ? 'T' : 'F')).join('');

/** 消融分析 */
function analyze(people, k) {
  const names = people.map(p => p.name);
  const base = solve(people).filter(s => s.liars === k);
  if (base.length !== 1) return null;
  const baseAns = keyOf(names, base[0]);
  const flat = [];
  people.forEach(p => p.claims.forEach(c => flat.push({ p: p.name, c })));
  let gate = 0, drop = 0, ess = 0;
  for (const f of flat) {
    const reduced = people.map(p => ({ ...p, claims: p.claims.filter(x => x !== f.c) }));
    const hits = solve(reduced).filter(s => s.liars === k);
    if (hits.length === 0) ess++;
    else if (hits.length === 1 && keyOf(names, hits[0]) === baseAns) drop++;
    else gate++;
  }
  return { gate, drop, ess, total: flat.length, baseAns };
}

/** 机制证据：cond 与 count 是否真的参与推理 */
function mechanism(people, k) {
  const names = people.map(p => p.name);
  const base = solve(people).filter(s => s.liars === k);
  if (base.length !== 1) return null;
  const baseAns = keyOf(names, base[0]);
  const flat = [];
  people.forEach(p => p.claims.forEach(c => flat.push({ p: p.name, c })));
  let condDecisive = 0, countDecisive = 0, countTautology = 0;
  for (const f of flat) {
    if (f.c.kind !== 'cond' && f.c.kind !== 'count') continue;
    if (f.c.kind === 'count' && f.c.k === k) { countTautology++; continue; }
    const reduced = people.map(p => ({ ...p, claims: p.claims.filter(x => x !== f.c) }));
    const hits = solve(reduced).filter(s => s.liars === k);
    if (hits.length !== 1 || keyOf(names, hits[0]) !== baseAns) {
      if (f.c.kind === 'cond') condDecisive++; else countDecisive++;
    }
  }
  return { condDecisive, countDecisive, countTautology };
}

const NAMES = ['A', 'B', 'C', 'D', 'E'];

/** 组合：从 arr 里选 c 个，返回所有索引组合 */
function combos(arr, c) {
  const out = [];
  const rec = (start, acc) => {
    if (acc.length === c) { out.push(acc.slice()); return; }
    for (let i = start; i < arr.length; i++) { acc.push(arr[i]); rec(i + 1, acc); acc.pop(); }
  };
  rec(0, []);
  return out;
}

/** 每人断言数的所有分配方案，总和 <= maxTotal */
function claimCountSplits(n, lo, hi, maxTotal) {
  const out = [];
  const rec = (i, acc) => {
    if (i === n) {
      const t = acc.reduce((a, b) => a + b, 0);
      if (t <= maxTotal) out.push(acc.slice());
      return;
    }
    for (let c = lo; c <= hi; c++) { acc.push(c); rec(i + 1, acc); acc.pop(); }
  };
  rec(0, []);
  return out;
}

/** 生成某级所有可枚举结构 */
function* structures(n, claimRange, kinds, maxCondDepth, maxTotal) {
  const names = NAMES.slice(0, n);

  const claimPool = [];
  const targets = n >= 3 ? names : names.slice(0, n);
  for (const nm of targets) {
    claimPool.push({ kind: 'honest', target: nm });
    claimPool.push({ kind: 'liar', target: nm });
  }
  if (kinds.includes('count')) for (let k = 1; k <= n; k++) claimPool.push({ kind: 'count', k });
  if (maxCondDepth >= 1 && kinds.includes('cond')) {
    for (const a of targets) {
      for (const b of targets) {
        if (a === b) continue;
        claimPool.push({ kind: 'cond', if: { kind: 'honest', target: a }, then: { kind: 'liar', target: b } });
        claimPool.push({ kind: 'cond', if: { kind: 'liar', target: a }, then: { kind: 'honest', target: b } });
      }
    }
  }

  for (const counts of claimCountSplits(n, claimRange[0], claimRange[1], maxTotal)) {
    // 每人的候选组合
    const perPerson = counts.map(c => combos(claimPool, c));
    // 笛卡尔积
    const idx = new Array(n).fill(0);
    const total = perPerson.reduce((a, b) => a * b.length, 1);
    for (let t = 0; t < total; t++) {
      let rem = t;
      const people = names.map((nm, i) => {
        const sel = perPerson[i][rem % perPerson[i].length];
        rem = Math.floor(rem / perPerson[i].length);
        return { name: nm, claims: sel.slice() };
      });
      if (people.some(p => p.claims.length === 0)) continue;
      yield people;
    }
  }
}

const LEVELS = {
  1: { n: 3, claimRange: [1, 2], kinds: ['honest', 'liar', 'count'], condDepth: 0, maxDrop: 1, minGate: 1 },
  2: { n: 4, claimRange: [1, 1], kinds: ['honest', 'liar', 'count'], condDepth: 0, maxDrop: 0, minGate: 1 },
  3: { n: 4, claimRange: [1, 2], kinds: ['honest', 'liar', 'count'], condDepth: 0, maxDrop: 1, minGate: 1 },
  4: { n: 4, claimRange: [1, 2], kinds: ['honest', 'liar', 'count', 'cond'], condDepth: 1, maxDrop: 1, minGate: 1 },
  5: { n: 5, claimRange: [1, 2], kinds: ['honest', 'liar', 'count', 'cond'], condDepth: 1, maxDrop: 1, minGate: 2 }
};

const wantLevel = process.argv[2] ? Number(process.argv[2]) : null;
const DIAG = process.argv.includes("--diag");
let stat={raw:0,sol2:0,kuniq:0,gate:0,drop:0,mech:0};
const limit = process.argv[3] ? Number(process.argv[3]) : 12;

for (const lv of Object.keys(LEVELS).map(Number)) {
  if (wantLevel && lv !== wantLevel) continue;
  const cfg = LEVELS[lv];
  let tried = 0, ok = 0;
  const found = [];
  for (const people of structures(cfg.n, cfg.claimRange, cfg.kinds, cfg.condDepth, 6)) {
    tried++;
    if (tried > 4_000_000) break;

    // 先算每个可行 k
    stat.raw++;
    const sols = solve(people);
    if (sols.length < 2) continue;
    stat.sol2++;
    const kCount = {};
    sols.forEach(s => { kCount[s.liars] = (kCount[s.liars] || 0) + 1; });

    for (const k of Object.keys(kCount).map(Number)) {
      if (kCount[k] !== 1) continue;

      stat.kuniq++;
      const a = analyze(people, k);
      if (!a) continue;
      if (a.gate >= cfg.minGate) stat.gate++;
      if (a.drop <= cfg.maxDrop) stat.drop++;
      if (a.drop > cfg.maxDrop) continue;
      if (a.gate < cfg.minGate) continue;

      const mech = mechanism(people, k);
      if (mech && mech.countDecisive >= 1) stat.mech++;
      if (lv === 3 && (!mech || mech.countDecisive < 1)) continue;
      if (lv === 4 && (!mech || mech.condDecisive < 1)) continue;
      if (lv === 5 && (!mech || mech.condDecisive < 1 || mech.countDecisive < 1)) continue;

      // 结构指纹：断言类型序列（排序后归一）
      const sig = people.map(p => p.claims.map(c => c.kind === 'cond' ? 'cond' : c.kind[0]).sort().join('')).join('|');
      if (found.some(f => f.sig === sig)) continue;

      found.push({ sig, people, k, a, mech, sols });
      ok++;
      break;
    }
    if (ok >= limit) break;
  }

  console.log(`\n=== L${lv} === 尝试 ${tried} 个结构，找到 ${found.length} 个不同结构`);
  if (DIAG) console.log("  诊断:", JSON.stringify(stat));
  for (const f of found) {
    const desc = f.people.map(p => `${p.name}:` + p.claims.map(c => {
      if (c.kind === 'honest') return `h(${c.target})`;
      if (c.kind === 'liar') return `l(${c.target})`;
      if (c.kind === 'count') return `c${c.k}`;
      const sh = x => x.kind === 'honest' ? `h(${x.target})` : `l(${x.target})`;
      return `if ${sh(c.if)} then ${sh(c.then)}`;
    }).join('+')).join('  ');
    console.log(`  k=${f.k} 闸门=${f.a.gate} 可删=${f.a.drop} 必要=${f.a.ess} condDec=${f.mech.condDecisive} countDec=${f.mech.countDecisive}`);
    console.log(`    ${desc}`);
    console.log(`    解: ${f.sols.map(s => NAMES.slice(0, f.people.length).map(n => n + (s.truth[n] ? '真' : '假')).join('') + '/k' + s.liars).join(' ')}`);
  }
}
