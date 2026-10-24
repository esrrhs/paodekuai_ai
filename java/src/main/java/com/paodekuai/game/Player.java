package com.paodekuai.game;

import com.paodekuai.model.Hand;

/** 玩家实体:id / 名字 / 手牌 / 是否 AI */
public final class Player {

    private final int id;
    private final String name;
    private final Hand hand = new Hand();
    private final boolean ai;

    public Player(int id, String name, boolean ai) {
        this.id = id;
        this.name = name;
        this.ai = ai;
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
        return ai;
    }

    public int getCardCount() {
        return hand.getTotal();
    }

    public boolean hasWon() {
        return hand.isEmpty();
    }

    @Override
    public String toString() {
        return "p" + id + "(" + name + ")";
    }
}
