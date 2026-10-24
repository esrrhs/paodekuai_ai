package com.paodekuai.model;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 跑得快卡牌点数枚举 (3最小, 2最大)。
 * 无花色;符号表与训练侧 PerfectPaoCPP 一致:3456789TJQKA2 (T 为 10)。
 */
public enum Rank {
    THREE(3, "3"),
    FOUR(4, "4"),
    FIVE(5, "5"),
    SIX(6, "6"),
    SEVEN(7, "7"),
    EIGHT(8, "8"),
    NINE(9, "9"),
    TEN(10, "T"),
    JACK(11, "J"),
    QUEEN(12, "Q"),
    KING(13, "K"),
    ACE(14, "A"),
    TWO(15, "2");

    /** 点数总数,即 13 种点数 */
    public static final int RANKS = 13;
    /** 能参与顺子/连对/三顺的点数个数 (3..A,2 不入链) */
    public static final int CHAIN_RANKS = 12;

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

    /** 点数值 3..15 */
    public int getValue() {
        return value;
    }

    /** 单字符符号,索引 = getValue() - 3 */
    public String getSymbol() {
        return symbol;
    }

    /** 特征矩阵用的点数下标 0..12 */
    public int getIndex() {
        return value - 3;
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
