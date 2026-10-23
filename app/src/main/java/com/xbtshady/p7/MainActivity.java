package com.xbtshady.p7;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.xbtshady.p7.data.BankRepo;
import com.xbtshady.p7.data.ProgressStore;
import com.xbtshady.p7.engine.Grader;
import com.xbtshady.p7.engine.Picker;
import com.xbtshady.p7.engine.QuestionRenderer;
import com.xbtshady.p7.engine.TruthRenderer;
import com.xbtshady.p7.model.Progress;
import com.xbtshady.p7.model.Question;
import com.xbtshady.p7.model.Record;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 2.0：底栏概览 / 题库 / 记录 + 全屏题目页。
 */
public class MainActivity extends AppCompatActivity {

    private static final int PAGE_BANK = 30;
    private static final int PAGE_HISTORY = 50;
    private static final int[] LEVEL_PEOPLE = {0, 3, 4, 6, 8, 12};
    private static final long[] CHALLENGE_MS = {
            0, 90_000, 105_000, 120_000, 150_000, 180_000
    };

    private static final String AUX_LOOSE = "loose";
    private static final String AUX_STANDARD = "standard";
    private static final String AUX_CHALLENGE = "challenge";

    private static final String SRC_OVERVIEW = "overview";
    private static final String SRC_BANK = "bank";
    private static final String SRC_HISTORY = "history";

    private final BankRepo repo = new BankRepo();
    private final ProgressStore store = new ProgressStore();
    private final Picker picker = new Picker();

    private Progress progress;
    private String auxTier = AUX_STANDARD;

    private enum Tab { OVERVIEW, BANK, HISTORY }

    private Tab tab = Tab.OVERVIEW;
    private boolean onQuestion;

    /** 题库页自身筛选（不回写概览默认，除分级条跳转外） */
    private int bankLevel; // 0=鍏ㄩ儴
    private String bankStatus = Progress.STATUS_ALL;
    private int bankPage;
    private int historyPage;

    // 题目页
    private Question current;
    private QuestionRenderer renderer;
    private String entrySource = SRC_OVERVIEW;
    private int snapLevel;
    private String snapStatus = Progress.STATUS_ALL;
    private long startedAtMs;
    private boolean submitted;
    private CountDownTimer timer;

    private FrameLayout content;
    private View bottomNav;
    private View navDivider;
    private TextView tabOverview;
    private TextView tabBank;
    private TextView tabHistory;

    private View overviewRoot;
    private View bankRoot;
    private View historyRoot;
    private View questionRoot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applySystemBarInsets(findViewById(android.R.id.content));

        content = findViewById(R.id.content);
        bottomNav = findViewById(R.id.bottom_nav);
        navDivider = findViewById(R.id.nav_divider);
        tabOverview = findViewById(R.id.tab_overview);
        tabBank = findViewById(R.id.tab_bank);
        tabHistory = findViewById(R.id.tab_history);

        tabOverview.setOnClickListener(v -> showTab(Tab.OVERVIEW));
        tabBank.setOnClickListener(v -> showTab(Tab.BANK));
        tabHistory.setOnClickListener(v -> showTab(Tab.HISTORY));

        repo.load(this);
        store.init(this);
        progress = store.load(repo.bankVersion(), repo.allProductIds());
        auxTier = progress.auxTier == null ? AUX_STANDARD : progress.auxTier;
        bankLevel = progress.filterLevel; // 0 鍏ㄩ儴鏃剁敤 filterLevel 鍙兘鏄?2锛岄搴撳垵濮嬭窡姒傝榛樿闅惧害
        if (bankLevel < 0) bankLevel = 0;
        bankStatus = progress.filterStatus == null ? Progress.STATUS_ALL : progress.filterStatus;

