package com.paodekuai.ai;

import com.paodekuai.model.Move;

import java.util.List;

/** 决策产出:选中动作 + 按概率降序的候选表 + 耗时 */
public record AiDecision(Move selectedMove, List<MoveScore> moveScores,
                         long durationMillis) {

    public List<MoveScore> topMoves(int n) {
        return moveScores.size() <= n ? moveScores : moveScores.subList(0, n);
    }
}
