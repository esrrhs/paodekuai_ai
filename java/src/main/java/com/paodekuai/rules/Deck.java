package com.paodekuai.rules;

import com.paodekuai.model.Hand;
import com.paodekuai.model.Rank;

import java.util.List;
import java.util.Random;

/**
 * 48 张无花色牌:3..K 各 4 张、A 3 张、2 1 张;三人各 16 张,无底牌。
 * 发牌与首出者规则与训练侧 pdk::Game::deal 一致:
 * 从场上 4 张 3 里随机指定一张,其持有者先出。
 */
public final class Deck {

    public static final int TOTAL_CARDS = 48;
    public static final int CARDS_PER_PLAYER = 16;
    public static final int NUM_PLAYERS = 3;

    /** 与训练侧 Rng 相同的 splitmix64,保证同种子发牌逐位一致 */
    public static final class Rng {
        private static final long GOLDEN = 0x9e3779b97f4a7c15L;
        private long state;

        public Rng(long seed) {
            this.state = seed != 0 ? seed : GOLDEN;
        }

        public long nextU64() {
            long z = (state += GOLDEN);
            z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
            z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
            return z ^ (z >>> 31);
        }

        /** [0, n) 均匀整数 */
        public int below(int n) {
            return (int) Long.remainderUnsigned(nextU64(), n);
        }
    }

    /** 一局发牌结果:三副手牌 + 首出座位 */
    public record Deal(List<Hand> hands, int leader) {
    }

    private Deck() {
    }

    /** 训练侧牌序:3..K 各 4 张、A x3、2 x1,再 Fisher-Yates 洗牌 */
    public static int[] shuffledDeck(Deck.Rng rng) {
        int[] deck = new int[TOTAL_CARDS];
        int i = 0;
        for (int r = 0; r <= 10; ++r) {          // 3..K 点数下标 0..10
            for (int k = 0; k < 4; ++k) {
                deck[i++] = r;
            }
        }
        for (int k = 0; k < 3; ++k) {
            deck[i++] = 11;                       // A x3
        }
        deck[i++] = 12;                           // 2 x1
        for (int j = TOTAL_CARDS - 1; j > 0; --j) {
            int k = rng.below(j + 1);
            int tmp = deck[j];
            deck[j] = deck[k];
            deck[k] = tmp;
        }
        return deck;
    }

    /** 与训练侧 Game::deal 一致的发牌与首出指定 */
    public static Deal deal(Deck.Rng rng) {
        int[] deck = shuffledDeck(rng);
        Hand[] hands = new Hand[NUM_PLAYERS];
        for (int s = 0; s < NUM_PLAYERS; ++s) {
            hands[s] = new Hand();
            for (int i = s * CARDS_PER_PLAYER; i < (s + 1) * CARDS_PER_PLAYER; ++i) {
                hands[s].addIndex(deck[i], 1);
            }
        }
        int pick = rng.below(4);
        int acc = 0;
        int leader = NUM_PLAYERS - 1;
        for (int s = 0; s < NUM_PLAYERS; ++s) {
            acc += hands[s].countIndex(0);        // 点数 3 的张数
            if (pick < acc) {
                leader = s;
                break;
            }
        }
        return new Deal(List.of(hands[0], hands[1], hands[2]), leader);
    }

    /** 便捷入口:用 java.util.Random 驱动(服务端用,不要求与训练侧同种子) */
    public static Deal deal(Random random) {
        long seed = random.nextLong();
        return deal(new Deck.Rng(seed));
    }

    /** 完整 48 张牌库 */
    public static Hand fullDeck() {
        Hand deck = new Hand();
        for (int r = 0; r <= 10; ++r) {
            deck.addIndex(r, 4);
        }
        deck.addIndex(11, 3);
        deck.addIndex(12, 1);
        return deck;
    }

    /** 解析 "3,4,4,K" 风格的逗号分隔牌面 */
    public static Hand fromCardString(String s) {
        Hand hand = new Hand();
        for (String token : s.split(",")) {
            token = token.trim();
            if (!token.isEmpty()) {
                hand.add(Rank.fromSymbol(token));
            }
        }
        return hand;
    }
}
