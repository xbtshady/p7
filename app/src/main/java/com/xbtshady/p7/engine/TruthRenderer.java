package com.xbtshady.p7.engine;

import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
 * 真假话题型。
 *
 * <p>题面：每人一行"名：台词"，约束独立一行加粗（前端设计 §3.2）。
 * <p>作答：每人一行两个并排方框 真/假（单选）；末行"撒谎人数"+数字输入 0–6（§3.4）。
 */
public final class TruthRenderer implements QuestionRenderer {

    /** 玩家填的结果。people 为 null 表示未填，liars 为 null 表示人数没填。 */
    public static final class Answer {
        public final Map<String, Boolean> people;
        public final Integer liars;

        public Answer(Map<String, Boolean> people, Integer liars) {
            this.people = people;
            this.liars = liars;
        }

        /** 是否所有人都填了 */
        public boolean allFilled(List<String> names) {
            if (people == null) return false;
            for (String n : names) {
                if (!people.containsKey(n)) return false;
            }
            return true;
        }
    }

    private static final String TAG_CHOICE = "choice:";
    private static final String TAG_LIARS = "liars";

    private final List<String> names = new ArrayList<>();

    @Override
    public void renderPrompt(Question q, ViewGroup box, LayoutInflater inflater) {
        box.removeAllViews();
        names.clear();

        JSONArray people = q.people();
        if (people == null) return;

        for (int i = 0; i < people.length(); i++) {
            JSONObject p = people.optJSONObject(i);
            if (p == null) continue;
            String name = p.optString("name", "");
            String says = p.optString("says", "");
            if (name.isEmpty()) continue;
            names.add(name);

            TextView tv = new TextView(box.getContext());
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tv.setText(name + "：" + says);
            tv.setTextColor(box.getContext().getColor(R.color.text_primary));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    box.getContext().getResources().getDimension(R.dimen.text_body));
            tv.setLineSpacing(0, 1.5f);
            if (i > 0) {
                ((LinearLayout.LayoutParams) tv.getLayoutParams()).topMargin =
                        box.getContext().getResources().getDimensionPixelSize(R.dimen.gap_row);
            }
            box.addView(tv);
        }

