/**
 * 题库验证器：独立复算 + 交叉核对 + 重复检查 + 难度校准
 *
 * 独立性要求：本文件 **不得 require build.js**，也不得复用它的任何函数。
 *   truth : build 用 2^N 全表 + `if?a:b` 求条件句；本文件用按人剪枝 + `!a||b` 等价式
 *   ledger: build 用逐笔状态机；本文件用闭合公式求终值 + 从零重算前缀和验中间态
 *   题面  : build 用自己的模板渲染；本文件独立实现一套渲染器，两边必须逐字相同
 *
 * 用法：
 *   node bank/verify.js            对 src.json 全量体检，有 bank.json 时做交叉核对
 *   node bank/verify.js --write-meta   额外写出 bank/meta.json
 */
const fs = require('fs');
const path = require('path');

const SRC = path.join(__dirname, 'src.json');
const BANK = path.join(__dirname, 'bank.json');
const BLUEPRINT = path.join(__dirname, 'blueprint.json');
const META_OUT = path.join(__dirname, 'meta.json');
const REPORT = path.join(__dirname, 'report.md');

const CN = ['零', '一', '两', '三', '四', '五', '六', '七', '八', '九', '十'];
const cn = n => CN[n] || String(n);

/* ============ 真假话：独立求解（剪枝 + 蕴含等价式） ============ */

/** 三值求值：null 表示当前赋值下还不能判定 */
function val(c, asg, liars) {
  switch (c.kind) {
    case 'honest':
      return asg[c.target] === undefined ? null : asg[c.target];
    case 'liar':
      return asg[c.target] === undefined ? null : asg[c.target] === false;
    case 'count':
      return liars === undefined ? null : liars === c.k;
    case 'cond': {
      const a = val(c.if, asg, liars);
      if (a === false) return true;          // 前件假，蕴含式为真，无需再看后件
      const b = val(c.then, asg, liars);
      if (a === null || b === null) return null;
      return !a || b;                        // ¬if ∨ then
    }
    default:
      throw new Error('未知断言类型: ' + c.kind);
  }
}

/** 剪枝判定：只查当前已可判定的断言 */
function violated(people, asg) {
  for (const p of people) {
    if (asg[p.name] === undefined) continue;
    for (const c of p.claims) {
      const r = val(c, asg, undefined);
      if (r !== null && r !== asg[p.name]) return true;
    }
  }
  return false;
}

/** 全量判定：所有赋值确定后，含 count 一并算 */
function fullyConsistent(people, asg) {
  const names = people.map(p => p.name);
  const liars = names.filter(nm => asg[nm] === false).length;
  if (!Number.isInteger(liars)) return null;
  for (const p of people) {
    for (const c of p.claims) {
      const r = val(c, asg, liars);
      if (r === null) return null;
      if (r !== asg[p.name]) return null;
    }
  }
  return liars;
}

/** 求所有自洽解 */
function solveAll(people) {
  const names = people.map(p => p.name);
  const out = [];
  const asg = {};
  const rec = i => {
    if (i === names.length) {
      const liars = fullyConsistent(people, asg);
      if (liars !== null) {
        out.push({ truth: { ...asg }, liars });
      }
      return;
    }
    const nm = names[i];
    for (const v of [true, false]) {
      asg[nm] = v;
      if (!violated(people, asg)) rec(i + 1);
    }
    delete asg[nm];
  };
  rec(0);
  return out;
}

/** 断言最大嵌套深度 */
function condDepth(c) {
  if (c.kind !== 'cond') return 0;
  return 1 + Math.max(condDepth(c.if), condDepth(c.then));
}

/* ============ 真假话：独立渲染器（与 build.js 各写一套） ============ */

function renderClaim(c) {
  switch (c.kind) {
    case 'honest':
      return c.target + ' 说真话';
    case 'liar':
      return c.target + ' 在撒谎';
    case 'count':
      return '我们中恰好' + cn(c.k) + '人撒谎';
    case 'cond':
      return '如果' + renderClaim(c.if) + '，那么' + renderClaim(c.then);
    default:
      throw new Error('未知断言类型: ' + c.kind);
  }
}

/* ============ 账本：独立核算（闭合公式 + 重算前缀和） ============ */

