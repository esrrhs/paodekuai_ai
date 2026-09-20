package com.paodekuai.rules;

import com.paodekuai.model.CardType;
import com.paodekuai.model.Hand;
import com.paodekuai.model.Move;
import com.paodekuai.model.Rank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 跑得快出牌规则与合法动作生成引擎
 */
public class MoveGenerator {

    /**
     * 生成当前手牌的所有合法动作
     *
     * @param hand 当前玩家手牌
     * @param lastMove 桌面当前最大牌 (若无人出牌或被动接管则为 null 或 pass)
     * @param playerId 出牌玩家 ID
     * @param mustBeat 是否开启跑得快“能管必管”规则
     */
    public static List<Move> generateLegalMoves(Hand hand, Move lastMove, int playerId, boolean mustBeat) {
        if (hand.isEmpty()) {
            return Collections.emptyList();
        }

        List<Move> legalMoves = new ArrayList<>();

        if (lastMove == null || lastMove.isPass()) {
            // 主动出牌 (Lead)
            generateAllLeadMoves(hand, playerId, legalMoves);
        } else {
            // 压牌 (Follow)
            generateBeatingMoves(hand, lastMove, playerId, legalMoves);

            // 炸弹可以压制非炸弹牌型
            if (!lastMove.isBomb()) {
                generateBombs(hand, 0, playerId, legalMoves);
            }

            // 如果没有合法的压牌动作，或者规则未强制“能管必管”，则允许过牌 PASS
            if (legalMoves.isEmpty() || !mustBeat) {
                legalMoves.add(Move.pass(playerId));
            }
        }

        return legalMoves;
    }

    private static void generateAllLeadMoves(Hand hand, int playerId, List<Move> result) {
        // 1. 单张
        for (int v = 3; v <= 15; v++) {
            if (hand.getCount(v) >= 1) {
                result.add(Move.of(CardType.SINGLE, v, List.of(Rank.fromValue(v)), playerId));
            }
        }

        // 2. 对子
        for (int v = 3; v <= 15; v++) {
            if (hand.getCount(v) >= 2) {
                Rank r = Rank.fromValue(v);
                result.add(Move.of(CardType.PAIR, v, List.of(r, r), playerId));
            }
        }

        // 3. 三张
        for (int v = 3; v <= 15; v++) {
            if (hand.getCount(v) >= 3) {
                Rank r = Rank.fromValue(v);
                result.add(Move.of(CardType.TRIPLE, v, List.of(r, r, r), playerId));
            }
        }

        // 4. 三带一
        generateTriplePlusOne(hand, 0, playerId, result);

        // 5. 三带二 (带一对或带两张单牌)
        generateTriplePlusTwo(hand, 0, playerId, result);

        // 6. 顺子 (5~12张，最大到A即14)
        generateStraights(hand, 0, -1, playerId, result);

        // 7. 连对 (2~6连对，最大到A即14)
        generateConsecutivePairs(hand, 0, -1, playerId, result);

        // 8. 飞机不带 (2~4连三张，最大到A即14)
        generateAirplanes(hand, 0, -1, playerId, result);

        // 9. 飞机带单牌
        generateAirplaneWithWings(hand, 0, -1, false, playerId, result);

        // 10. 飞机带对子
        generateAirplaneWithWings(hand, 0, -1, true, playerId, result);

        // 11. 炸弹 (4张)
        generateBombs(hand, 0, playerId, result);

        // 12. 四带二
        generateFourPlusTwo(hand, 0, playerId, result);
    }

    private static void generateBeatingMoves(Hand hand, Move lastMove, int playerId, List<Move> result) {
        int lastRank = lastMove.getMainRank();
        int cardCount = lastMove.getCardCount();

        switch (lastMove.getType()) {
            case SINGLE -> {
                for (int v = lastRank + 1; v <= 15; v++) {
                    if (hand.getCount(v) >= 1) {
                        result.add(Move.of(CardType.SINGLE, v, List.of(Rank.fromValue(v)), playerId));
                    }
                }
            }
            case PAIR -> {
                for (int v = lastRank + 1; v <= 15; v++) {
                    if (hand.getCount(v) >= 2) {
                        Rank r = Rank.fromValue(v);
                        result.add(Move.of(CardType.PAIR, v, List.of(r, r), playerId));
                    }
                }
            }
            case TRIPLE -> {
                for (int v = lastRank + 1; v <= 15; v++) {
                    if (hand.getCount(v) >= 3) {
                        Rank r = Rank.fromValue(v);
                        result.add(Move.of(CardType.TRIPLE, v, List.of(r, r, r), playerId));
                    }
                }
            }
            case TRIPLE_PLUS_ONE -> generateTriplePlusOne(hand, lastRank, playerId, result);
            case TRIPLE_PLUS_PAIR -> generateTriplePlusTwo(hand, lastRank, playerId, result);
            case STRAIGHT -> generateStraights(hand, lastRank, cardCount, playerId, result);
            case CONSECUTIVE_PAIRS -> generateConsecutivePairs(hand, lastRank, cardCount, playerId, result);
            case AIRPLANE -> generateAirplanes(hand, lastRank, cardCount, playerId, result);
            case AIRPLANE_PLUS_SINGLES -> generateAirplaneWithWings(hand, lastRank, cardCount, false, playerId, result);
            case AIRPLANE_PLUS_PAIRS -> generateAirplaneWithWings(hand, lastRank, cardCount, true, playerId, result);
            case BOMB -> generateBombs(hand, lastRank, playerId, result);
            case FOUR_PLUS_TWO -> generateFourPlusTwo(hand, lastRank, playerId, result);
            case PASS -> {
                // 不会出现
            }
            default -> {}
        }
    }

