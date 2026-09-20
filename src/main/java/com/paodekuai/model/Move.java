package com.paodekuai.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 出牌动作 (包含牌型、主权值、牌列表、出牌玩家等)
 */
public final class Move {
    private final CardType type;
    private final int mainRank; // 用于同类型比大小的主权值 (例如顺子最大牌，三带中三张的点数，炸弹点数等)
    private final List<Rank> cards; // 出牌包含的具体点数列表 (排序后)
    private final int playerId; // 出牌玩家编号 (0, 1, 2)

    private Move(CardType type, int mainRank, List<Rank> cards, int playerId) {
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.mainRank = mainRank;
        List<Rank> copy = new ArrayList<>(cards);
        Collections.sort(copy, (a, b) -> Integer.compare(a.getValue(), b.getValue()));
        this.cards = Collections.unmodifiableList(copy);
        this.playerId = playerId;
    }

    public static Move pass(int playerId) {
        return new Move(CardType.PASS, 0, List.of(), playerId);
    }

    public static Move of(CardType type, int mainRank, List<Rank> cards, int playerId) {
        return new Move(type, mainRank, cards, playerId);
    }

    public CardType getType() {
        return type;
    }

    public int getMainRank() {
        return mainRank;
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

    public boolean isPass() {
        return type == CardType.PASS;
    }

    public boolean isBomb() {
        return type == CardType.BOMB;
    }

    /**
     * 判断当前出牌能否压过 other
     */
    public boolean canBeat(Move other) {
        if (other == null || other.isPass()) {
            return !this.isPass();
        }
        if (this.isPass()) {
            return false;
        }

        // 炸弹压制普通牌型
        if (this.isBomb() && !other.isBomb()) {
            return true;
        }

        // 炸弹对比炸弹
        if (this.isBomb() && other.isBomb()) {
            return this.mainRank > other.mainRank;
        }

        // 普通牌型压制：牌型必须完全一致，且张数必须相同
        if (this.type == other.type && this.getCardCount() == other.getCardCount()) {
            return this.mainRank > other.mainRank;
        }

        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Move move)) return false;
        return mainRank == move.mainRank && type == move.type && Objects.equals(cards, move.cards);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, mainRank, cards);
    }

    public String toCardString() {
        if (isPass()) {
            return "PASS";
        }
        return cards.stream().map(Rank::getSymbol).collect(Collectors.joining(","));
    }

    @Override
    public String toString() {
        if (isPass()) {
            return String.format("[P%d: PASS]", playerId);
        }
        return String.format("[P%d: %s (%s, main=%s)]",
                playerId, toCardString(), type.getDescription(), Rank.fromValue(mainRank).getSymbol());
    }
}