        // 约束独立一行、加粗
        String constraint = q.constraint();
        if (!constraint.isEmpty()) {
            TextView tv = new TextView(box.getContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = box.getContext().getResources().getDimensionPixelSize(R.dimen.gap_row);
            tv.setLayoutParams(lp);
            tv.setText(constraint);
            tv.setTypeface(null, Typeface.BOLD);
            tv.setTextColor(box.getContext().getColor(R.color.text_primary));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    box.getContext().getResources().getDimension(R.dimen.text_body));
            box.addView(tv);
        }
    }

    @Override
    public void renderInput(Question q, ViewGroup box, LayoutInflater inflater) {
        box.removeAllViews();
        int gap = box.getContext().getResources().getDimensionPixelSize(R.dimen.gap_row);

        for (String name : names) {
            LinearLayout row = new LinearLayout(box.getContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = gap;
            row.setLayoutParams(lp);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView nameTv = new TextView(box.getContext());
            nameTv.setText(name);
            nameTv.setTextColor(box.getContext().getColor(R.color.text_primary));
            nameTv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    box.getContext().getResources().getDimension(R.dimen.text_body));
            nameTv.setLayoutParams(new LinearLayout.LayoutParams(
                    box.getContext().getResources().getDimensionPixelSize(R.dimen.margin_h) * 3,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(nameTv);

            // 真 / 假 两个并排方框（前端设计 §7：选中填强调色）
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    box.getContext().getResources().getDimensionPixelSize(R.dimen.button_h) * 2,
                    box.getContext().getResources().getDimensionPixelSize(R.dimen.button_h));
            clp.setMarginStart(gap);

            TextView t = makeChoice(box, name, true);
            t.setLayoutParams(clp);
            row.addView(t);

            TextView f = makeChoice(box, name, false);
            f.setLayoutParams(clp);
            row.addView(f);

            box.addView(row);
        }

        // 末行：撒谎人数
        LinearLayout liarRow = new LinearLayout(box.getContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = gap;
        liarRow.setLayoutParams(lp);
        liarRow.setOrientation(LinearLayout.HORIZONTAL);
        liarRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(box.getContext());
        label.setText(R.string.liars_count);
        label.setTextColor(box.getContext().getColor(R.color.text_primary));
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                box.getContext().getResources().getDimension(R.dimen.text_body));
        liarRow.addView(label);

        EditText et = new EditText(box.getContext());
        et.setTag(TAG_LIARS);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("0");
        et.setTextColor(box.getContext().getColor(R.color.text_primary));
        et.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                box.getContext().getResources().getDimension(R.dimen.text_body));
        et.setPadding(gap, 0, gap, 0);
        liarRow.addView(et);

        box.addView(liarRow);
    }

    private TextView makeChoice(ViewGroup box, String name, boolean value) {
        TextView tv = new TextView(box.getContext());
        tv.setTag(TAG_CHOICE + name + ":" + value);
        tv.setText(value ? R.string.choice_true : R.string.choice_false);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundResource(R.drawable.bg_choice);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                box.getContext().getResources().getDimension(R.dimen.text_body));
        tv.setTextColor(box.getContext().getColor(R.color.text_primary));
        // 选中态切换：同一人的另一格取消选中
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

        Integer liars = null;
        View lv = box.findViewWithTag(TAG_LIARS);
        if (lv instanceof EditText) {
            String s = ((EditText) lv).getText().toString().trim();
            if (!s.isEmpty()) {
                try {
                    liars = Integer.parseInt(s);
                } catch (NumberFormatException ignored) {
                    // 非数字视为未填（前端设计 §8）
                }
            }
        }
        return new Answer(people, liars);
    }

    @Override
    public void renderSolution(Question q, ViewGroup box, LayoutInflater inflater) {
        box.removeAllViews();
        int gap = box.getContext().getResources().getDimensionPixelSize(R.dimen.gap_row);

        // 一行每人结论
        JSONObject truth = q.answerTruth();
        if (truth != null && !names.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) sb.append("　");
                sb.append(names.get(i)).append(truth.optBoolean(names.get(i)) ? "真" : "假");
            }
            addLine(box, sb.toString(), gap, 0);
        }

        // 一段说明
        if (!q.solutionSummary().isEmpty()) {
            addLine(box, q.solutionSummary(), gap, gap);
            addDivider(box, gap);
        }

        JSONArray lines = q.solutionLines();
        if (lines != null) {
            for (int i = 0; i < lines.length(); i++) {
                String s = lines.optString(i, "");
                if (!s.isEmpty()) addLine(box, s, gap, 0);
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
        View lv = box.findViewWithTag(TAG_LIARS);
        if (lv instanceof EditText) {
            ((EditText) lv).setEnabled(!locked);
            ((EditText) lv).setFocusable(!locked);
            ((EditText) lv).setFocusableInTouchMode(!locked);
        }
    }

    private void addLine(ViewGroup box, String text, int gap, int topMargin) {
        TextView tv = new TextView(box.getContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        tv.setLayoutParams(lp);
        tv.setText(text);
        tv.setTextColor(box.getContext().getColor(R.color.text_secondary));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                box.getContext().getResources().getDimension(R.dimen.text_body));
        tv.setLineSpacing(0, 1.5f);
        tv.setPadding(0, 0, 0, gap);
        box.addView(tv);
    }

    private void addDivider(ViewGroup box, int gap) {
        View v = new View(box.getContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = gap;
        lp.bottomMargin = gap;
        v.setLayoutParams(lp);
        v.setBackgroundColor(box.getContext().getColor(R.color.divider));
        box.addView(v);
    }
}
