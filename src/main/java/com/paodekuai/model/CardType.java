package com.paodekuai.model;

/**
 * 跑得快牌型枚举
 */
public enum CardType {
    PASS("过牌", 0),
    SINGLE("单张", 1),
    PAIR("对子", 2),
    TRIPLE("三张", 3),
    TRIPLE_PLUS_ONE("三带一", 4),
    TRIPLE_PLUS_PAIR("三带二", 5),
    STRAIGHT("顺子", 5),
    CONSECUTIVE_PAIRS("连对", 4),
    AIRPLANE("飞机不带", 6),
    AIRPLANE_PLUS_SINGLES("飞机带单", 8),
    AIRPLANE_PLUS_PAIRS("飞机带对", 10),
    FOUR_PLUS_TWO("四带二", 6),
    FOUR_PLUS_TWO_PAIRS("四带两对", 8),
    BOMB("炸弹", 4);

    private final String description;
    private final int minCards;

    CardType(String description, int minCards) {
        this.description = description;
        this.minCards = minCards;
    }

    public String getDescription() {
        return description;
    }

    public int getMinCards() {
        return minCards;
    }
}
