package com.paodekuai.model;

import java.util.List;

/**
 * 出牌动作:牌型 + 主牌点数下标 + 链长 + 具体牌张 + 出牌人,不可变。
 * 与训练侧 MoveInfo 同构:mainRank 为牌型主牌的点数下标(链型取最低点),
 * len 为链长(非链型恒 1)。空牌列表表示"过"。
 */
public final class Move {

    private final CardType type;
    private final int mainRank;
    private final int len;
    private final List<Rank> cards;
    private final int playerId;

    private Move(CardType type, int mainRank, int len, List<Rank> cards, int playerId) {
        this.type = type;
        this.mainRank = mainRank;
        this.len = len;
        this.cards = List.copyOf(cards);
        this.playerId = playerId;
    }

    public static Move pass(int playerId) {
        return new Move(CardType.PASS, -1, 0, List.of(), playerId);
    }

    public static Move of(CardType type, int mainRank, int len, List<Rank> cards, int playerId) {
        return new Move(type, mainRank, Math.max(len, 1), cards, playerId);
    }

    public boolean isPass() {
        return type == CardType.PASS;
    }

    public CardType getType() {
        return type;
    }

    /** 主牌点数下标 0..12(链型取最低点);过牌为 -1 */
    public int getMainRank() {
        return mainRank;
    }

    /** 链长;非链型恒 1 */
    public int getLen() {
        return len;
    }

    public List<Rank> getCards() {
        return cards;
    }

    public int getCardCount() {
        return cards.size();
    }

    public int getPlayerId() {
        return playerId;
    }

    /**
     * 训练侧 pdk::beats 语义:炸弹压一切非炸弹、炸弹比点数;
     * 其余必须同类型同长度,链型再比点数。
     */
    public boolean canBeat(Move other) {
        if (other == null || other.isPass()) {
            return true;
        }
        if (type == CardType.BOMB) {
            return other.type != CardType.BOMB || mainRank > other.mainRank;
        }
        if (other.type == CardType.BOMB) {
            return false;
        }
        if (type != other.type) {
            return false;
        }
        if (isChainType(type)) {
            return len == other.len && mainRank > other.mainRank;
        }
        return mainRank > other.mainRank;
    }

    public static boolean isChainType(CardType type) {
        return type == CardType.STRAIGHT
                || type == CardType.PAIR_STRAIGHT
                || type == CardType.TRIPLE_STRAIGHT
                || type == CardType.PLANE_SOLO
                || type == CardType.PLANE_PAIR;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Move other)) {
            return false;
        }
        return type == other.type && mainRank == other.mainRank && len == other.len
                && cards.equals(other.cards);
    }

    @Override
    public int hashCode() {
        return (31 * type.hashCode() + mainRank) * 31 + len;
    }

    @Override
    public String toString() {
        if (isPass()) {
            return "PASS";
        }
        StringBuilder sb = new StringBuilder(type.getLabel()).append(':');
        for (Rank r : cards) {
            sb.append(r.getSymbol());
        }
        return sb.toString();
    }
}
