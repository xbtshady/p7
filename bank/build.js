/**
 * 题库构建：src.json（结构化） → bank.json（可展示）
 * 职责只有两件：算准答案、把结构渲染成人话。不生成题目内容。
 * 用法：node bank/build.js
 */
const fs = require('fs');
const path = require('path');

const SRC = path.join(__dirname, 'src.json');
const OUT = path.join(__dirname, 'bank.json');
const CN = ['零', '一', '两', '三', '四', '五', '六', '七', '八', '九', '十'];
const cn = n => CN[n] || String(n);

/* ---------- 真假话：枚举验唯一解 + 生成推理说明 ---------- */

function buildTruth(q) {
  const people = q.people;
  const n = people.length;
  const names = people.map(p => p.name);
  if (n < 3) throw new Error('真假话题至少 3 人（2 人只靠"谁在撒谎"类陈述必然 0 解或 2 解）');
  if (new Set(names).size !== n) throw new Error('人名重复');

  const walk = (c, who) => {
    if (c.kind === 'honest' || c.kind === 'liar') {
      if (!names.includes(c.target)) throw new Error(`${who} 指向了不存在的人 ${c.target}`);
      return;
    }
    if (c.kind === 'count') {
      if (!Number.isInteger(c.k)) throw new Error(`${who} 的 count 缺 k`);
      return;
    }
    if (c.kind === 'cond') {
      walk(c.if, who); walk(c.then, who);
      return;
    }
    throw new Error(`未知 claim kind: ${c.kind}`);
  };
  for (const p of people) for (const c of p.claims) walk(c, p.name);

  const claimText = c => {
    if (c.kind === 'honest') return `${c.target} 说真话`;
    if (c.kind === 'liar') return `${c.target} 在撒谎`;
    if (c.kind === 'count') return `我们中恰好${cn(c.k)}人撒谎`;
    return `如果${claimText(c.if)}，那么${claimText(c.then)}`;
  };
  const claimValue = (c, truth, liars) => {
    if (c.kind === 'honest') return truth[c.target];
    if (c.kind === 'liar') return !truth[c.target];
    if (c.kind === 'count') return liars === c.k;
    return claimValue(c.if, truth, liars) ? claimValue(c.then, truth, liars) : true;
  };

  const consistent = [];
  for (let mask = 0; mask < 1 << n; mask++) {
    const truth = {};
    names.forEach((nm, i) => { truth[nm] = ((mask >> i) & 1) === 1; });
    const liars = names.filter(nm => !truth[nm]).length;
    let ok = true;
    for (const p of people) {
      const isHonest = truth[p.name];
      for (const c of p.claims) {
        if (claimValue(c, truth, liars) !== isHonest) { ok = false; break; }
      }
      if (!ok) break;
    }
    if (ok) consistent.push({ truth, liars });
  }

  if (consistent.length < 2) {
    throw new Error(`自洽解只有 ${consistent.length} 组，不需要排除候选，达不到"基础心智模拟"的底线`);
  }

  const k = q.intendedLiars;
  const hits = consistent.filter(s => s.liars === k);
  if (hits.length !== 1) {
    throw new Error(
      `intendedLiars=${k} 命中的自洽解有 ${hits.length} 组（全部自洽解：` +
      consistent.map(s => names.map(nm => nm + (s.truth[nm] ? '真' : '假')).join('') + `/${s.liars}`).join(' ') +
      '）'
    );
  }
  const sol = hits[0];

  const solText = s => names.map(nm => nm + (s.truth[nm] ? '真' : '假')).join('、');
  const lines = [];

  lines.push(
    `把所有 ${1 << n} 种真假组合代进去，能让每句话和自己身份对上的一共 ${consistent.length} 组：` +
    consistent.map(solText).join('；') + '。'
  );
  lines.push(`其中满足"恰好${cn(k)}人撒谎"的只有一组：${solText(sol)}。`);

  for (const p of people) {
    const isHonest = sol.truth[p.name];
    for (const c of p.claims) {
      const v = claimValue(c, sol.truth, sol.liars);
      lines.push(
        `${p.name} 是${isHonest ? '说真话的人' : '撒谎的人'}，他说的"${claimText(c)}"确实是${v ? '真' : '假'}话——` +
        `${isHonest ? '真话者的陈述为真' : '撒谎者的陈述为假'}，${v === isHonest ? '对得上' : '对不上'}。`
      );
    }
  }

  const summary = `${names.map(nm => nm + ' ' + (sol.truth[nm] ? '真' : '假')).join('、')}，撒谎 ${k} 人`;

  return {
    id: q.id,
    type: 'truth',
    level: q.level,
    mode: 'answer',
    prototype: q.prototype,
    prompt: {
      people: people.map(p => ({ name: p.name, says: p.claims.map(claimText).join('；') })),
      constraint: `恰好${cn(k)}人撒谎`
    },
    answer: { truth: sol.truth, liars: k },
    solution: { lines, summary }
  };
}

