package com.xbtshady.p7;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.xbtshady.p7.data.BankRepo;
import com.xbtshady.p7.data.ProgressStore;
import com.xbtshady.p7.engine.Grader;
import com.xbtshady.p7.engine.LedgerRenderer;
import com.xbtshady.p7.engine.Picker;
import com.xbtshady.p7.engine.QuestionRenderer;
import com.xbtshady.p7.engine.TruthRenderer;
import com.xbtshady.p7.model.Progress;
import com.xbtshady.p7.model.Question;
import com.xbtshady.p7.model.Record;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 唯一 Activity，管三页切换（架构文档 §9 / 前端设计 §1）。
 *
 * <pre>
 * 启动 → 读 bank.json + ProgressStore.load()
 *      → HOME     五级列表 + 设置
 *      → LIST     某级题目列表 + 随机一题
 *      → QUESTION 题面 / 作答 / 提交 / 结果 + 答案
 * </pre>
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "p7";
    /** 挑战档时限 90 秒（架构文档 §8.1：ms 等于时限 90000） */
    private static final long CHALLENGE_TIMEOUT_MS = 90_000L;

    // 三个辅助档
    private static final String AUX_LOOSE = "loose";
    private static final String AUX_STANDARD = "standard";
    private static final String AUX_CHALLENGE = "challenge";

    private final BankRepo repo = new BankRepo();
    private final ProgressStore store = new ProgressStore();
    private final Picker picker = new Picker();

    private Progress progress;
    private String auxTier = AUX_STANDARD;

    // 答题页状态
    private int level = 1;
    private Question current;
    private QuestionRenderer renderer;
    private long startedAtMs;
    private boolean submitted;
    private boolean timedOut;
    private CountDownTimer timer;
    private Record pendingRecord;

    private View homeRoot;
    private View listRoot;
    private View questionRoot;

    private enum Page { HOME, LIST, QUESTION }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        repo.load(this);
        store.init(this);
        progress = store.load(repo.bankVersion(), allQuestionIds());
        auxTier = progress.auxTier;

        if (repo.status() != BankRepo.Status.OK) {
            // §4.3 / §4.5：题库异常或版本不匹配时首页提示，不出题
            showBankProblem();
            return;
        }
        showHome();
    }

    private Set<String> allQuestionIds() {
        Set<String> ids = new HashSet<>();
        for (Question q : repo.all()) ids.add(q.id);
        return ids;
    }

    private void showBankProblem() {
        TextView tv = new TextView(this);
        int msg = repo.status() == BankRepo.Status.MISMATCH ? R.string.bank_mismatch : R.string.bank_error;
        tv.setText(msg);
        tv.setTextColor(getColor(R.color.text_secondary));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_body));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(32, 32, 32, 32);
        setContentView(tv);
    }

    // ==================== HOME ====================

    private void showHome() {
        page = Page.HOME;
        homeRoot = getLayoutInflater().inflate(R.layout.home, null);
        setContentView(homeRoot);

        homeRoot.findViewById(R.id.btn_settings).setOnClickListener(v -> showSettings());

        LinearLayout container = homeRoot.findViewById(R.id.level_container);
        container.removeAllViews();

        // 前端设计 §1：五级永远可点，没有锁定态
        for (int lv = 1; lv <= 5; lv++) {
            container.addView(makeLevelRow(lv, container));
        }
    }

    private View makeLevelRow(int lv, ViewGroup parent) {
        int pad = getResources().getDimensionPixelSize(R.dimen.margin_h);
        int rowH = getResources().getDimensionPixelSize(R.dimen.row_h);

        LinearLayout row = new LinearLayout(this);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowH));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad, 0, pad, 0);

        TextView left = new TextView(this);
        left.setText("L" + lv);
        left.setTextColor(getColor(R.color.text_primary));
        left.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_body));
        left.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(left);

        // 不同题号已做数 / 该级总题数
        List<Question> pool = repo.ofLevel(lv);
        int done = progress.doneCountOf(repo.idsOfLevel(lv));
        TextView right = new TextView(this);
        right.setText(done + "/" + pool.size());
        right.setTextColor(getColor(R.color.text_secondary));
        right.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_body));
        row.addView(right);

        row.setOnClickListener(v -> showList(lv));

        if (lv < 5) {
            View div = new View(this);
            ((LinearLayout) parent).addView(div, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        return row;
    }

    // ==================== LIST ====================

    private void showList(int lv) {
        page = Page.LIST;
        level = lv;
        listRoot = getLayoutInflater().inflate(R.layout.list, null);
        setContentView(listRoot);

        TextView title = listRoot.findViewById(R.id.tv_title);
        title.setText("L" + lv);

        listRoot.findViewById(R.id.btn_back).setOnClickListener(v -> showHome());

        final List<Question> pool = repo.ofLevel(lv);
        Button random = listRoot.findViewById(R.id.btn_random);
        if (pool.isEmpty()) {
            // §8：该级题库为空 → 随机按钮置灰
            random.setEnabled(false);
        } else {
            random.setOnClickListener(v -> {
                Question q = picker.pick(pool, null);
                if (q != null) showQuestion(q);
            });
        }

        ListView list = listRoot.findViewById(R.id.list);
        TextView empty = listRoot.findViewById(R.id.tv_empty);
        if (pool.isEmpty()) {
            list.setVisibility(View.GONE);
            empty.setVisibility(View.VISIBLE);
        } else {
            list.setVisibility(View.VISIBLE);
            empty.setVisibility(View.GONE);
            list.setAdapter(new QuestionAdapter(pool));
            list.setOnItemClickListener((parent, view, position, id) -> showQuestion(pool.get(position)));
        }
    }

    /** 行 = 题号 + 类型 + 上次结果（前端设计 §2） */
    private final class QuestionAdapter extends BaseAdapter {
        private final List<Question> items;

        QuestionAdapter(List<Question> items) {
            this.items = items;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.row_question, parent, false);
            Question q = items.get(position);

            ((TextView) v.findViewById(R.id.tv_id)).setText(q.id);
            ((TextView) v.findViewById(R.id.tv_type)).setText(q.typeLabel());

            TextView r = v.findViewById(R.id.tv_result);
            Boolean ok = progress.firstResultOf(q.id);   // 取最早一条，重做不改这个显示
            if (ok == null) {
                r.setText(R.string.not_done);
                r.setTextColor(getColor(R.color.text_secondary));
            } else if (ok) {
                r.setText(R.string.correct);
                r.setTextColor(getColor(R.color.correct));
            } else {
                r.setText(R.string.wrong);
                r.setTextColor(getColor(R.color.wrong));
            }
            return v;
        }
    }

    // ==================== QUESTION ====================

    private void showQuestion(Question q) {
        page = Page.QUESTION;
        cancelTimer();
        current = q;
        submitted = false;
        timedOut = false;
        pendingRecord = null;
        startedAtMs = System.currentTimeMillis();

        renderer = q.isTruth() ? new TruthRenderer() : new LedgerRenderer();

        questionRoot = getLayoutInflater().inflate(R.layout.question, null);
        setContentView(questionRoot);

        TextView top = questionRoot.findViewById(R.id.tv_top);
        top.setText(q.id + " · 辅助：" + auxLabel());

        questionRoot.findViewById(R.id.btn_exit).setOnClickListener(v -> showList(level));

        ViewGroup promptBox = questionRoot.findViewById(R.id.box_prompt);
        ViewGroup inputBox = questionRoot.findViewById(R.id.box_input);
        ViewGroup firstBox = questionRoot.findViewById(R.id.box_first_step);
        Button submit = questionRoot.findViewById(R.id.btn_submit);

        renderer.renderPrompt(q, promptBox, getLayoutInflater());
        renderer.renderInput(q, inputBox, getLayoutInflater());

        // 三档差异（前端设计 §4）
        if (AUX_CHALLENGE.equals(auxTier)) {
            setupChallenge(firstBox, inputBox, submit);
        } else if (AUX_STANDARD.equals(auxTier)) {
            setupStandard(promptBox, inputBox, submit);
        } else {
            // 宽松：常驻可回看、直接可填、无计时
            renderer.setLocked(inputBox, false);
            submit.setVisibility(View.VISIBLE);
            submit.setOnClickListener(v -> onSubmit());
        }

        submit.setVisibility(View.VISIBLE);
    }

    /** 标准档：初始可见，点"开始作答"后折叠为一行"题面已收起"，不可再展开；折叠后解锁 */
    private void setupStandard(ViewGroup promptBox, ViewGroup inputBox, Button submit) {
        renderer.setLocked(inputBox, true);

        // 折叠按钮放在题面区下方
        Button start = new Button(this);
        start.setText(R.string.start_answering);
        start.setBackgroundResource(R.drawable.bg_button_secondary);
        start.setTextColor(getColor(R.color.text_primary));
        start.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_body));
        int gap = getResources().getDimensionPixelSize(R.dimen.gap_row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                getResources().getDimensionPixelSize(R.dimen.button_h));
        lp.topMargin = gap;
        start.setLayoutParams(lp);
        start.setAllCaps(false);
        promptBox.addView(start);

        submit.setEnabled(false);
        start.setOnClickListener(v -> {
            // 折叠：替换为一行的只读文字，不可再展开
            promptBox.removeAllViews();
            TextView collapsed = new TextView(this);
            collapsed.setText(R.string.prompt_collapsed);
            collapsed.setTextColor(getColor(R.color.text_secondary));
            collapsed.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimension(R.dimen.text_body));
            promptBox.addView(collapsed);

            renderer.setLocked(inputBox, false);
            submit.setEnabled(true);
            submit.setOnClickListener(x -> onSubmit());
            startedAtMs = System.currentTimeMillis();
        });
    }

    /** 挑战档：题面常驻可见，先填第一步才解锁作答，01:30 倒计时 */
    private void setupChallenge(ViewGroup firstBox, ViewGroup inputBox, Button submit) {
        renderer.setLocked(inputBox, true);
        firstBox.setVisibility(View.VISIBLE);

        int gap = getResources().getDimensionPixelSize(R.dimen.gap_row);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        EditText et = new EditText(this);
        et.setHint(R.string.first_step_hint);
        et.setTextColor(getColor(R.color.text_primary));
        et.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_body));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        et.setLayoutParams(elp);
        row.addView(et);

        Button start = new Button(this);
        start.setText(R.string.first_step_start);
        start.setBackgroundResource(R.drawable.bg_button);
        start.setTextColor(getResources().getColor(android.R.color.white));
        start.setAllCaps(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                getResources().getDimensionPixelSize(R.dimen.button_h));
        slp.setMarginStart(gap);
        start.setLayoutParams(slp);
        row.addView(start);
        firstBox.addView(row);

        // 空值或纯空格时按钮置灰（前端设计 §3.3）
        start.setEnabled(false);
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                start.setEnabled(s != null && s.toString().trim().length() > 0);
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        submit.setEnabled(false);
        start.setOnClickListener(v -> {
            String first = et.getText().toString().trim();
            // 本区替换为一行只读文字（玩家输入原文）
            firstBox.removeAllViews();
            TextView kept = new TextView(this);
            kept.setText(first);
            kept.setTextColor(getColor(R.color.text_primary));
            kept.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_body));
            kept.setLineSpacing(0, 1.5f);
            firstBox.addView(kept);

            renderer.setLocked(inputBox, false);
            submit.setEnabled(true);
            submit.setOnClickListener(x -> onSubmit());
            startTimer();
        });
    }

    private void startTimer() {
        cancelTimer();
        startedAtMs = System.currentTimeMillis();
        final TextView tv = questionRoot.findViewById(R.id.tv_timer);
        tv.setVisibility(View.VISIBLE);
        tv.setTextColor(getColor(R.color.text_primary));
        tv.setText("01:30");

        timer = new CountDownTimer(CHALLENGE_TIMEOUT_MS, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                long sec = millisUntilFinished / 1000;
                tv.setText(String.format("%02d:%02d", sec / 60, sec % 60));
                // 剩 10 秒变色（前端设计 §4）
                if (millisUntilFinished <= 10_000) {
                    tv.setTextColor(getColor(R.color.wrong));
                }
            }

            @Override
            public void onFinish() {
                tv.setText("00:00");
                onTimeout();
            }
        }.start();
    }

    private void cancelTimer() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    /** 挑战档倒计时归零：自动提交，按答错处理（架构文档 §8.1） */
    private void onTimeout() {
        if (submitted) return;
        timedOut = true;
        submitted = true;

        ViewGroup inputBox = questionRoot.findViewById(R.id.box_input);
        Button submit = questionRoot.findViewById(R.id.btn_submit);

        // 已填内容原样保留，控件置为不可编辑
        renderer.setLocked(inputBox, true);

        // §8.1：超时按答错处理，ms 等于时限
        boolean ok = false;
        long ms = CHALLENGE_TIMEOUT_MS;

        Record rec = new Record(current.id, System.currentTimeMillis(), ok, ms, auxTier, null);
        progress.records.add(rec);
        progress.lastQid = current.id;
        progress.trim();
        store.save(progress);
        pendingRecord = rec;

        // 提交按钮换成"重做本题"
        submit.setText(R.string.redo);
        submit.setEnabled(true);
        submit.setOnClickListener(v -> redoQuestion());

        renderResult(ok, "timeout");
    }

    private void onSubmit() {
        if (submitted || current == null) return;
        submitted = true;
        cancelTimer();

        ViewGroup inputBox = questionRoot.findViewById(R.id.box_input);
        Object answer = renderer.readAnswer(inputBox);

        boolean ok;
        String signal;
        if (current.isTruth()) {
            Grader.Result r = Grader.gradeTruth(current, (TruthRenderer.Answer) answer);
            ok = r.ok;
            signal = r.signal;
        } else {
            Grader.Result r = Grader.gradeLedger(current, (Integer) answer);
            ok = r.ok;
            signal = r.signal;
        }

        long ms = System.currentTimeMillis() - startedAtMs;
        Record rec = new Record(current.id, System.currentTimeMillis(), ok, ms, auxTier, null);
        progress.records.add(rec);
        progress.lastQid = current.id;
        progress.trim();
        store.save(progress);
        pendingRecord = rec;

        renderResult(ok, signal);
    }

    /** 结果 + 答案区（前端设计 §3.5） */
    private void renderResult(boolean ok, String signal) {
        ViewGroup box = questionRoot.findViewById(R.id.box_result);
        box.setVisibility(View.VISIBLE);
        box.removeAllViews();

        // 第一段 判定：只四个字，不给分数、不给用时
        TextView verdict = new TextView(this);
        verdict.setText(ok ? R.string.correct : R.string.wrong);
        verdict.setTextColor(getColor(ok ? R.color.correct : R.color.wrong));
        verdict.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_title));
        verdict.setTypeface(null, Typeface.BOLD);
        box.addView(verdict);

        // 第二段 卡点：仅答错时
        if (!ok) {
            box.addView(buildStuckSection());
        }

        // 第三段 答案
        int gap = getResources().getDimensionPixelSize(R.dimen.gap_block);
        TextView title = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = gap;
        title.setLayoutParams(lp);
        title.setText(R.string.answer_title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_body));
        title.setTypeface(null, Typeface.BOLD);
        box.addView(title);

        ViewGroup answerBox = new LinearLayout(this);
        answerBox.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout) answerBox).setOrientation(LinearLayout.VERTICAL);
        box.addView(answerBox);
        renderer.renderSolution(current, answerBox, getLayoutInflater());

        // 第四段 按钮
        LinearLayout btns = new LinearLayout(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = gap;
        btns.setLayoutParams(blp);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        Button next = new Button(this);
        next.setText(R.string.next);
        next.setBackgroundResource(R.drawable.bg_button);
        next.setTextColor(getResources().getColor(android.R.color.white));
        next.setAllCaps(false);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                getResources().getDimensionPixelSize(R.dimen.button_h), 1f);
        next.setLayoutParams(nlp);
        next.setOnClickListener(v -> nextQuestion());
        btns.addView(next);

        Button redo = new Button(this);
        redo.setText(R.string.redo);
        redo.setBackgroundResource(R.drawable.bg_button_secondary);
        redo.setTextColor(getColor(R.color.text_primary));
        redo.setAllCaps(false);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0,
                getResources().getDimensionPixelSize(R.dimen.button_h), 1f);
        rlp.setMarginStart(getResources().getDimensionPixelSize(R.dimen.margin_h));
        redo.setLayoutParams(rlp);
        redo.setOnClickListener(v -> redoQuestion());
        btns.addView(redo);

        box.addView(btns);

        Button submit = questionRoot.findViewById(R.id.btn_submit);
        if (timedOut) {
            // 超时后提交按钮已换成"重做本题"，这里不再覆盖
            return;
        }
        submit.setEnabled(false);
    }

    /** 卡点三选一：选中即记录，不必提交，可随时改（前端设计 §3.5） */
    private View buildStuckSection() {
        int gap = getResources().getDimensionPixelSize(R.dimen.gap_block);
        int gapRow = getResources().getDimensionPixelSize(R.dimen.gap_row);

        LinearLayout box = new LinearLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = gap;
        box.setLayoutParams(lp);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(R.string.stuck_title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_body));
        box.addView(title);

        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = gapRow;
        row.setLayoutParams(rlp);
        row.setOrientation(LinearLayout.HORIZONTAL);

        String[] keys = {"hold", "update", "convert"};
        int[] labels = {R.string.stuck_hold, R.string.stuck_update, R.string.stuck_convert};
        int[] notes = {R.string.stuck_hold_note, R.string.stuck_update_note, R.string.stuck_convert_note};

        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < keys.length; i++) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) clp.setMarginStart(gapRow);
            col.setLayoutParams(clp);

            Button b = new Button(this);
            b.setText(labels[i]);
            b.setBackgroundResource(R.drawable.bg_choice);
            b.setTextColor(getColor(R.color.text_primary));
            b.setAllCaps(false);
            b.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_body));
            b.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    getResources().getDimensionPixelSize(R.dimen.button_h)));
            final String key = keys[i];
            b.setOnClickListener(v -> {
                // 选中即记录，可随时改：回写到本条记录的 stuck 字段
                if (pendingRecord != null) pendingRecord.stuck = key;
                store.save(progress);
                b.setSelected(true);
            });

            TextView note = new TextView(this);
            note.setText(notes[i]);
            note.setTextColor(getColor(R.color.text_secondary));
            note.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimension(R.dimen.text_secondary));
            note.setLineSpacing(0, 1.5f);

            col.addView(b);
            col.addView(note);
            cols.addView(col);
        }
        row.addView(cols);
        box.addView(row);
        return box;
    }

    private void nextQuestion() {
        List<Question> pool = repo.ofLevel(level);
        Question q = picker.pick(pool, current != null ? current.id : null);
        if (q == null) {
            Toast.makeText(this, R.string.empty_level, Toast.LENGTH_SHORT).show();
            showList(level);
            return;
        }
        showQuestion(q);
    }

    /** 重做：清空作答、复位题面、答案区收起、重新计时；追加一条新记录（前端设计 §3.5） */
    private void redoQuestion() {
        if (current != null) showQuestion(current);
    }

    // ==================== 设置 ====================

    private void showSettings() {
        final String[] keys = {AUX_LOOSE, AUX_STANDARD, AUX_CHALLENGE};
        String[] labels = {getString(R.string.aux_loose), getString(R.string.aux_standard),
                getString(R.string.aux_challenge)};
        int checked = 1;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(auxTier)) checked = i;
        }

        // 版本信息只读一行：数据 v1 · 题库 vN（前端设计 §5）
        new AlertDialog.Builder(this)
                .setTitle(R.string.aux_tier)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    // 立即生效，从下一题起算（前端设计 §5）
                    auxTier = keys[which];
                    progress.auxTier = auxTier;
                    store.save(progress);
                    dialog.dismiss();
                })
                .setMessage(getString(R.string.version_info, repo.bankVersion()))
                .show();
    }

    private String auxLabel() {
        if (AUX_LOOSE.equals(auxTier)) return getString(R.string.aux_loose);
        if (AUX_CHALLENGE.equals(auxTier)) return getString(R.string.aux_challenge);
        return getString(R.string.aux_standard);
    }

    // ==================== 返回键 ====================

    private Page page = Page.HOME;

    @Override
    public void onBackPressed() {
        // 前端设计 §8：答题页 → 列表；列表 → 首页；首页 → 退出 app
        switch (page) {
            case QUESTION:
                // 退出不算一次作答，不写记录（§8.1）
                cancelTimer();
                showList(level);
                break;
            case LIST:
                showHome();
                break;
            default:
                super.onBackPressed();
                break;
        }
    }

    @Override
    protected void onDestroy() {
        cancelTimer();
        super.onDestroy();
    }
}