/** 闭合公式：直接把每笔的影响汇总，不走逐笔状态机 */
function ledgerClosed(q) {
  let buyQty = 0, buyCost = 0, sellQty = 0, sellRev = 0;
  let swapGive = 0, swapTake = 0, swapCashOut = 0;
  for (const t of q.tx) {
    if (t.op === 'buy') { buyQty += t.qty; buyCost += t.qty * t.price; }
    else if (t.op === 'sell') { sellQty += t.qty; sellRev += t.qty * t.price; }
    else if (t.op === 'swap') { swapGive += t.give; swapTake += t.take; swapCashOut += t.cash; }
    else throw new Error('未知交易类型: ' + t.op);
  }
  const cashEnd = q.open.cash - buyCost + sellRev - swapCashOut;
  const holdEnd = q.open.hold + buyQty - sellQty + swapTake - swapGive;
  const totalOpen = q.open.cash + q.open.hold * q.open.price;
  const totalEnd = cashEnd + holdEnd * q.closePrice;
  const net = totalEnd - totalOpen;
  return {
    cashEnd, holdEnd, totalOpen, totalEnd, net,
    value: q.question === 'total' ? totalEnd : net
  };
}

/** 每个时点的状态：每次都从期初重新累加，不复用累加器 */
function ledgerStates(q) {
  const deltas = q.tx.map(t => {
    if (t.op === 'buy') return { dc: -(t.qty * t.price), dh: t.qty };
    if (t.op === 'sell') return { dc: t.qty * t.price, dh: -t.qty };
    if (t.op === 'swap') return { dc: -t.cash, dh: t.take - t.give };
    throw new Error('未知交易类型: ' + t.op);
  });
  const states = [{ cash: q.open.cash, hold: q.open.hold }];
  for (let i = 0; i < deltas.length; i++) {
    let dc = 0, dh = 0;
    for (let j = 0; j <= i; j++) { dc += deltas[j].dc; dh += deltas[j].dh; }
    states.push({ cash: q.open.cash + dc, hold: q.open.hold + dh });
  }
  return states;
}

/** 账本难度锚 */
function ledgerAnchor(q) {
  const ops = new Set(q.tx.map(t => t.op));
  const buys = q.tx.filter(t => t.op === 'buy').length;
  const sells = q.tx.filter(t => t.op === 'sell').length;
  const hasSwap = ops.has('swap');
  const lastPrice = q.tx.length ? (q.tx[q.tx.length - 1].price ?? q.closePrice) : q.open.price;
  let tracked = 1;                                   // cash
  if (sells > 0 || hasSwap) tracked += 1;            // hold
  if (buys >= 2) tracked += 1;                       // 均价
  if (q.closePrice !== lastPrice) tracked += 1;      // 市值差
  return { tx: q.tx.length, tracked, ops: ops.size, swapUsed: hasSwap };
}

/* ============ 难度锚 / 级别区间校验 ============ */

function truthAnchor(q) {
  const kinds = new Set();
  let maxDepth = 0, count = 0;
  const walk = c => {
    kinds.add(c.kind);
    if (c.kind === 'count') count++;
    if (c.kind === 'cond') { walk(c.if); walk(c.then); }
  };
  for (const p of q.people) for (const c of p.claims) { walk(c); maxDepth = Math.max(maxDepth, condDepth(c)); count++; }
  return {
    n: q.people.length,
    condDepth: maxDepth,
    assertCount: count,
    kinds: [...kinds],
    countUsed: kinds.has('count'),
    swapUsed: undefined
  };
}

function inRange(v, range) {
  if (!Array.isArray(range)) return true;
  return v >= range[0] && v <= range[1];
}