/* ---------- 账本：从交易序列重算每一行 ---------- */

function buildLedger(q) {
  const { open, tx, closePrice, question } = q;
  let cash = open.cash;
  let hold = open.hold;
  const ledger = [{ step: '期初', cash, hold, note: `市价 ${open.price}` }];

  for (const t of tx) {
    if (t.op === 'buy') {
      cash -= t.qty * t.price;
      hold += t.qty;
      ledger.push({ step: `买入 ${t.qty} 件，每件 ${t.price}，付 ${t.qty * t.price}`, cash, hold });
    } else if (t.op === 'sell') {
      cash += t.qty * t.price;
      hold -= t.qty;
      ledger.push({ step: `卖出 ${t.qty} 件，每件 ${t.price}，收 ${t.qty * t.price}`, cash, hold });
    } else if (t.op === 'swap') {
      cash -= t.cash;
      hold += t.take - t.give;
      ledger.push({ step: `用 ${t.give} 件加 ${t.cash} 元换 ${t.take} 件`, cash, hold });
    } else {
      throw new Error(`未知交易类型 ${t.op}`);
    }
    if (hold < 0) throw new Error(`${q.id}：持货变成负数`);
    if (cash < 0) throw new Error(`${q.id}：现金变成负数`);
  }
  ledger.push({ step: '期末', cash, hold, note: `市价 ${closePrice}` });

  const openTotal = open.cash + open.hold * open.price;
  const closeTotal = cash + hold * closePrice;
  const net = closeTotal - openTotal;
  const value = question === 'total' ? closeTotal : net;

  const narrative = [];
  narrative.push(
    open.hold > 0
      ? `期初有现金 ${open.cash} 元，手里 ${open.hold} 件货，当时市价 ${open.price} 元。`
      : `期初有现金 ${open.cash} 元，手里没有货，当时市价 ${open.price} 元。`
  );
  for (const t of tx) {
    const tail = t.note ? `（${t.note}）` : '';
    if (t.op === 'buy') narrative.push(`花 ${t.qty * t.price} 元买入 ${t.qty} 件${tail}。`);
    else if (t.op === 'sell') narrative.push(`以 ${t.qty * t.price} 元卖出 ${t.qty} 件${tail}。`);
    else narrative.push(`用 ${t.give} 件货加 ${t.cash} 元，换来 ${t.take} 件${tail}。`);
  }
  narrative.push(`现市价 ${closePrice} 元。`);

  const lines = [
    `期初总资产 ${openTotal}（${open.cash} + ${open.hold}×${open.price}）`,
    `期末现金 ${cash}，持货 ${hold} 件`,
    `期末总资产 ${closeTotal}（${cash} + ${hold}×${closePrice}）`,
    `净盈亏 ${net >= 0 ? '+' : ''}${net}`
  ];

  return {
    id: q.id,
    type: 'ledger',
    level: q.level,
    mode: 'answer',
    prototype: q.prototype,
    prompt: { narrative, question },
    answer: { value },
    solution: { ledger, lines, summary: question === 'total' ? `期末总资产 ${closeTotal} 元` : `净盈亏 ${net >= 0 ? '+' : ''}${net} 元` }
  };
}

