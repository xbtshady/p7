package com.xbtshady.p7.engine;

import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.xbtshady.p7.R;
import com.xbtshady.p7.model.Question;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 真假话：经典「骑士与无赖」题面。
 * <pre>
 * 岛上的人只有两种：骑士总说真话，无赖总说假话。
 *
 * A 说：「…」
 * B 说：「…」
 *
 * 问：谁是骑士，谁是无赖？
 * </pre>
 */
public final class TruthRenderer implements QuestionRenderer {

    /** people: true=骑士，false=无赖 */
    public static final class Answer {
        public final Map<String, Boolean> people;

        public Answer(Map<String, Boolean> people) {
            this.people = people;
        }
    }

    private static final String TAG_CHOICE = "choice:";
    private final List<String> names = new ArrayList<>();

    @Override
    public void renderPrompt(Question q, ViewGroup box, LayoutInflater inflater) {
        box.removeAllViews();
        names.clear();

        JSONArray people = q.people();
        if (people == null) return;

        int gap = box.getContext().getResources().getDimensionPixelSize(R.dimen.gap_row);

        // 开场：世界观（固定，与经典题一致）
        addPara(box, box.getContext().getString(R.string.truth_intro), 0);

        // 陈述
        for (int i = 0; i < people.length(); i++) {
            JSONObject p = people.optJSONObject(i);
            if (p == null) continue;
            String name = p.optString("name", "");
            String says = p.optString("says", "");
            if (name.isEmpty()) continue;
            names.add(name);
            addPara(box, box.getContext().getString(R.string.truth_says_line, name, says), gap);
        }

        // 设问
        addPara(box, box.getContext().getString(R.string.truth_ask), gap * 2);
    }

    private void addPara(ViewGroup box, String text, int topMargin) {
        TextView tv = new TextView(box.getContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        tv.setLayoutParams(lp);
        tv.setText(text);
        tv.setTextColor(box.getContext().getColor(R.color.text_primary));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                box.getContext().getResources().getDimension(R.dimen.text_body));
        tv.setLineSpacing(0, 1.5f);
        box.addView(tv);
    }

    @Override
    public void renderInput(Question q, ViewGroup box, LayoutInflater inflater) {
        box.removeAllViews();
        int gap = box.getContext().getResources().getDimensionPixelSize(R.dimen.gap_row);
        boolean twoCol = names.size() >= 7;

        if (!twoCol) {
            for (String name : names) {
                box.addView(makePersonRow(box, name, gap, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            return;
        }

        for (int i = 0; i < names.size(); i += 2) {
            LinearLayout pair = new LinearLayout(box.getContext());
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.topMargin = gap;
            pair.setLayoutParams(plp);
            pair.setOrientation(LinearLayout.HORIZONTAL);

            View left = makePersonRow(box, names.get(i), 0, 0);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            left.setLayoutParams(llp);
            pair.addView(left);

            if (i + 1 < names.size()) {
                View right = makePersonRow(box, names.get(i + 1), 0, 0);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                rlp.setMarginStart(gap);
                right.setLayoutParams(rlp);
                pair.addView(right);
            }
            box.addView(pair);
        }
    }

    private View makePersonRow(ViewGroup box, String name, int topMargin, int width) {
        int gap = box.getContext().getResources().getDimensionPixelSize(R.dimen.gap_row);
        LinearLayout row = new LinearLayout(box.getContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                width == 0 ? ViewGroup.LayoutParams.MATCH_PARENT : width,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        if (topMargin > 0) lp.topMargin = topMargin;
        row.setLayoutParams(lp);
        row.setOrientation(LinearLayout.VERTICAL);

        TextView nameTv = new TextView(box.getContext());
        nameTv.setText(name);
        nameTv.setTextColor(box.getContext().getColor(R.color.text_primary));
        nameTv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                box.getContext().getResources().getDimension(R.dimen.text_body));
        row.addView(nameTv);

        LinearLayout choices = new LinearLayout(box.getContext());
        choices.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams clpOuter = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clpOuter.topMargin = gap / 2;
        choices.setLayoutParams(clpOuter);

        int btnH = box.getContext().getResources().getDimensionPixelSize(R.dimen.button_h);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, btnH, 1f);

        TextView t = makeChoice(box, name, true);
        t.setLayoutParams(clp);
        choices.addView(t);

        TextView f = makeChoice(box, name, false);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, btnH, 1f);
        flp.setMarginStart(gap);
        f.setLayoutParams(flp);
        choices.addView(f);

        row.addView(choices);
        return row;
    }

    private TextView makeChoice(ViewGroup box, String name, boolean value) {
        TextView tv = new TextView(box.getContext());
        tv.setTag(TAG_CHOICE + name + ":" + value);
        tv.setText(value ? R.string.choice_true : R.string.choice_false);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundResource(R.drawable.bg_choice);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                box.getContext().getResources().getDimension(R.dimen.text_body));
        tv.setTextColor(box.getContext().getResources().getColorStateList(R.color.choice_text, null));
        tv.setOnClickListener(v -> {
            View other = box.findViewWithTag(TAG_CHOICE + name + ":" + !value);
            if (other != null) other.setSelected(false);
            v.setSelected(true);
        });
        return tv;
    }

    @Override
    public Object readAnswer(ViewGroup box) {
        Map<String, Boolean> people = new HashMap<>();
        for (String name : names) {
            View t = box.findViewWithTag(TAG_CHOICE + name + ":true");
            View f = box.findViewWithTag(TAG_CHOICE + name + ":false");
            if (t != null && t.isSelected()) people.put(name, true);
            else if (f != null && f.isSelected()) people.put(name, false);
        }
        return new Answer(people);
    }

    @Override
    public void renderSolution(Question q, ViewGroup box, LayoutInflater inflater) {
        box.removeAllViews();
        int gap = box.getContext().getResources().getDimensionPixelSize(R.dimen.gap_row);

        JSONObject truth = q.answerTruth();
        if (truth != null && !names.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) sb.append("　");
                boolean knight = truth.optBoolean(names.get(i));
                sb.append(names.get(i)).append(knight ? "骑士" : "无赖");
            }
            addPara(box, sb.toString(), 0);
        }

        if (!q.solutionSummary().isEmpty()) {
            addPara(box, q.solutionSummary(), gap);
        }

        JSONArray lines = q.solutionLines();
        if (lines != null) {
            for (int i = 0; i < lines.length(); i++) {
                String s = lines.optString(i, "");
                if (!s.isEmpty()) addPara(box, s, gap);
            }
        }
    }

    @Override
    public void setLocked(ViewGroup box, boolean locked) {
        for (String name : names) {
            for (boolean v : new boolean[]{true, false}) {
                View c = box.findViewWithTag(TAG_CHOICE + name + ":" + v);
                if (c != null) {
                    c.setEnabled(!locked);
                    c.setClickable(!locked);
                }
            }
        }
    }
}
