package com.paodekuai.game;

import com.paodekuai.model.Hand;
import com.paodekuai.model.Move;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 玩家公开观察视角:只暴露该玩家可见的信息,绝不泄露对手私有手牌。
 * 策略网络的特征编码只从这里读取。
 */
public final class PublicView {

    private final int viewingPlayerId;
    private final Hand myHand;
    private final int[] cardCounts;      // 三家剩余张数(clone)
    private final List<Move> moveHistory; // 完整动作序列(含过),不可变
    private final Move[] lastMoves;      // 各家最近动作(含过)
    private final Move toBeat;           // 必须压过的牌;null = 自由出牌
    private final int lastPlayerId;      // 最近一个非过动作的玩家
    private final int activePlayerId;    // 当前行动玩家
    private final int bombCount;

    PublicView(int viewingPlayerId, Hand myHand, int[] cardCounts,
               List<Move> moveHistory, Move[] lastMoves, Move toBeat,
               int lastPlayerId, int activePlayerId, int bombCount) {
        this.viewingPlayerId = viewingPlayerId;
        this.myHand = myHand;
        this.cardCounts = cardCounts.clone();
        this.moveHistory = moveHistory;
        this.lastMoves = lastMoves.clone();
        this.toBeat = toBeat;
        this.lastPlayerId = lastPlayerId;
        this.activePlayerId = activePlayerId;
        this.bombCount = bombCount;
    }

    public int getViewingPlayerId() {
        return viewingPlayerId;
    }

    public Hand getMyHand() {
        return myHand;
    }

    public int[] getCardCounts() {
        return cardCounts.clone();
    }

    public int getCardCount(int playerId) {
        return cardCounts[playerId];
    }

    public List<Move> getMoveHistory() {
        return moveHistory;
    }

    /** 某家最近一次动作(含过;从未行动时为 PASS) */
    public Move getLastMoveOf(int playerId) {
        return lastMoves[playerId];
    }

    /** 必须压过的牌;null = 自由出牌 */
    public Move getToBeat() {
        return toBeat;
    }

    public int getLastPlayerId() {
        return lastPlayerId;
    }

    public int getActivePlayerId() {
        return activePlayerId;
    }

    public int getBombCount() {
        return bombCount;
    }

    /** 上家(相对视角) */
    public int prevSeat() {
        return (viewingPlayerId + GameState.NUM_PLAYERS - 1) % GameState.NUM_PLAYERS;
    }

    /** 下家(相对视角) */
    public int nextSeat() {
        return (viewingPlayerId + 1) % GameState.NUM_PLAYERS;
    }

    /** 自己视角的未知牌池:48 张 - 自己手牌 - 全场已打出 */
    public Hand computeUnseenCards() {
        Hand unseen = com.paodekuai.rules.Deck.fullDeck();
        unseen.sub(myHand);
        for (Move m : moveHistory) {
            if (!m.isPass()) {
                for (com.paodekuai.model.Rank r : m.getCards()) {
                    unseen.add(r, -1);
                }
            }
        }
        return unseen;
    }

    @Override
    public String toString() {
        return "PublicView{viewer=" + viewingPlayerId
                + ", hand=" + myHand
                + ", counts=" + Arrays.toString(cardCounts)
                + ", active=" + activePlayerId + "}";
    }
}