/* ---------- 校验输出（app 侧还有一次运行期校验） ---------- */

function validate(out) {
  const errs = [];
  const ids = new Set();
  for (const q of out.questions) {
    const tag = q.id || '(无 id)';
    if (!q.id) errs.push('存在无 id 的题');
    if (ids.has(q.id)) errs.push(`${tag}：id 重复`);
    ids.add(q.id);
    if (![1, 2, 3, 4, 5].includes(q.level)) errs.push(`${tag}：level 越界`);
    if (!q.prototype) errs.push(`${tag}：缺 prototype`);
    if (q.mode !== 'answer') errs.push(`${tag}：mode 不是 answer`);
    if (q.type === 'truth') {
      const names = q.prompt.people.map(p => p.name);
      if (!q.prompt.people.length) errs.push(`${tag}：people 为空`);
      if (!q.prompt.constraint) errs.push(`${tag}：constraint 为空`);
      if (Object.keys(q.answer.truth).sort().join() !== [...names].sort().join()) errs.push(`${tag}：answer.truth 与 people 不一致`);
      if (!Number.isInteger(q.answer.liars)) errs.push(`${tag}：liars 不是整数`);
      if (!q.solution.lines.length) errs.push(`${tag}：solution 为空`);
    } else if (q.type === 'ledger') {
      if (!q.prompt.narrative.length) errs.push(`${tag}：narrative 为空`);
      if (!['net', 'total'].includes(q.prompt.question)) errs.push(`${tag}：question 非法`);
      if (!Number.isInteger(q.answer.value)) errs.push(`${tag}：answer.value 不是整数`);
      if (!q.solution.ledger.length) errs.push(`${tag}：solution.ledger 为空`);
    } else {
      errs.push(`${tag}：未知 type ${q.type}`);
    }
  }
  return errs;
}

/* ---------- 主流程 ---------- */

const src = JSON.parse(fs.readFileSync(SRC, 'utf8'));
const questions = [];
const failures = [];

for (const q of src.questions) {
  try {
    questions.push(q.type === 'truth' ? buildTruth(q) : buildLedger(q));
  } catch (e) {
    failures.push(`${q.id}: ${e.message}`);
  }
}

const out = { schemaVersion: 1, bankVersion: src.bankVersion, questions };
const errs = validate(out);

const stat = {};
for (const q of questions) {
  stat[q.level] = stat[q.level] || { truth: 0, ledger: 0 };
  stat[q.level][q.type] += 1;
}

console.log(`源题 ${src.questions.length}，构建成功 ${questions.length}，失败 ${failures.length}`);
if (failures.length) {
  console.log('\n构建失败：');
  failures.forEach(f => console.log('  ' + f));
}
if (errs.length) {
  console.log('\n校验不通过：');
  errs.forEach(e => console.log('  ' + e));
}
console.log('\n分级统计：');
for (const lv of Object.keys(stat).sort()) {
  console.log(`  L${lv}: 真假话 ${stat[lv].truth || 0}，账本 ${stat[lv].ledger || 0}`);
}
const proto = {};
questions.forEach(q => { proto[q.prototype] = (proto[q.prototype] || 0) + 1; });
console.log('\n原型覆盖：');
for (const p of Object.keys(proto).sort()) console.log(`  ${p}: ${proto[p]}`);

if (failures.length || errs.length) {
  console.log('\n未写出 bank.json。');
  process.exit(1);
}

fs.writeFileSync(OUT, JSON.stringify(out, null, 2) + '\n', 'utf8');
console.log(`\n已写出 ${path.relative(process.cwd(), OUT)}（${questions.length} 题）`);
