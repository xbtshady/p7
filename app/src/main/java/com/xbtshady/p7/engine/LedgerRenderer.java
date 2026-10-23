package com.xbtshady.p7.engine;

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

/**
 * 账本题型。
 *
 * <p>题面：叙事逐句一段（每句一行，行间 8dp），最后一行是问题（前端设计 §3.2）。
 * <p>作答：一个数字输入框，右侧单位"元"（§3.4）。
 * <p>答案：一张四行小表（步骤 / 现金 / 持货），再列三行结论（§3.5）。
 */
public final class LedgerRenderer implements QuestionRenderer {

    private static final String TAG_VALUE = "value";

    @Override
    public void renderPrompt(Question q, ViewGroup box, LayoutInflater inflater) {
        box.removeAllViews();
        int gap = box.getContext().getResources().getDimensionPixelSize(R.dimen.gap_row);

        JSONArray narrative = q.narrative();
        if (narrative != null) {
            for (int i = 0; i < narrative.length(); i++) {
                String s = narrative.optString(i, "");
                if (s.isEmpty()) continue;
                addLine(box, s, gap, i > 0 ? gap : 0, true);
            }
        }

        // 最后一行是问题
        String question = "net".equals(q.ledgerQuestion())
                ? box.getContext().getString(R.string.q_net)
                : box.getContext().getString(R.string.q_total);
        addLine(box, question, 0, gap, true);
    }

    @Override
    public void renderInput(Question q, ViewGroup box, LayoutInflater inflater) {
        box.removeAllViews();

        LinearLayout row = new LinearLayout(box.getContext());
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        EditText et = new EditText(box.getContext());
        et.setTag(TAG_VALUE);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        et.setHint("0");
        et.setTextColor(box.getContext().getColor(R.color.text_primary));
        et.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                box.getContext().getResources().getDimension(R.dimen.text_body));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.width = box.getContext().getResources().getDimensionPixelSize(R.dimen.margin_h) * 8;
        et.setLayoutParams(lp);
        row.addView(et);

        TextView unit = new TextView(box.getContext());
        unit.setText(R.string.unit_yuan);
        unit.setTextColor(box.getContext().getColor(R.color.text_secondary));
        unit.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                box.getContext().getResources().getDimension(R.dimen.text_body));
        unit.setPadding(box.getContext().getResources().getDimensionPixelSize(R.dimen.gap_row), 0, 0, 0);
        row.addView(unit);

        box.addView(row);
    }

    @Override
    public Object readAnswer(ViewGroup box) {
        View v = box.findViewWithTag(TAG_VALUE);
        if (!(v instanceof EditText)) return null;
        String s = normalizeNumber(((EditText) v).getText().toString());
        if (s.isEmpty() || "-".equals(s) || "+".equals(s)) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            // 非数字视为未填（前端设计 §8）
            return null;
        }
    }

    /** §8：忽略空格、千分位、全角半角 */
    private static String normalizeNumber(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '０' && c <= '９') {
                sb.append((char) (c - '０' + '0'));       // 全角数字
            } else if (c == '－' || c == 'ー') {
                sb.append('-');                            // 全角减号
            } else if (c == '＋') {
                sb.append('+');
            } else if (c == ' ' || c == '　' || c == ',' || c == '，' || c == '、') {
                continue;                                  // 空格与千分位
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override
    public void renderSolution(Question q, ViewGroup box, LayoutInflater inflater) {
        box.removeAllViews();
        int gap = box.getContext().getResources().getDimensionPixelSize(R.dimen.gap_row);

        // 四行小表：步骤 / 现金 / 持货
        JSONArray ledger = q.solutionLedger();
        if (ledger != null) {
            for (int i = 0; i < ledger.length(); i++) {
                JSONObject r = ledger.optJSONObject(i);
                if (r == null) continue;
                String step = r.optString("step", "");
                int cash = r.optInt("cash", 0);
                int hold = r.optInt("hold", 0);
                addRow(box, step, String.valueOf(cash), String.valueOf(hold), gap);
            }
        }

        addDivider(box, gap);

        JSONArray lines = q.solutionLines();
        if (lines != null) {
            for (int i = 0; i < lines.length(); i++) {
                String s = lines.optString(i, "");
                if (s.isEmpty()) continue;
                addLine(box, s, gap, i == 0 ? 0 : gap, false);
            }
        }
    }

    @Override
    public void setLocked(ViewGroup box, boolean locked) {
        View v = box.findViewWithTag(TAG_VALUE);
        if (v instanceof EditText) {
            ((EditText) v).setEnabled(!locked);
            ((EditText) v).setFocusable(!locked);
            ((EditText) v).setFocusableInTouchMode(!locked);
        }
    }

    /** 表格行：左步骤、中现金、右持货 */
    private void addRow(ViewGroup box, String step, String cash, String hold, int gap) {
        LinearLayout row = new LinearLayout(box.getContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = gap;
        row.setLayoutParams(lp);
        row.setOrientation(LinearLayout.HORIZONTAL);

        TextView a = cell(box, step);
        a.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 3f));
        row.addView(a);

        TextView b = cell(box, cash);
        b.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(b);

        TextView c = cell(box, hold);
        c.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(c);

        box.addView(row);
    }

    private TextView cell(ViewGroup box, String text) {
        TextView tv = new TextView(box.getContext());
        tv.setText(text);
        tv.setTextColor(box.getContext().getColor(R.color.text_secondary));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                box.getContext().getResources().getDimension(R.dimen.text_secondary));
        tv.setLineSpacing(0, 1.5f);
        return tv;
    }

    private void addLine(ViewGroup box, String text, int bottomPadding, int topMargin, boolean primary) {
        TextView tv = new TextView(box.getContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        tv.setLayoutParams(lp);
        tv.setText(text);
        tv.setTextColor(box.getContext().getColor(primary ? R.color.text_primary : R.color.text_secondary));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                box.getContext().getResources().getDimension(R.dimen.text_body));
        tv.setLineSpacing(0, 1.5f);
        tv.setPadding(0, 0, 0, bottomPadding);
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