function checkAnchor(q, anchor, spec) {
  const bad = [];
  const a = spec.anchor;
  if (q.type === 'truth') {
    if (!inRange(anchor.n, a.n)) bad.push(`人数 ${anchor.n} 不在 [${a.n}]`);
    if (!inRange(anchor.condDepth, a.condDepth)) bad.push(`条件句深度 ${anchor.condDepth} 不在 [${a.condDepth}]`);
    if (!inRange(anchor.assertCount, a.assertCount)) bad.push(`断言数 ${anchor.assertCount} 不在 [${a.assertCount}]`);
    for (const must of spec.mustUse || []) {
      if (must === 'cond' && !anchor.kinds.includes('cond')) bad.push('缺少条件句断言（该级必须）');
      if (must === 'count' && !anchor.countUsed) bad.push('缺少 count 断言（该级必须）');
    }
  } else {
    if (!inRange(anchor.tx, a.tx)) bad.push(`交易笔数 ${anchor.tx} 不在 [${a.tx}]`);
    if (!inRange(anchor.tracked, a.tracked)) bad.push(`并行量 ${anchor.tracked} 不在 [${a.tracked}]`);
    if (!inRange(anchor.ops, a.ops)) bad.push(`交易种类 ${anchor.ops} 不在 [${a.ops}]`);
    if (a.swapUsed === false && anchor.swapUsed) bad.push('该级不应出现置换');
    if (a.swapUsed === true && !anchor.swapUsed) bad.push('该级必须出现置换');
    for (const must of spec.mustUse || []) {
      if (must === 'swap' && !anchor.swapUsed) bad.push('缺少置换操作（该级必须）');
    }
  }
  return bad;
}

/* ============ 指纹 ============ */

function fingerprint(q) {
  if (q.type === 'truth') {
    const sig = q.people.map(p => p.claims.map(shortClaim).join('+')).join(',');
    const hasCond = JSON.stringify(q).includes('"cond"');
    return `truth|n=${q.people.length}|k=${q.intendedLiars}|cond=${hasCond ? 1 : 0}|${sig}`;
  }
  const opSig = q.tx.map(t => t.op + (t.qty ?? `${t.give}>${t.take}`)).join(',');
  return `ledger|${q.prototype}|${q.question}|open=${q.open.hold}|${opSig}`;
}

function shortClaim(c) {
  if (c.kind === 'honest') return 'h';
  if (c.kind === 'liar') return 'l';
  if (c.kind === 'count') return 'c' + c.k;
  return 'if(' + shortClaim(c.if) + '>' + shortClaim(c.then) + ')';
}

/* ============ 关键断言 / 典型错误（代替人工标注） ============ */

/**
 * 逐条断言做消融实验，分三类：
 *   闸门 gate     — 去掉后「恰好 k 人撒谎」会同时命中多组，唯一性靠它
 *   必要 essential — 去掉后无解，这句不能少
 *   冗余 droppable — 去掉后答案不变，这句是废话，玩家白读
 *
 * 可删断言是题面质量信号：有它没它一个样，说明这题偏松，或者题面可以更短。
 */
function analyzeClaims(people, k) {
  const base = solveAll(people);
  const baseHits = base.filter(s => s.liars === k);
  const keyOf = s => people.map(p => p.name + (s.truth[p.name] ? 'T' : 'F')).join('');
  const baseAns = baseHits.length === 1 ? keyOf(baseHits[0]) : null;

  const flat = [];
  people.forEach(p => p.claims.forEach(c => flat.push({ name: p.name, claim: c })));

  const gates = [], droppable = [], essential = [];
  for (const f of flat) {
    const reduced = people.map(p => ({
      ...p,
      claims: p.claims.filter(c => c !== f.claim)
    }));
    const hits = solveAll(reduced).filter(s => s.liars === k);
    const label = `${f.name} 说的「${renderClaim(f.claim)}」`;
    if (hits.length === 0) essential.push(label);
    else if (hits.length === 1 && baseAns && keyOf(hits[0]) === baseAns) droppable.push(label);
    else if (hits.length > 1) gates.push({ label, hits: hits.length });
    else gates.push({ label, hits: hits.length });   // 命中 1 组但答案变了：也是关键
  }
  return { gates, droppable, essential, baseAns };
}

/** 兼容旧调用：取第一道闸门 */
function findCriticalClaim(people, k) {
  const a = analyzeClaims(people, k);
  if (!a.gates.length) {
    return {
      critical: null,
      hit: `无单点闸门："恰好${cn(k)}人撒谎"由人数约束本身收敛，不依赖某一句`
    };
  }
  const g = a.gates[0];
  return {
    critical: g.label,
    hit: `去掉它，"恰好${cn(k)}人撒谎"会同时命中 ${g.hits} 组候选，必须靠这句二选一`
  };
}

/**
 * 账本典型错误：用三种常见错算各跑一遍，取离标准答案最近的那个非等值结果。
 * 这是「最诱人的错误」，比人拍脑袋写的准。
 */
