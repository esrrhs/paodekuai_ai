package com.paodekuai.ai;

import com.paodekuai.model.Move;

/** 候选动作及其策略概率 */
public record MoveScore(Move move, double probability) {
}
