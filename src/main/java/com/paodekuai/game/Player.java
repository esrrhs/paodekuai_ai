package com.paodekuai.game;

import com.paodekuai.model.Hand;

import java.util.Objects;

/**
 * 玩家实体
 */
public class Player {
    private final int id;
    private final String name;
    private final Hand hand;
    private final boolean isAi;

    public Player(int id, String name, Hand hand, boolean isAi) {
        this.id = id;
        this.name = name;
        this.hand = Objects.requireNonNull(hand, "hand cannot be null");
        this.isAi = isAi;
    }

    public Player copy() {
        return new Player(id, name, hand.copy(), isAi);
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Hand getHand() {
        return hand;
    }

    public boolean isAi() {
        return isAi;
    }

    public int getCardCount() {
        return hand.getTotalCards();
    }

    public boolean hasWon() {
        return hand.isEmpty();
    }

    @Override
    public String toString() {
        return String.format("%s (ID=%d, %d cards)", name, id, getCardCount());
    }
}