function ledgerTraps(q) {
  const truth = ledgerClosed(q);
  const states = ledgerStates(q);
  const last = states[states.length - 1];

  let cashFlow = 0;
  for (const t of q.tx) {
    if (t.op === 'buy') cashFlow -= t.qty * t.price;
    else if (t.op === 'sell') cashFlow += t.qty * t.price;
    else cashFlow -= t.cash;
  }

  const candidates = [
    { name: '漏算持仓市值（只报期末现金）', value: truth.cashEnd, why: '只跟住了现金，忘了手里的货也值钱' },
    { name: '把现金流当盈亏', value: cashFlow, why: '把买卖收支差额直接当成赚了多少钱' },
    { name: '用期初市价给期末持仓估值', value: last.cash + last.hold * q.open.price, why: '期末市价变了，却沿用期初的价格' },
    { name: '用末笔成交价给期末持仓估值', value: last.cash + last.hold * (q.tx.length ? (q.tx[q.tx.length - 1].price ?? q.closePrice) : q.open.price), why: '把成交价当市价' }
  ];

  const usable = candidates
    .filter(c => c.value !== truth.value)
    .map(c => ({ ...c, diff: Math.abs(c.value - truth.value) }))
    .sort((x, y) => x.diff - y.diff);

  return usable.slice(0, 2);
}

/* ============ 单题检查 ============ */

function checkTruth(q, bp) {
  const errors = [];
  const warns = [];
  const info = {};

  const names = q.people.map(p => p.name);
  if (new Set(names).size !== names.length) errors.push('人名重复');
  if (names.length < 3) errors.push('人数不足 3');

  let sols;
  try {
    sols = solveAll(q.people);
  } catch (e) {
    errors.push('求解失败: ' + e.message);
    return { errors, warns, info };
  }
  info.survivors = sols.length;

  if (sols.length < 2) {
    errors.push(`自洽解只有 ${sols.length} 组，不需要排除候选`);
  }
  const hits = sols.filter(s => s.liars === q.intendedLiars);
  if (hits.length !== 1) {
    errors.push(`k=${q.intendedLiars} 命中 ${hits.length} 组（全部自洽解：${sols.map(s => names.map(n => n + (s.truth[n] ? '真' : '假')).join('') + '/' + s.liars).join(' ')}）`);
  } else {
    info.answer = hits[0];
  }

  const anchor = truthAnchor(q);
  info.anchor = anchor;
  const spec = bp.levels.find(l => l.level === q.level);
  if (spec) {
    const bad = checkAnchor(q, anchor, spec.truth);
    if (bad.length) warns.push(...bad.map(b => '难度锚: ' + b));
  } else {
    errors.push('蓝图中无此级别');
  }

  const claimAnalysis = analyzeClaims(q.people, q.intendedLiars);
  info.claims = claimAnalysis;
  info.critical = claimAnalysis.gates.length
    ? { critical: claimAnalysis.gates[0].label, hit: claimAnalysis.gates[0].hits }
    : { critical: null, hit: '无单点闸门' };
  if (claimAnalysis.droppable.length) {
    warns.push(`可删断言 ${claimAnalysis.droppable.length} 条（去掉后答案不变）：${claimAnalysis.droppable.join('、')}`);
  }
  if (!claimAnalysis.gates.length) {
    warns.push('无单点闸门：唯一性只由人数约束提供，结构偏平');
  }

  return { errors, warns, info };
}

function checkLedger(q, bp) {
  const errors = [];
  const warns = [];
  const info = {};

  let closed, states;
  try {
    closed = ledgerClosed(q);
    states = ledgerStates(q);
  } catch (e) {
    errors.push('核算失败: ' + e.message);
    return { errors, warns, info };
  }
  info.closed = closed;

  for (let i = 0; i < states.length; i++) {
    if (states[i].hold < 0) errors.push(`第 ${i} 步持仓为负 (${states[i].hold})`);
    if (states[i].cash < 0) errors.push(`第 ${i} 步现金为负 (${states[i].cash})`);
  }
  if (closed.holdEnd < 0) errors.push('期末持仓为负');

  if (!['net', 'total'].includes(q.question)) errors.push('question 非法');
  if (!Number.isInteger(closed.value)) errors.push('答案不是整数');

  // 单笔交易的题不构成心智模拟，属于示例
  if (q.tx.length < 2) errors.push(`只有 ${q.tx.length} 笔交易，属于示例而非正式题`);

  const anchor = ledgerAnchor(q);
  info.anchor = anchor;
  const spec = bp.levels.find(l => l.level === q.level);
  if (spec) {
    const bad = checkAnchor(q, anchor, spec.ledger);
    if (bad.length) warns.push(...bad.map(b => '难度锚: ' + b));
  } else {
    errors.push('蓝图中无此级别');
  }

  info.traps = ledgerTraps(q);
  return { errors, warns, info };
}

