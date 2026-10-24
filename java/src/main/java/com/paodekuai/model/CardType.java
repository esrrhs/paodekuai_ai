package com.paodekuai.model;

/**
 * 跑得快牌型枚举,与训练侧 PerfectPaoCPP 的 MoveType (pdk/moves.h) 一一对应。
 * 无王炸、无四带二;连对与三顺最少 2 组,顺子最少 5 张。
 */
public enum CardType {
    PASS(0, "过"),
    SINGLE(1, "单张"),
    PAIR(2, "对子"),
    TRIPLE(3, "三张"),
    BOMB(4, "炸弹"),
    TRIPLE_TWO(5, "三带二"),
    STRAIGHT(6, "顺子"),
    PAIR_STRAIGHT(7, "连对"),
    TRIPLE_STRAIGHT(8, "三顺"),
    PLANE_SOLO(9, "飞机带单"),
    PLANE_PAIR(10, "飞机带对"),
    /** 非法牌型,仅用于检测结果,与训练侧 MT_WRONG 对应 */
    WRONG(11, "无效");

    /** 链型最小长度;2 不入链 */
    public static final int MIN_STRAIGHT = 5;
    public static final int MIN_PAIR_CHAIN = 2;
    public static final int MIN_PLANE = 2;

    private final int code;
    private final String label;

    CardType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 与训练侧 MoveType 数值一致 */
    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }
}
