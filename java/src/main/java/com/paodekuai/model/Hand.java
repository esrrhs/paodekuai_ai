package com.paodekuai.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 手牌/牌集:13 维点数计数向量,与训练侧 pdk::CardSet 同构。
 * 空集表示"过"。下标即 Rank.getIndex()(0..12)。
 */
public final class Hand {

    private final int[] counts = new int[Rank.RANKS];
    private int total;

    public Hand() {
    }

    public Hand(Hand other) {
        System.arraycopy(other.counts, 0, this.counts, 0, Rank.RANKS);
        this.total = other.total;
    }

    public Hand copy() {
        return new Hand(this);
    }

    public void add(Rank rank, int n) {
        counts[rank.getIndex()] += n;
        total += n;
    }

    public void add(Rank rank) {
        add(rank, 1);
    }

    public void addIndex(int rankIndex, int n) {
        counts[rankIndex] += n;
        total += n;
    }

    public void add(Hand other) {
        for (int r = 0; r < Rank.RANKS; ++r) {
            counts[r] += other.counts[r];
        }
        total += other.total;
    }

    public void sub(Hand other) {
        for (int r = 0; r < Rank.RANKS; ++r) {
            counts[r] -= other.counts[r];
        }
        total -= other.total;
    }

    /** 是否包含 other 的所有牌 */
    public boolean contains(Hand other) {
        for (int r = 0; r < Rank.RANKS; ++r) {
            if (counts[r] < other.counts[r]) {
                return false;
            }
        }
        return true;
    }

    public boolean contains(Move move) {
        for (Rank r : move.getCards()) {
            if (counts[r.getIndex()] <= 0) {
                return false;
            }
        }
        return true;
    }

    public void play(Move move) {
        for (Rank r : move.getCards()) {
            counts[r.getIndex()]--;
        }
        total -= move.getCards().size();
    }

    public void unplay(Move move) {
        for (Rank r : move.getCards()) {
            counts[r.getIndex()]++;
        }
        total += move.getCards().size();
    }

    public int count(Rank rank) {
        return counts[rank.getIndex()];
    }

    public int countIndex(int rankIndex) {
        return counts[rankIndex];
    }

    public int getTotal() {
        return total;
    }

    public boolean isEmpty() {
        return total == 0;
    }

    public void clear() {
        Arrays.fill(counts, 0);
        total = 0;
    }

    /** 展开成单张列表(点数升序) */
    public List<Rank> getCards() {
        List<Rank> cards = new ArrayList<>(total);
        for (int r = 0; r < Rank.RANKS; ++r) {
            for (int k = 0; k < counts[r]; ++k) {
                cards.add(Rank.fromValue(r + 3));
            }
        }
        return cards;
    }

    /** 与训练侧 CardSet::str 一致:点数升序,字母表 3456789TJQKA2;空集返回空串 */
    public String toCardString() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < Rank.RANKS; ++r) {
            for (int k = 0; k < counts[r]; ++k) {
                sb.append(Rank.fromValue(r + 3).getSymbol());
            }
        }
        return sb.toString();
    }

    /** 解析 "34455A2" 风格的牌面串 */
    public static Hand parse(String s) {
        Hand hand = new Hand();
        for (int i = 0; i < s.length(); ++i) {
            hand.add(Rank.fromSymbol(String.valueOf(s.charAt(i))));
        }
        return hand;
    }

    /** 稳定的 64 位指纹:每点数 3 位,共 39 位;与训练侧 CardSet::key 一致 */
    public long key() {
        long k = 0;
        for (int r = 0; r < Rank.RANKS; ++r) {
            k |= (long) counts[r] << (3 * r);
        }
        return k;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Hand other)) {
            return false;
        }
        return total == other.total && Arrays.equals(counts, other.counts);
    }

    @Override
    public int hashCode() {
        return 31 * Integer.hashCode(total) + Arrays.hashCode(counts);
    }

    @Override
    public String toString() {
        return toCardString();
    }
}
