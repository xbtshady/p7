package com.xbtshady.p7.engine;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import com.xbtshady.p7.model.Question;

/**
 * 架构文档 §4.4 渲染分派。
 *
 * <p>renderer 只吃 {@link Question}，不碰资产、不碰进度、不写存储。
 * 加题型 = 加一个 renderer + 一个 type 字符串，现有代码和题库格式都不用改。
 */
public interface QuestionRenderer {

    /** 题面 */
    void renderPrompt(Question q, ViewGroup box, LayoutInflater inflater);

    /** 作答控件 */
    void renderInput(Question q, ViewGroup box, LayoutInflater inflater);

    /** 取玩家填的值；未填满时返回部分值或 null（调用方按"有空格"处理） */
    Object readAnswer(ViewGroup box);

    /** 提交后展示的完整答案 */
    void renderSolution(Question q, ViewGroup box, LayoutInflater inflater);

    /**
     * 锁死作答控件。
     * 挑战档倒计时归零后：已填内容<b>原样保留</b>（不清空，玩家能对照答案看自己推到哪），
     * 但控件置为不可编辑（前端设计 §4）。
     */
    void setLocked(ViewGroup box, boolean locked);
}