/* ============ 与 bank.json 交叉核对 ============ */

function crossCheck(q, built, info) {
  const errs = [];
  if (!built) return ['bank.json 中缺少该题'];

  if (q.type === 'truth') {
    const expect = info.answer;
    if (!expect) return ['无唯一解，跳过答案核对'];
    for (const nm of Object.keys(expect.truth)) {
      if (built.answer.truth[nm] !== expect.truth[nm]) {
        errs.push(`答案不符：${nm} 应为 ${expect.truth[nm] ? '真' : '假'}，bank.json 记为 ${built.answer.truth[nm] ? '真' : '假'}`);
      }
    }
    if (built.answer.liars !== expect.liars) {
      errs.push(`撒谎人数不符：应为 ${expect.liars}，bank.json 记为 ${built.answer.liars}`);
    }
    // 题面逐字比对：两边渲染器各自实现，必须一致
    const expectPeople = q.people.map(p => ({ name: p.name, says: p.claims.map(renderClaim).join('；') }));
    const gotPeople = built.prompt.people || [];
    if (expectPeople.length !== gotPeople.length) {
      errs.push('题面人数与源数据不符');
    } else {
      expectPeople.forEach((p, i) => {
        if (p.name !== gotPeople[i].name) errs.push(`题面第 ${i + 1} 人姓名不符`);
        if (p.says !== gotPeople[i].says) errs.push(`题面第 ${i + 1} 人陈述不符：应为「${p.says}」，实为「${gotPeople[i].says}」`);
      });
    }
    const expectConstraint = `恰好${cn(q.intendedLiars)}人撒谎`;
    if (built.prompt.constraint !== expectConstraint) {
      errs.push(`约束文案不符：应为「${expectConstraint}」，实为「${built.prompt.constraint}」`);
    }
  } else {
    if (built.answer.value !== info.closed.value) {
      errs.push(`答案不符：应为 ${info.closed.value}，bank.json 记为 ${built.answer.value}`);
    }
    // 账本叙事独立重渲染
    const tail = t => (t.note ? `（${t.note}）` : '');
    const lines = [];
    lines.push(q.open.hold > 0
      ? `期初有现金 ${q.open.cash} 元，手里 ${q.open.hold} 件货，当时市价 ${q.open.price} 元。`
      : `期初有现金 ${q.open.cash} 元，手里没有货，当时市价 ${q.open.price} 元。`);
    for (const t of q.tx) {
      if (t.op === 'buy') lines.push(`花 ${t.qty * t.price} 元买入 ${t.qty} 件${tail(t)}。`);
      else if (t.op === 'sell') lines.push(`以 ${t.qty * t.price} 元卖出 ${t.qty} 件${tail(t)}。`);
      else lines.push(`用 ${t.give} 件货加 ${t.cash} 元，换来 ${t.take} 件${tail(t)}。`);
    }
    lines.push(`现市价 ${q.closePrice} 元。`);
    const got = built.prompt.narrative || [];
    if (lines.length !== got.length) {
      errs.push(`叙事行数不符：应为 ${lines.length}，实为 ${got.length}`);
    } else {
      lines.forEach((l, i) => {
        if (l !== got[i]) errs.push(`叙事第 ${i + 1} 行不符：应为「${l}」，实为「${got[i]}」`);
      });
    }
    if (built.prompt.question !== q.question) errs.push('question 与源数据不符');
  }
  return errs;
}

/* ============ 主流程 ============ */

const src = JSON.parse(fs.readFileSync(SRC, 'utf8'));
const bp = JSON.parse(fs.readFileSync(BLUEPRINT, 'utf8'));
const bank = fs.existsSync(BANK) ? JSON.parse(fs.readFileSync(BANK, 'utf8')) : null;
const builtById = new Map((bank ? bank.questions : []).map(q => [q.id, q]));