        if (repo.status() != BankRepo.Status.OK) {
            showTab(Tab.OVERVIEW);
            return;
        }
        showTab(Tab.OVERVIEW);
    }

    private void showTab(Tab t) {
        cancelTimer();
        onQuestion = false;
        tab = t;
        bottomNav.setVisibility(View.VISIBLE);
        navDivider.setVisibility(View.VISIBLE);
        highlightTabs();
        content.removeAllViews();
        switch (t) {
            case BANK:
                bankRoot = getLayoutInflater().inflate(R.layout.bank, content, false);
                content.addView(bankRoot);
                bindBank();
                break;
            case HISTORY:
                historyRoot = getLayoutInflater().inflate(R.layout.history, content, false);
                content.addView(historyRoot);
                bindHistory();
                break;
            default:
                overviewRoot = getLayoutInflater().inflate(R.layout.overview, content, false);
                content.addView(overviewRoot);
                bindOverview();
                break;
        }
    }

    private void highlightTabs() {
        int accent = getColor(R.color.accent);
        int secondary = getColor(R.color.text_secondary);
        tabOverview.setTextColor(tab == Tab.OVERVIEW ? accent : secondary);
        tabBank.setTextColor(tab == Tab.BANK ? accent : secondary);
        tabHistory.setTextColor(tab == Tab.HISTORY ? accent : secondary);
    }

    // ==================== OVERVIEW ====================

    private void bindOverview() {
        overviewRoot.findViewById(R.id.btn_settings).setOnClickListener(v -> showSettings());

        TextView err = overviewRoot.findViewById(R.id.tv_overview_error);
        View practice = overviewRoot.findViewById(R.id.btn_practice);
        LinearLayout levels = overviewRoot.findViewById(R.id.level_container);

        if (repo.status() != BankRepo.Status.OK) {
            err.setVisibility(View.VISIBLE);
            err.setText(repo.status() == BankRepo.Status.MISMATCH
                    ? R.string.bank_mismatch : R.string.bank_error);
            practice.setEnabled(false);
            levels.removeAllViews();
            overviewRoot.findViewById(R.id.tv_solved_total).setVisibility(View.GONE);
            overviewRoot.findViewById(R.id.tv_first_rate).setVisibility(View.GONE);
            overviewRoot.findViewById(R.id.tv_last7).setVisibility(View.GONE);
            overviewRoot.findViewById(R.id.tv_recent_title).setVisibility(View.GONE);
            return;
        }
        err.setVisibility(View.GONE);

        int solved = progress.solvedTotal(repo.allProductIds());
        ((TextView) overviewRoot.findViewById(R.id.tv_solved_total))
                .setText(getString(R.string.solved_total, solved));

        levels.removeAllViews();
        for (int lv = 1; lv <= 5; lv++) {
            levels.addView(makeLevelRow(lv));
        }

        TextView rate = overviewRoot.findViewById(R.id.tv_first_rate);
        double r = progress.firstAttemptRate(Progress.FIRST_RATE_WINDOW);
        if (Double.isNaN(r)) {
            rate.setText(R.string.first_rate_empty);
        } else {
            int pct = (int) Math.round(r * 100);
            int n = Math.min(Progress.FIRST_RATE_WINDOW, uniqueFirstCount());
            rate.setText(getString(R.string.first_rate, n, pct));
        }
        ((TextView) overviewRoot.findViewById(R.id.tv_last7))
                .setText(getString(R.string.last_7d, progress.submissionsLast7Days()));

        practice.setEnabled(true);
        practice.setOnClickListener(v -> {
            int lv = progress.filterLevel;
            String st = progress.filterStatus == null ? Progress.STATUS_ALL : progress.filterStatus;
            openRandom(lv, st, SRC_OVERVIEW);
        });

        bindRecent();
    }

    private int uniqueFirstCount() {
        java.util.HashSet<String> s = new java.util.HashSet<>();
        for (Record r : progress.records) s.add(r.qid);
        return s.size();
    }

    private View makeLevelRow(int lv) {
        int pad = getResources().getDimensionPixelSize(R.dimen.margin_h);
        int gap = getResources().getDimensionPixelSize(R.dimen.gap_row);

        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = gap;
        row.setLayoutParams(lp);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_row_press);
        row.setPadding(pad, pad, pad, pad);
        row.setMinimumHeight(getResources().getDimensionPixelSize(R.dimen.row_h));

        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView left = new TextView(this);
        left.setText("L" + lv);
        left.setTextColor(getColor(R.color.text_primary));
        left.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_body));
        leftCol.addView(left);

        TextView hint = new TextView(this);
        hint.setText(getString(R.string.level_hint, LEVEL_PEOPLE[lv]));
        hint.setTextColor(getColor(R.color.text_secondary));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.text_secondary));
        leftCol.addView(hint);
        row.addView(leftCol);

        int total = repo.countOfLevel(lv);
        int done = progress.solvedCountOf(repo.idsOfLevel(lv));
        TextView right = new TextView(this);
        right.setText(done + "/" + total);
        right.setTextColor(getColor(R.color.text_secondary));
        right.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_body));
        row.addView(right);

        row.setOnClickListener(v -> {
            progress.filterLevel = lv;
            store.save(progress);
            bankLevel = lv;
            bankStatus = Progress.STATUS_ALL;
            bankPage = 0;
            showTab(Tab.BANK);
        });
        return row;
    }

    private void bindRecent() {
        LinearLayout box = overviewRoot.findViewById(R.id.recent_container);
        TextView title = overviewRoot.findViewById(R.id.tv_recent_title);
        box.removeAllViews();
        List<Record> recent = progress.recent(10);
        if (recent.isEmpty()) {
            title.setVisibility(View.GONE);
            return;
        }
        title.setVisibility(View.VISIBLE);
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        int gap = getResources().getDimensionPixelSize(R.dimen.gap_row);
        for (Record rec : recent) {
            TextView tv = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = gap;
            tv.setLayoutParams(lp);
            String ok = getString(rec.ok ? R.string.correct : R.string.wrong);
            tv.setText(fmt.format(new Date(rec.ts)) + "  " + rec.qid + "  " + ok);
            tv.setTextColor(getColor(R.color.text_primary));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimension(R.dimen.text_body));
            tv.setPadding(0, gap, 0, gap);
            tv.setOnClickListener(v -> openQuestion(rec.qid, SRC_OVERVIEW,
                    progress.filterLevel, Progress.STATUS_ALL));
            box.addView(tv);
        }
    }

    // ==================== BANK ====================

    private void bindBank() {
        LinearLayout levelChips = bankRoot.findViewById(R.id.level_chips);
        LinearLayout statusChips = bankRoot.findViewById(R.id.status_chips);
        levelChips.removeAllViews();
        statusChips.removeAllViews();

        addChip(levelChips, getString(R.string.filter_all), bankLevel == 0, v -> {
            bankLevel = 0;
            bankPage = 0;
            bindBank();
        });
        for (int lv = 1; lv <= 5; lv++) {
            final int L = lv;
            addChip(levelChips, "L" + lv, bankLevel == lv, v -> {
                bankLevel = L;
                bankPage = 0;
                bindBank();
            });
        }

        addChip(statusChips, getString(R.string.filter_all),
                Progress.STATUS_ALL.equals(bankStatus), v -> {
                    bankStatus = Progress.STATUS_ALL;
                    bankPage = 0;
                    bindBank();
                });
        addChip(statusChips, getString(R.string.status_todo),
                Progress.STATUS_TODO.equals(bankStatus), v -> {
                    bankStatus = Progress.STATUS_TODO;
                    bankPage = 0;
                    bindBank();
                });
        addChip(statusChips, getString(R.string.status_tried),
                Progress.STATUS_TRIED.equals(bankStatus), v -> {
                    bankStatus = Progress.STATUS_TRIED;
                    bankPage = 0;
                    bindBank();
                });
        addChip(statusChips, getString(R.string.status_solved),
                Progress.STATUS_SOLVED.equals(bankStatus), v -> {
                    bankStatus = Progress.STATUS_SOLVED;
                    bankPage = 0;
                    bindBank();
                });

        int total = repo.countMatching(bankLevel, bankStatus, progress);
        int pages = Math.max(1, (total + PAGE_BANK - 1) / PAGE_BANK);
        if (bankPage >= pages) bankPage = pages - 1;
        if (bankPage < 0) bankPage = 0;

        List<Question> page = repo.page(bankLevel, bankStatus, progress,
                bankPage * PAGE_BANK, PAGE_BANK);

        Button random = bankRoot.findViewById(R.id.btn_random);
        ListView list = bankRoot.findViewById(R.id.list);
        TextView empty = bankRoot.findViewById(R.id.tv_empty);
        View pager = bankRoot.findViewById(R.id.pager);

        if (total == 0) {
            random.setEnabled(false);
            list.setVisibility(View.GONE);
            empty.setVisibility(View.VISIBLE);
            pager.setVisibility(View.GONE);
        } else {
            random.setEnabled(true);
            random.setOnClickListener(v -> openRandom(bankLevel, bankStatus, SRC_BANK));
            list.setVisibility(View.VISIBLE);
            empty.setVisibility(View.GONE);
            pager.setVisibility(View.VISIBLE);
            list.setAdapter(new BankAdapter(page));
            list.setOnItemClickListener((p, view, pos, id) ->
                    openQuestion(page.get(pos).id, SRC_BANK, bankLevel, bankStatus));

            ((TextView) bankRoot.findViewById(R.id.tv_page))
                    .setText(getString(R.string.page_fmt, bankPage + 1, pages));
            bankRoot.findViewById(R.id.btn_prev).setOnClickListener(v -> {
                if (bankPage > 0) {
                    bankPage--;
                    bindBank();
                }
            });
            bankRoot.findViewById(R.id.btn_next).setOnClickListener(v -> {
                if (bankPage + 1 < pages) {
                    bankPage++;
                    bindBank();
                }
            });
        }
    }

    private void addChip(LinearLayout parent, String label, boolean selected, View.OnClickListener click) {
        TextView tv = new TextView(this);
        int gap = getResources().getDimensionPixelSize(R.dimen.gap_row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                getResources().getDimensionPixelSize(R.dimen.button_h));
        lp.setMarginEnd(gap);
        tv.setLayoutParams(lp);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(gap * 2, 0, gap * 2, 0);
        tv.setBackgroundResource(R.drawable.bg_choice);
        tv.setSelected(selected);
        tv.setTextColor(getColor(R.color.text_primary));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_body));
        tv.setOnClickListener(click);
        parent.addView(tv);
    }

    private final class BankAdapter extends BaseAdapter {
        private final List<Question> items;

        BankAdapter(List<Question> items) {
            this.items = items;
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.row_question, parent, false);
            Question q = items.get(position);
            ((TextView) v.findViewById(R.id.tv_id)).setText(q.id);
            ((TextView) v.findViewById(R.id.tv_people))
                    .setText(getString(R.string.people_count, q.peopleCount()));
            TextView st = v.findViewById(R.id.tv_status);
            Progress.QStatus qs = progress.statusOf(q.id);
            if (qs == Progress.QStatus.SOLVED) {
                st.setText(R.string.status_solved);
                st.setTextColor(getColor(R.color.correct));
            } else if (qs == Progress.QStatus.TRIED) {
                st.setText(R.string.status_tried);
                st.setTextColor(getColor(R.color.text_secondary));
            } else {
                st.setText(R.string.status_todo);
                st.setTextColor(getColor(R.color.text_secondary));
            }
            return v;
        }
    }

    // ==================== HISTORY ====================

    private void bindHistory() {
        int total = progress.historyCount();
        int pages = Math.max(1, (total + PAGE_HISTORY - 1) / PAGE_HISTORY);
        if (historyPage >= pages) historyPage = pages - 1;
        if (historyPage < 0) historyPage = 0;

        ListView list = historyRoot.findViewById(R.id.list);
        TextView empty = historyRoot.findViewById(R.id.tv_empty);
        View pager = historyRoot.findViewById(R.id.pager);

        if (total == 0) {
            list.setVisibility(View.GONE);
            empty.setVisibility(View.VISIBLE);
            pager.setVisibility(View.GONE);
            return;
        }
        list.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        pager.setVisibility(View.VISIBLE);

        List<Record> page = progress.historyPage(historyPage * PAGE_HISTORY, PAGE_HISTORY);
        list.setAdapter(new HistoryAdapter(page));
        list.setOnItemClickListener((p, view, pos, id) -> {
            Record rec = page.get(pos);
            // 璁板綍杩涘叆锛氬揩鐓?= 鍏ㄩ儴闅惧害+鍏ㄩ儴鐘舵€?            openQuestion(rec.qid, SRC_HISTORY, 0, Progress.STATUS_ALL);
        });

        ((TextView) historyRoot.findViewById(R.id.tv_page))
                .setText(getString(R.string.page_fmt, historyPage + 1, pages));
        historyRoot.findViewById(R.id.btn_prev).setOnClickListener(v -> {
            if (historyPage > 0) {
                historyPage--;
                bindHistory();
            }
        });
        historyRoot.findViewById(R.id.btn_next).setOnClickListener(v -> {
            if (historyPage + 1 < pages) {
                historyPage++;
                bindHistory();
            }
        });
    }

    private final class HistoryAdapter extends BaseAdapter {
        private final List<Record> items;
        private final SimpleDateFormat fmt =
                new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

        HistoryAdapter(List<Record> items) {
            this.items = items;
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.row_record, parent, false);
            Record rec = items.get(position);
            ((TextView) v.findViewById(R.id.tv_when)).setText(fmt.format(new Date(rec.ts)));
            TextView ok = v.findViewById(R.id.tv_ok);
            ok.setText(rec.ok ? R.string.correct : R.string.wrong);
            ok.setTextColor(getColor(rec.ok ? R.color.correct : R.color.wrong));
            ((TextView) v.findViewById(R.id.tv_qid)).setText(rec.qid);
            ((TextView) v.findViewById(R.id.tv_meta)).setText(recordMeta(rec));
            return v;
        }
    }

    private boolean isTimeoutMs(Record rec) {
        Question q = repo.byId(rec.qid);
        if (q == null) return false;
        long lim = CHALLENGE_MS[Math.min(5, Math.max(1, q.level))];
        return AUX_CHALLENGE.equals(rec.aux) && rec.ms == lim;
    }

    // ==================== QUESTION ====================

    private void openRandom(int level, String status, String source) {
        List<Question> pool = repo.matching(level, status, progress);
        Question q = picker.pick(pool, progress.lastQid);
        if (q == null) {
            Toast.makeText(this, R.string.pick_fail, Toast.LENGTH_SHORT).show();
            return;
        }
        openQuestion(q.id, source, level, status);
    }

    private void openQuestion(String qid, String source, int snapLv, String snapSt) {
        Question q = repo.byId(qid);
        if (q == null || !q.inProductPool()) {
            Toast.makeText(this, R.string.retired, Toast.LENGTH_SHORT).show();
            return;
        }
        entrySource = source;
        snapLevel = snapLv;
        snapStatus = snapSt == null ? Progress.STATUS_ALL : snapSt;
        showQuestion(q);
    }

    private void showQuestion(Question q) {
        cancelTimer();
        onQuestion = true;
        current = q;
        submitted = false;
        startedAtMs = System.currentTimeMillis();
        progress.lastQid = q.id;
        store.save(progress);

        renderer = new TruthRenderer();

        bottomNav.setVisibility(View.GONE);
        navDivider.setVisibility(View.GONE);
        content.removeAllViews();
        questionRoot = getLayoutInflater().inflate(R.layout.question, content, false);
        content.addView(questionRoot);

        ((TextView) questionRoot.findViewById(R.id.tv_top))
                .setText(q.id + " 路 " + auxLabel());
        questionRoot.findViewById(R.id.btn_close).setOnClickListener(v -> closeQuestion());

        ViewGroup promptBox = questionRoot.findViewById(R.id.box_prompt);
        ViewGroup inputBox = questionRoot.findViewById(R.id.box_input);
        Button submit = questionRoot.findViewById(R.id.btn_submit);

        renderer.renderPrompt(q, promptBox, getLayoutInflater());
        renderer.renderInput(q, inputBox, getLayoutInflater());
        renderer.setLocked(inputBox, false);

        submit.setVisibility(View.VISIBLE);
        submit.setEnabled(true);
        submit.setText(R.string.submit);
        submit.setOnClickListener(v -> onSubmit(false));

        questionRoot.findViewById(R.id.box_result).setVisibility(View.GONE);
        questionRoot.findViewById(R.id.box_answer).setVisibility(View.GONE);
        bindAttempts(q.id);

        if (AUX_CHALLENGE.equals(auxTier)) {
            startTimer(q.level);
        } else {
            questionRoot.findViewById(R.id.tv_timer).setVisibility(View.GONE);
        }
    }

    private void startTimer(int level) {
        cancelTimer();
        long limit = CHALLENGE_MS[Math.min(5, Math.max(1, level))];
        final TextView tv = questionRoot.findViewById(R.id.tv_timer);
        tv.setVisibility(View.VISIBLE);
        tv.setTextColor(getColor(R.color.text_primary));
        startedAtMs = System.currentTimeMillis();

        timer = new CountDownTimer(limit, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                long sec = millisUntilFinished / 1000;
                tv.setText(String.format(Locale.getDefault(), "%02d:%02d", sec / 60, sec % 60));
                if (millisUntilFinished <= 10_000) {
                    tv.setTextColor(getColor(R.color.wrong));
                }
            }

            @Override
            public void onFinish() {
                tv.setText("00:00");
                onSubmit(true);
            }
        }.start();
    }

    private void cancelTimer() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    private void onSubmit(boolean timeout) {
        if (submitted || current == null || questionRoot == null) return;
        submitted = true;
        cancelTimer();

        ViewGroup inputBox = questionRoot.findViewById(R.id.box_input);
        Button submit = questionRoot.findViewById(R.id.btn_submit);
        renderer.setLocked(inputBox, true);
        submit.setVisibility(View.GONE);

        boolean ok;
        long ms;
        if (timeout) {
            ok = false;
            ms = CHALLENGE_MS[Math.min(5, Math.max(1, current.level))];
        } else {
            Object answer = renderer.readAnswer(inputBox);
            Grader.Result r = Grader.gradeTruth(current, (TruthRenderer.Answer) answer);
            ok = r.ok;
            ms = System.currentTimeMillis() - startedAtMs;
        }

        Record rec = new Record(current.id, System.currentTimeMillis(), ok, ms, auxTier,
                null, repo.bankVersion());
        progress.records.add(rec);
        progress.trim();
        store.save(progress);

        renderResult(ok);
        bindAttempts(current.id);
    }

    private void renderResult(boolean ok) {
        ViewGroup box = questionRoot.findViewById(R.id.box_result);
        ViewGroup answerBox = questionRoot.findViewById(R.id.box_answer);
        box.setVisibility(View.VISIBLE);
        box.removeAllViews();
        answerBox.setVisibility(View.GONE);
        answerBox.removeAllViews();

        TextView verdict = new TextView(this);
        verdict.setText(ok ? R.string.correct : R.string.wrong);
        verdict.setTextColor(getColor(ok ? R.color.correct : R.color.wrong));
        verdict.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_title));
        verdict.setTypeface(null, Typeface.BOLD);
        box.addView(verdict);

        Button showAnswer = new Button(this);
        showAnswer.setText(R.string.show_answer);
        showAnswer.setBackgroundResource(R.drawable.bg_button);
        showAnswer.setTextColor(getColor(R.color.text_on_accent));
        showAnswer.setAllCaps(false);
        showAnswer.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_body));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                getResources().getDimensionPixelSize(R.dimen.button_h));
        alp.topMargin = getResources().getDimensionPixelSize(R.dimen.gap_block);
        showAnswer.setLayoutParams(alp);
        showAnswer.setOnClickListener(v -> {
            if (answerBox.getVisibility() == View.VISIBLE) {
                answerBox.setVisibility(View.GONE);
                showAnswer.setText(R.string.show_answer);
            } else {
                renderAnswerInto(answerBox);
                answerBox.setVisibility(View.VISIBLE);
                showAnswer.setText(R.string.hide_answer);
            }
        });
        box.addView(showAnswer);
        box.addView(buildActionRow());
    }

    private void renderAnswerInto(ViewGroup box) {
        box.removeAllViews();
        int gap = getResources().getDimensionPixelSize(R.dimen.gap_row);
        TextView title = new TextView(this);
        title.setText(R.string.answer_title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_body));
        title.setTypeface(null, Typeface.BOLD);
        box.addView(title);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = gap;
        body.setLayoutParams(blp);
        box.addView(body);
        renderer.renderSolution(current, body, getLayoutInflater());
    }

    private View buildActionRow() {
        int gap = getResources().getDimensionPixelSize(R.dimen.margin_h);
        int rowGap = getResources().getDimensionPixelSize(R.dimen.gap_row);
        LinearLayout btns = new LinearLayout(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = rowGap;
        btns.setLayoutParams(blp);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        Button next = new Button(this);
        next.setText(R.string.next);
        next.setBackgroundResource(R.drawable.bg_button_secondary);
        next.setTextColor(getColor(R.color.text_primary));
        next.setAllCaps(false);
        next.setLayoutParams(new LinearLayout.LayoutParams(0,
                getResources().getDimensionPixelSize(R.dimen.button_h), 1f));
        next.setOnClickListener(v -> {
            List<Question> pool = repo.matching(snapLevel, snapStatus, progress);
            Question q = picker.pick(pool, current != null ? current.id : null);
            if (q == null) {
                Toast.makeText(this, R.string.pick_fail, Toast.LENGTH_SHORT).show();
                return;
            }
            // 缁ф壙 entrySource
            openQuestion(q.id, entrySource, snapLevel, snapStatus);
        });
        btns.addView(next);

        Button redo = new Button(this);
        redo.setText(R.string.redo);
        redo.setBackgroundResource(R.drawable.bg_button_secondary);
        redo.setTextColor(getColor(R.color.text_primary));
        redo.setAllCaps(false);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0,
                getResources().getDimensionPixelSize(R.dimen.button_h), 1f);
        rlp.setMarginStart(gap);
        redo.setLayoutParams(rlp);
        redo.setOnClickListener(v -> {
            if (current != null) showQuestion(current);
        });
        btns.addView(redo);
        return btns;
    }

    /** 本题提交历史（新→旧），无记录则隐藏。不展示当时作答内容。 */
    private void bindAttempts(String qid) {
        if (questionRoot == null) return;
        ViewGroup box = questionRoot.findViewById(R.id.box_attempts);
        box.removeAllViews();
        List<Record> list = progress.recordsOf(qid, 50);
        if (list.isEmpty()) {
            box.setVisibility(View.GONE);
            return;
        }
        box.setVisibility(View.VISIBLE);

        TextView title = new TextView(this);
        title.setText(R.string.attempts_title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_body));
        title.setTypeface(null, Typeface.BOLD);
        box.addView(title);

        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        int gap = getResources().getDimensionPixelSize(R.dimen.gap_row);
        for (Record rec : list) {
            View row = getLayoutInflater().inflate(R.layout.row_record, box, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = gap;
            row.setLayoutParams(lp);
            row.setPadding(0, row.getPaddingTop(), 0, row.getPaddingBottom());
            ((TextView) row.findViewById(R.id.tv_when)).setText(fmt.format(new Date(rec.ts)));
            TextView ok = row.findViewById(R.id.tv_ok);
            ok.setText(rec.ok ? R.string.correct : R.string.wrong);
            ok.setTextColor(getColor(rec.ok ? R.color.correct : R.color.wrong));
            row.findViewById(R.id.tv_qid).setVisibility(View.GONE);
            ((TextView) row.findViewById(R.id.tv_meta)).setText(recordMeta(rec));
            box.addView(row);
        }
    }

    private void closeQuestion() {
        cancelTimer();
        onQuestion = false;
        if (SRC_BANK.equals(entrySource)) showTab(Tab.BANK);
        else if (SRC_HISTORY.equals(entrySource)) showTab(Tab.HISTORY);
        else showTab(Tab.OVERVIEW);
    }

    // ==================== 璁剧疆 ====================

    private void showSettings() {
        final String[] keys = {AUX_LOOSE, AUX_STANDARD, AUX_CHALLENGE};
        String[] labels = {
                getString(R.string.aux_loose),
                getString(R.string.aux_standard),
                getString(R.string.aux_challenge)
        };
        int checked = 1;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(auxTier)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.aux_tier)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    auxTier = keys[which];
                    progress.auxTier = auxTier;
                    store.save(progress);
                    dialog.dismiss();
                })
                .setMessage(getString(R.string.version_info, repo.bankVersion()))
                .show();
    }

    private String auxLabel() {
        return auxLabelOf(auxTier);
    }

    private String auxLabelOf(String aux) {
        if (AUX_LOOSE.equals(aux)) return getString(R.string.aux_loose);
        if (AUX_CHALLENGE.equals(aux)) return getString(R.string.aux_challenge);
        return getString(R.string.aux_standard);
    }

    /** 记录行次要信息：强度；挑战超时则附「超时」。不展示耗时。 */
    private String recordMeta(Record rec) {
        String meta = auxLabelOf(rec.aux);
        if (!rec.ok && isTimeoutMs(rec)) {
            meta = meta + "  ·  " + getString(R.string.timeout_mark);
        }
        return meta;
    }

    @Override
    public void onBackPressed() {
        if (onQuestion) {
            closeQuestion();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        cancelTimer();
        super.onDestroy();
    }

    private void applySystemBarInsets(View root) {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                            | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(root);
    }
}