    private static void generateBombs(Hand hand, int minRank, int playerId, List<Move> result) {
        for (int v = minRank + 1; v <= 15; v++) {
            if (hand.getCount(v) == 4) {
                Rank r = Rank.fromValue(v);
                result.add(Move.of(CardType.BOMB, v, List.of(r, r, r, r), playerId));
            }
        }
    }

    private static void generateTriplePlusOne(Hand hand, int minRank, int playerId, List<Move> result) {
        for (int t = minRank + 1; t <= 15; t++) {
            if (hand.getCount(t) >= 3) {
                Rank tr = Rank.fromValue(t);
                // 挑选一张单牌
                for (int s = 3; s <= 15; s++) {
                    if (s != t && hand.getCount(s) >= 1) {
                        Rank sr = Rank.fromValue(s);
                        result.add(Move.of(CardType.TRIPLE_PLUS_ONE, t, List.of(tr, tr, tr, sr), playerId));
                    }
                }
            }
        }
    }

    private static void generateTriplePlusTwo(Hand hand, int minRank, int playerId, List<Move> result) {
        for (int t = minRank + 1; t <= 15; t++) {
            if (hand.getCount(t) >= 3) {
                Rank tr = Rank.fromValue(t);
                // 1. 带一对
                for (int p = 3; p <= 15; p++) {
                    if (p != t && hand.getCount(p) >= 2) {
                        Rank pr = Rank.fromValue(p);
                        result.add(Move.of(CardType.TRIPLE_PLUS_PAIR, t, List.of(tr, tr, tr, pr, pr), playerId));
                    }
                }
                // 2. 带两张单牌
                for (int s1 = 3; s1 <= 15; s1++) {
                    if (s1 == t || hand.getCount(s1) < 1) continue;
                    for (int s2 = s1 + 1; s2 <= 15; s2++) {
                        if (s2 == t || hand.getCount(s2) < 1) continue;
                        result.add(Move.of(CardType.TRIPLE_PLUS_PAIR, t,
                                List.of(tr, tr, tr, Rank.fromValue(s1), Rank.fromValue(s2)), playerId));
                    }
                }
            }
        }
    }

    private static void generateStraights(Hand hand, int minRank, int requiredLength, int playerId, List<Move> result) {
        // 顺子点数在 3..14 之间 (不能包含 2)
        int minLen = (requiredLength > 0) ? requiredLength : 5;
        int maxLen = (requiredLength > 0) ? requiredLength : 12;

        for (int len = minLen; len <= maxLen; len++) {
            for (int start = 3; start <= 14 - len + 1; start++) {
                int mainRank = start + len - 1; // 顺子的最大牌
                if (mainRank <= minRank) {
                    continue;
                }
                boolean valid = true;
                List<Rank> cards = new ArrayList<>(len);
                for (int i = 0; i < len; i++) {
                    if (hand.getCount(start + i) < 1) {
                        valid = false;
                        break;
                    }
                    cards.add(Rank.fromValue(start + i));
                }
                if (valid) {
                    result.add(Move.of(CardType.STRAIGHT, mainRank, cards, playerId));
                }
            }
        }
    }

    private static void generateConsecutivePairs(Hand hand, int minRank, int requiredCardCount, int playerId, List<Move> result) {
        // 连对至少2连对 (4张)，最大到A (14)
        int minPairs = (requiredCardCount > 0) ? requiredCardCount / 2 : 2;
        int maxPairs = (requiredCardCount > 0) ? requiredCardCount / 2 : 6;

        for (int pairs = minPairs; pairs <= maxPairs; pairs++) {
            for (int start = 3; start <= 14 - pairs + 1; start++) {
                int mainRank = start + pairs - 1;
                if (mainRank <= minRank) {
                    continue;
                }
                boolean valid = true;
                List<Rank> cards = new ArrayList<>(pairs * 2);
                for (int i = 0; i < pairs; i++) {
                    int v = start + i;
                    if (hand.getCount(v) < 2) {
                        valid = false;
                        break;
                    }
                    Rank r = Rank.fromValue(v);
                    cards.add(r);
                    cards.add(r);
                }
                if (valid) {
                    result.add(Move.of(CardType.CONSECUTIVE_PAIRS, mainRank, cards, playerId));
                }
            }
        }
    }