const rows = [];
const idSeen = new Map();
const fpMap = new Map();

for (const q of src.questions) {
  const { errors, warns, info } = q.type === 'truth' ? checkTruth(q, bp) : checkLedger(q, bp);

  if (idSeen.has(q.id)) errors.push(`id 与 ${idSeen.get(q.id)} 重复`);
  else idSeen.set(q.id, q.id);

  const fp = fingerprint(q);
  if (fpMap.has(fp)) warns.push(`与 ${fpMap.get(fp)} 结构指纹相同`);
  else fpMap.set(fp, q.id);

  if (bank) errors.push(...crossCheck(q, builtById.get(q.id), info));
  else if (!['truth', 'ledger'].includes(q.type)) errors.push('未知题型 ' + q.type);

  rows.push({ q, errors, warns, info, fp });
}

/* ---- 覆盖率 ---- */

const cover = {};
for (const r of rows) {
  const key = 'L' + r.q.level;
  cover[key] = cover[key] || { truth: 0, ledger: 0, ok: 0, bad: 0, plainOnly: 0 };
  cover[key][r.q.type] += 1;
  if (r.errors.length) cover[key].bad += 1; else cover[key].ok += 1;
  if (r.q.type === 'truth') {
    const kinds = r.info.anchor ? r.info.anchor.kinds.filter(k => k !== 'count') : [];
    const onlyPlain = kinds.length > 0 && kinds.every(k => k === 'honest' || k === 'liar');
    const usesCount = r.info.anchor && r.info.anchor.countUsed;
    const usesCond = kinds.includes('cond');
    if (onlyPlain && !usesCount && !usesCond) cover[key].plainOnly += 1;
  }
}

/* ---- 输出报告 ---- */

const failed = rows.filter(r => r.errors.length);
const warned = rows.filter(r => !r.errors.length && r.warns.length);

const md = [];
md.push('# 题库验证报告');
md.push('');
md.push(`生成于 ${new Date().toISOString().slice(0, 19).replace('T', ' ')}`);
md.push('');
md.push('## 汇总');
md.push('');
md.push(`- 源题总数：${rows.length}`);
md.push(`- **通过全部检查：${rows.length - failed.length}**`);
md.push(`- 未通过：${failed.length}`);
md.push(`- 有警告（不影响入库，但需校准）：${warned.length}`);
md.push(`- bank.json 交叉核对：${bank ? '已执行' : '未执行（bank.json 不存在）'}`);
md.push('');
md.push('## 分级覆盖');
md.push('');
md.push('| 级 | 真假话 | 账本 | 通过 | 未通过 | 纯 honest/liar |');
md.push('| --- | --- | --- | --- | --- | --- |');
for (const k of Object.keys(cover).sort()) {
  const c = cover[k];
  const ratio = c.truth ? Math.round((c.plainOnly / c.truth) * 100) : 0;
  md.push(`| ${k} | ${c.truth} | ${c.ledger} | ${c.ok} | ${c.bad} | ${c.plainOnly} 道（${ratio}%） |`);
}
md.push('');
const truthRows = rows.filter(r => r.q.type === 'truth' && r.info.claims);
const withGate = truthRows.filter(r => r.info.claims.gates.length > 0);
const withDroppable = truthRows.filter(r => r.info.claims.droppable.length > 0);

