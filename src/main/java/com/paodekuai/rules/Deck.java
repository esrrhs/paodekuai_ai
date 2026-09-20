package com.paodekuai.rules;

import com.paodekuai.model.Hand;
import com.paodekuai.model.Rank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 跑得快 48 张标准牌库生成与发牌器
 * (3~K 各 4 张，A 为 3 张，2 为 1 张，共 48 张；三人玩法每人 16 张)
 */
public class Deck {
    public static final int TOTAL_CARDS = 48;
    public static final int CARDS_PER_PLAYER = 16;
    public static final int NUM_PLAYERS = 3;

    /**
     * 生成 48 张标准牌库
     */
    public static List<Rank> createStandard48Cards() {
        List<Rank> deck = new ArrayList<>(TOTAL_CARDS);
        // 3 到 K，各 4 张 (11 * 4 = 44 张)
        for (int v = Rank.THREE.getValue(); v <= Rank.KING.getValue(); v++) {
            Rank r = Rank.fromValue(v);
            for (int i = 0; i < 4; i++) {
                deck.add(r);
            }
        }
        // A 为 3 张
        for (int i = 0; i < 3; i++) {
            deck.add(Rank.ACE);
        }
        // 2 为 1 张
        deck.add(Rank.TWO);

        return deck;
    }

    /**
     * 洗牌并分发给 3 位玩家
     */
    public static List<Hand> deal(Random random) {
        List<Rank> deck = createStandard48Cards();
        Collections.shuffle(deck, random);

        List<Hand> hands = new ArrayList<>(NUM_PLAYERS);
        for (int i = 0; i < NUM_PLAYERS; i++) {
            Hand hand = new Hand();
            for (int j = 0; j < CARDS_PER_PLAYER; j++) {
                hand.add(deck.get(i * CARDS_PER_PLAYER + j));
            }
            hands.add(hand);
        }
        return hands;
    }

    /**
     * 从字符串快捷解析并构造 Hand (例如 "3,4,4,5,5,7,9,9,10,10,J,J,Q,Q,K,A")
     */
    public static Hand fromCardString(String str) {
        Hand hand = new Hand();
        if (str == null || str.isBlank()) {
            return hand;
        }
        String[] parts = str.split(",");
        for (String p : parts) {
            String s = p.trim();
            if (!s.isEmpty()) {
                hand.add(Rank.fromSymbol(s));
            }
        }
        return hand;
    }
}
