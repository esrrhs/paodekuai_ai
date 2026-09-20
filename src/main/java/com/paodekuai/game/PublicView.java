package com.paodekuai.game;

import com.paodekuai.model.Hand;
import com.paodekuai.model.Move;
import com.paodekuai.model.Rank;
import com.paodekuai.rules.Deck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 玩家的观察视角 (不完全信息，严格隐藏对手私有手牌)
 */
public class PublicView {
    private final int viewingPlayerId;
    private final Hand myHand;
    private final int[] cardCounts; // 3位玩家当前剩余手牌张数
    private final List<Move> moveHistory;
    private final Move lastMove;
    private final int lastMovePlayerId;
    private final int activePlayerId;
    private final int passCount;
    private final boolean mustBeat;

    public PublicView(int viewingPlayerId, Hand myHand, int[] cardCounts, List<Move> moveHistory,
                      Move lastMove, int lastMovePlayerId, int activePlayerId, int passCount, boolean mustBeat) {
        this.viewingPlayerId = viewingPlayerId;
        this.myHand = myHand;
        this.cardCounts = cardCounts.clone();
        this.moveHistory = Collections.unmodifiableList(moveHistory);
        this.lastMove = lastMove;
        this.lastMovePlayerId = lastMovePlayerId;
        this.activePlayerId = activePlayerId;
        this.passCount = passCount;
        this.mustBeat = mustBeat;
    }

    public int getViewingPlayerId() {
        return viewingPlayerId;
    }

    public Hand getMyHand() {
        return myHand;
    }

    public int getCardCount(int playerId) {
        return cardCounts[playerId];
    }

    public int[] getCardCounts() {
        return cardCounts.clone();
    }

    public List<Move> getMoveHistory() {
        return moveHistory;
    }

    public Move getLastMove() {
        return lastMove;
    }

    public int getLastMovePlayerId() {
        return lastMovePlayerId;
    }

    public int getActivePlayerId() {
        return activePlayerId;
    }

    public int getPassCount() {
        return passCount;
    }

    public boolean isMustBeat() {
        return mustBeat;
    }

    /**
     * 计算所有对手当前可能持有的未知牌池
     * 未知牌池 = 48张初始牌库 - 我的私有手牌 - 场上已打出的所有历史牌
     */
    public List<Rank> computeUnseenCards() {
        List<Rank> deck = Deck.createStandard48Cards();

        // 移除自己手里的牌
        for (Rank r : myHand.getCards()) {
            deck.remove(r);
        }

        // 移除历史上已经打出的牌
        for (Move m : moveHistory) {
            if (!m.isPass()) {
                for (Rank r : m.getCards()) {
                    deck.remove(r);
                }
            }
        }

        return deck;
    }
}