md.push('## 结构强度（仅真假话）');
md.push('');
md.push('对每条断言做消融实验：单独去掉它，看答案还在不在。');
md.push('');
md.push('- **闸门断言**：去掉后「恰好 k 人撒谎」会同时命中多组，唯一性靠它 —— 这才是玩家必须验到的那一步');
md.push('- **必要断言**：去掉后无解，这句不能少');
md.push('- **可删断言**：去掉后答案不变，玩家读它不产生任何推理收益');
md.push('');
md.push(`- 有闸门断言的题：${withGate.length} / ${truthRows.length}`);
md.push(`- 含可删断言的题：${withDroppable.length} / ${truthRows.length}`);
md.push(`- 断言全部可删的题：${truthRows.filter(r => r.info.claims.droppable.length === r.info.claims.droppable.length + r.info.claims.essential.length).length} / ${truthRows.length}`);
md.push('');
if (withDroppable.length) {
  md.push('| 题号 | 断言数 | 闸门 | 必要 | 可删 | 可删的是哪几句 |');
  md.push('| --- | --- | --- | --- | --- | --- |');
  for (const r of withDroppable) {
    const c = r.info.claims;
    md.push(`| ${r.q.id} | ${r.info.anchor.assertCount} | ${c.gates.length} | ${c.essential.length} | ${c.droppable.length} | ${c.droppable.join('；')} |`);
  }
  md.push('');
}
md.push('## 逐题结果');
md.push('| 题号 | 级 | 类型 | 结论 | 关键指标 |');
md.push('| --- | --- | --- | --- | --- |');
for (const r of rows) {
  const a = r.info.anchor || {};
  const metric = r.q.type === 'truth'
    ? `n=${a.n} 解=${r.info.survivors ?? '?'} 深=${a.condDepth}`
    : `tx=${a.tx} 量=${a.tracked}`;
  const verdict = r.errors.length ? '**未通过**' : (r.warns.length ? '通过（有警告）' : '通过');
  md.push(`| ${r.q.id} | L${r.q.level} | ${r.q.type} | ${verdict} | ${metric} |`);
}
md.push('');

if (failed.length) {
  md.push('## 未通过明细');
  md.push('');
  for (const r of failed) {
    md.push(`### ${r.q.id}`);
    r.errors.forEach(e => md.push(`- ${e}`));
    md.push('');
  }
}

if (warned.length) {
  md.push('## 警告明细');
  md.push('');
  md.push('难度锚与蓝图不符。这些题能入库，但级别标定可疑，需要要么改题、要么改蓝图区间。');
  md.push('');
  for (const r of warned) {
    md.push(`- ${r.q.id}：${r.warns.join('；')}`);
  }
  md.push('');
}

/* ---- meta.json ---- */

if (process.argv.includes('--write-meta')) {
  const meta = {
    generatedAt: new Date().toISOString(),
    blueprintVersion: bp.blueprintVersion,
    questions: {}
  };
  for (const r of rows) {
    if (r.errors.length) continue;
    const m = { level: r.q.level, type: r.q.type, anchor: r.info.anchor };
    if (r.q.type === 'truth') {
      m.survivors = r.info.survivors;
      m.criticalState = r.info.critical ? r.info.critical.critical : null;
      m.criticalNote = r.info.critical && r.info.critical.hit !== undefined && typeof r.info.critical.hit === 'number'
        ? `去掉它，「恰好${cn(r.q.intendedLiars)}人撒谎」会同时命中 ${r.info.critical.hit} 组候选，必须靠这句二选一`
        : (r.info.critical ? r.info.critical.hit : null);
      if (r.info.claims) {
        m.droppableClaims = r.info.claims.droppable;
        m.essentialClaims = r.info.claims.essential;
      }
      if (m.criticalState) {
        m.commonError = `漏验${m.criticalState}，就会把错误候选当成解`;
      } else if (m.droppableClaims && m.droppableClaims.length) {
        m.commonError = `题面有 ${m.droppableClaims.length} 条可删断言，玩家容易在废话上耗时间而漏看真闸门`;
      }
    } else {
      m.commonError = (r.info.traps || []).map(t => `${t.name} → 会得到 ${t.value}（差 ${t.diff}）：${t.why}`);
    }
    meta.questions[r.q.id] = m;
  }
  fs.writeFileSync(META_OUT, JSON.stringify(meta, null, 2) + '\n', 'utf8');
  md.push('## meta.json');
  md.push('');
  md.push(`已写出，共 ${Object.keys(meta.questions).length} 题（只含通过项）。`);
  md.push('');
}

fs.writeFileSync(REPORT, md.join('\n') + '\n', 'utf8');

console.log(`源题 ${rows.length}，通过 ${rows.length - failed.length}，未通过 ${failed.length}，有警告 ${warned.length}`);
if (failed.length) {
  console.log('\n未通过：');
  failed.forEach(r => console.log(`  ${r.q.id}: ${r.errors.join(' / ')}`));
}
if (warned.length) {
  console.log('\n难度锚警告：');
  warned.forEach(r => console.log(`  ${r.q.id}: ${r.warns.join(' / ')}`));
}
console.log(`\n报告已写出：${path.relative(process.cwd(), REPORT)}`);
if (failed.length) process.exit(1);
