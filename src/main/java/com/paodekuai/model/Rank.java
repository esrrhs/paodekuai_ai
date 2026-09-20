package com.paodekuai.model;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 跑得快卡牌点数枚举 (3最小, 2最大)
 */
public enum Rank {
    THREE(3, "3"),
    FOUR(4, "4"),
    FIVE(5, "5"),
    SIX(6, "6"),
    SEVEN(7, "7"),
    EIGHT(8, "8"),
    NINE(9, "9"),
    TEN(10, "10"),
    JACK(11, "J"),
    QUEEN(12, "Q"),
    KING(13, "K"),
    ACE(14, "A"),
    TWO(15, "2");

    private final int value;
    private final String symbol;

    private static final Map<Integer, Rank> VALUE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(Rank::getValue, r -> r));

    private static final Map<String, Rank> SYMBOL_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(Rank::getSymbol, r -> r));

    Rank(int value, String symbol) {
        this.value = value;
        this.symbol = symbol;
    }

    public int getValue() {
        return value;
    }

    public String getSymbol() {
        return symbol;
    }

    public static Rank fromValue(int value) {
        Rank rank = VALUE_MAP.get(value);
        if (rank == null) {
            throw new IllegalArgumentException("Unknown card rank value: " + value);
        }
        return rank;
    }

    public static Rank fromSymbol(String symbol) {
        Rank rank = SYMBOL_MAP.get(symbol.trim().toUpperCase());
        if (rank == null) {
            throw new IllegalArgumentException("Unknown card rank symbol: " + symbol);
        }
        return rank;
    }

    @Override
    public String toString() {
        return symbol;
    }
}