    private static void generateAirplanes(Hand hand, int minRank, int requiredCardCount, int playerId, List<Move> result) {
        int minTriples = (requiredCardCount > 0) ? requiredCardCount / 3 : 2;
        int maxTriples = (requiredCardCount > 0) ? requiredCardCount / 3 : 4;

        for (int numTriples = minTriples; numTriples <= maxTriples; numTriples++) {
            for (int start = 3; start <= 14 - numTriples + 1; start++) {
                int mainRank = start + numTriples - 1;
                if (mainRank <= minRank) continue;

                boolean valid = true;
                List<Rank> cards = new ArrayList<>(numTriples * 3);
                for (int i = 0; i < numTriples; i++) {
                    int v = start + i;
                    if (hand.getCount(v) < 3) {
                        valid = false;
                        break;
                    }
                    Rank r = Rank.fromValue(v);
                    cards.add(r);
                    cards.add(r);
                    cards.add(r);
                }
                if (valid) {
                    result.add(Move.of(CardType.AIRPLANE, mainRank, cards, playerId));
                }
            }
        }
    }

    private static void generateAirplaneWithWings(Hand hand, int minRank, int requiredCardCount, boolean withPairs, int playerId, List<Move> result) {
        int wingCardsPerTriple = withPairs ? 2 : 1; // 带对子是2张，带单牌是1张
        int totalPerTriple = 3 + wingCardsPerTriple;

        int minTriples = (requiredCardCount > 0) ? requiredCardCount / totalPerTriple : 2;
        int maxTriples = (requiredCardCount > 0) ? requiredCardCount / totalPerTriple : 3;

        for (int numTriples = minTriples; numTriples <= maxTriples; numTriples++) {
            for (int start = 3; start <= 14 - numTriples + 1; start++) {
                int mainRank = start + numTriples - 1;
                if (mainRank <= minRank) continue;

                // 检查三张是否齐全
                boolean hasTriples = true;
                Set<Integer> tripleRanks = new HashSet<>();
                for (int i = 0; i < numTriples; i++) {
                    int v = start + i;
                    tripleRanks.add(v);
                    if (hand.getCount(v) < 3) {
                        hasTriples = false;
                        break;
                    }
                }
                if (!hasTriples) continue;

                List<Rank> tripleCards = new ArrayList<>(numTriples * 3);
                for (int i = 0; i < numTriples; i++) {
                    Rank r = Rank.fromValue(start + i);
                    tripleCards.add(r);
                    tripleCards.add(r);
                    tripleCards.add(r);
                }

                // 寻找翅膀
                if (withPairs) {
                    List<Integer> availablePairs = new ArrayList<>();
                    for (int v = 3; v <= 15; v++) {
                        if (!tripleRanks.contains(v) && hand.getCount(v) >= 2) {
                            availablePairs.add(v);
                        }
                    }
                    if (availablePairs.size() >= numTriples) {
                        // 取前 numTriples 对
                        List<Rank> allCards = new ArrayList<>(tripleCards);
                        for (int i = 0; i < numTriples; i++) {
                            Rank pr = Rank.fromValue(availablePairs.get(i));
                            allCards.add(pr);
                            allCards.add(pr);
                        }
                        result.add(Move.of(CardType.AIRPLANE_PLUS_PAIRS, mainRank, allCards, playerId));
                    }
                } else {
                    List<Integer> availableSingles = new ArrayList<>();
                    for (int v = 3; v <= 15; v++) {
                        if (!tripleRanks.contains(v) && hand.getCount(v) >= 1) {
                            availableSingles.add(v);
                        }
                    }
                    if (availableSingles.size() >= numTriples) {
                        List<Rank> allCards = new ArrayList<>(tripleCards);
                        for (int i = 0; i < numTriples; i++) {
                            allCards.add(Rank.fromValue(availableSingles.get(i)));
                        }
                        result.add(Move.of(CardType.AIRPLANE_PLUS_SINGLES, mainRank, allCards, playerId));
                    }
                }
            }
        }
    }

    private static void generateFourPlusTwo(Hand hand, int minRank, int playerId, List<Move> result) {
        for (int v = minRank + 1; v <= 15; v++) {
            if (hand.getCount(v) == 4) {
                Rank r = Rank.fromValue(v);
                // 四带一对
                for (int p = 3; p <= 15; p++) {
                    if (p != v && hand.getCount(p) >= 2) {
                        Rank pr = Rank.fromValue(p);
                        result.add(Move.of(CardType.FOUR_PLUS_TWO, v, List.of(r, r, r, r, pr, pr), playerId));
                    }
                }
                // 四带两单
                for (int s1 = 3; s1 <= 15; s1++) {
                    if (s1 == v || hand.getCount(s1) < 1) continue;
                    for (int s2 = s1 + 1; s2 <= 15; s2++) {
                        if (s2 == v || hand.getCount(s2) < 1) continue;
                        result.add(Move.of(CardType.FOUR_PLUS_TWO, v,
                                List.of(r, r, r, r, Rank.fromValue(s1), Rank.fromValue(s2)), playerId));
                    }
                }
            }
        }
    }
}
