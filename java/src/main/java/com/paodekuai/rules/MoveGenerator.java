package com.paodekuai.rules;

import com.paodekuai.model.CardType;
import com.paodekuai.model.Hand;
import com.paodekuai.model.Move;
import com.paodekuai.model.Rank;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 牌型检测与出牌生成,忠实移植训练侧 pdk/moves.cpp。
 *
 * 湖南跑得快牌型:单张、对子、三张、三带二(带一对或两单)、顺子(>=5, 3..A)、
 * 连对(>=2)、三顺(>=2)、飞机带单/带对、炸弹(4 同点)。无王炸、无四带二。
 *
 * 能管必管:应牌时若存在能压过桌面的出牌则不允许过(含炸弹)。
 */
public final class MoveGenerator {

    /** 牌型检测结果,与训练侧 MoveInfo 同构;rank 为主牌点数下标(链型取最低点) */
    public record MoveInfo(CardType type, int rank, int len) {
        public static final MoveInfo PASS = new MoveInfo(CardType.PASS, -1, 0);
        public static final MoveInfo WRONG = new MoveInfo(CardType.WRONG, -1, 0);
    }

    private final Hand hand;

    public MoveGenerator(Hand hand) {
        this.hand = hand;
    }

    // ------------------------------------------------------------------
    // 生成器(pdk/moves.cpp 的 MoveGenerator 类)
    // ------------------------------------------------------------------

    public List<Hand> genAll() {
        List<Hand> out = new ArrayList<>();
        out.addAll(genSingles());
        out.addAll(genPairs());
        out.addAll(genTriples());
        out.addAll(genBombs());
        out.addAll(genTripleTwo());
        out.addAll(genSerials(1, CardType.MIN_STRAIGHT, 0));   // 顺子
        out.addAll(genSerials(2, CardType.MIN_PAIR_CHAIN, 0)); // 连对
        out.addAll(genSerials(3, CardType.MIN_PLANE, 0));      // 三顺
        out.addAll(genPlaneSolo(0));
        out.addAll(genPlanePair(0));
        return dedupe(out);
    }

    /** 某一类牌型的全部出牌;len > 0 时限定链长 */
    public List<Hand> genByType(CardType type, int len) {
        List<Hand> out = switch (type) {
            case SINGLE -> genSingles();
            case PAIR -> genPairs();
            case TRIPLE -> genTriples();
            case BOMB -> genBombs();
            case TRIPLE_TWO -> genTripleTwo();
            case STRAIGHT -> genSerials(1, CardType.MIN_STRAIGHT, len);
            case PAIR_STRAIGHT -> genSerials(2, CardType.MIN_PAIR_CHAIN, len);
            case TRIPLE_STRAIGHT -> genSerials(3, CardType.MIN_PLANE, len);
            case PLANE_SOLO -> genPlaneSolo(len);
            case PLANE_PAIR -> genPlanePair(len);
            default -> new ArrayList<>();
        };
        return dedupe(out);
    }

    private static List<Hand> dedupe(List<Hand> moves) {
        Set<Long> seen = new HashSet<>(moves.size() * 2);
        List<Hand> out = new ArrayList<>(moves.size());
        for (Hand m : moves) {
            if (seen.add(m.key())) {
                out.add(m);
            }
        }
        return out;
    }

    private List<Hand> genSingles() {
        List<Hand> r = new ArrayList<>();
        for (int rank = 0; rank < Rank.RANKS; ++rank) {
            if (hand.countIndex(rank) >= 1) {
                Hand m = new Hand();
                m.addIndex(rank, 1);
                r.add(m);
            }
        }
        return r;
    }

    private List<Hand> genPairs() {
        List<Hand> r = new ArrayList<>();
        for (int rank = 0; rank < Rank.RANKS; ++rank) {
            if (hand.countIndex(rank) >= 2) {
                Hand m = new Hand();
                m.addIndex(rank, 2);
                r.add(m);
            }
        }
        return r;
    }

    private List<Hand> genTriples() {
        List<Hand> r = new ArrayList<>();
        for (int rank = 0; rank < Rank.RANKS; ++rank) {
            if (hand.countIndex(rank) >= 3) {
                Hand m = new Hand();
                m.addIndex(rank, 3);
                r.add(m);
            }
        }
        return r;
    }

    private List<Hand> genBombs() {
        List<Hand> r = new ArrayList<>();
        for (int rank = 0; rank < Rank.RANKS; ++rank) {
            if (hand.countIndex(rank) == 4) {
                Hand m = new Hand();
                m.addIndex(rank, 4);
                r.add(m);
            }
        }
        return r;
    }

    /**
     * 三带二:三张 + 2 张散牌(一对或两张不同单张;散牌不用三张自己的点数,
     * 所以 3333 只会是炸弹)。
     */
    private List<Hand> genTripleTwo() {
        List<Hand> r = new ArrayList<>();
        for (int t = 0; t < Rank.RANKS; ++t) {
            if (hand.countIndex(t) < 3) {
                continue;
            }
            Hand rest = hand.copy();
            rest.addIndex(t, -hand.countIndex(t));
            List<Hand> wings = new ArrayList<>();
            enumLooseMultisets(rest, 0, 2, new Hand(), wings);
            for (Hand w : wings) {
                Hand m = new Hand();
                m.addIndex(t, 3);
                m.add(w);
                r.add(m);
            }
        }
        return r;
    }

    /** repeat=1 单张链 / 2 连对 / 3 三顺;链只在 3..A 内;fixedLen > 0 限定链长 */
    private List<Hand> genSerials(int repeat, int minLen, int fixedLen) {
        List<Integer> ranks = new ArrayList<>();
        for (int r = 0; r < Rank.CHAIN_RANKS; ++r) {
            if (hand.countIndex(r) >= repeat) {
                ranks.add(r);
            }
        }
        List<Hand> out = new ArrayList<>();
        int i = 0;
        while (i < ranks.size()) {
            int j = i;
            while (j + 1 < ranks.size() && ranks.get(j + 1) - ranks.get(j) == 1) {
                ++j;
            }
            int longest = j - i + 1;
            if (fixedLen > 0) {
                if (longest >= fixedLen) {
                    for (int idx = 0; idx + fixedLen <= longest; ++idx) {
                        out.add(fromRanks(ranks.subList(i + idx, i + idx + fixedLen), repeat));
                    }
                }
            } else {
                for (int len = minLen; len <= longest; ++len) {
                    for (int idx = 0; idx + len <= longest; ++idx) {
                        out.add(fromRanks(ranks.subList(i + idx, i + idx + len), repeat));
                    }
                }
            }
            i = j + 1;
        }
        return out;
    }

    private List<Hand> genPlaneSolo(int fixedLen) {
        List<Hand> planes = genSerials(3, CardType.MIN_PLANE, fixedLen);
        List<Hand> out = new ArrayList<>();
        for (Hand pl : planes) {
            int chainLen = 0;
            Hand rest = hand.copy();
            for (int r = 0; r < Rank.RANKS; ++r) {
                if (pl.countIndex(r) == 3) {
                    ++chainLen;
                    rest.addIndex(r, -3);
                }
            }
            List<Hand> wings = new ArrayList<>();
            enumLooseMultisets(rest, 0, chainLen, new Hand(), wings);
            for (Hand w : wings) {
                Hand m = pl.copy();
                m.add(w);
                out.add(m);
            }
        }
        return out;
    }

    private List<Hand> genPlanePair(int fixedLen) {
        List<Hand> planes = genSerials(3, CardType.MIN_PLANE, fixedLen);
        List<Hand> out = new ArrayList<>();
        for (Hand pl : planes) {
            int chainLen = 0;
            List<Integer> pairCands = new ArrayList<>();
            for (int r = 0; r < Rank.RANKS; ++r) {
                if (pl.countIndex(r) == 3) {
                    ++chainLen;
                    continue;
                }
                if (hand.countIndex(r) >= 2) {
                    pairCands.add(r);
                }
            }
            if (pairCands.size() >= chainLen) {
                List<List<Integer>> combos = new ArrayList<>();
                enumRankCombos(pairCands, 0, chainLen, new ArrayList<>(), combos);
                for (List<Integer> ps : combos) {
                    Hand m = pl.copy();
                    for (int r : ps) {
                        m.addIndex(r, 2);
                    }
                    out.add(m);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 组合枚举辅助
    // ------------------------------------------------------------------

    /** 从 cnt 里枚举 need 张散牌的全部多重集(训练侧 enumLooseMultisets) */
    private static void enumLooseMultisets(Hand cnt, int rank, int need, Hand pick,
                                           List<Hand> out) {
        if (need == 0) {
            out.add(pick.copy());
            return;
        }
        if (rank >= Rank.RANKS) {
            return;
        }
        int mx = Math.min(cnt.countIndex(rank), need);
        for (int take = 0; take <= mx; ++take) {
            pick.addIndex(rank, take);
            enumLooseMultisets(cnt, rank + 1, need - take, pick, out);
            pick.addIndex(rank, -take);
        }
    }

    /** 从候选点数里枚举 need 个不同点数的组合(训练侧 enumRankCombos) */
    private static void enumRankCombos(List<Integer> cands, int idx, int need,
                                       List<Integer> sel, List<List<Integer>> out) {
        if (need == 0) {
            out.add(new ArrayList<>(sel));
            return;
        }
        for (int i = idx; i + need <= cands.size(); ++i) {
            sel.add(cands.get(i));
            enumRankCombos(cands, i + 1, need - 1, sel, out);
            sel.remove(sel.size() - 1);
        }
    }

    private static Hand fromRanks(List<Integer> ranks, int repeat) {
        Hand m = new Hand();
        for (int r : ranks) {
            m.addIndex(r, repeat);
        }
        return m;
    }

    // ------------------------------------------------------------------
    // 牌型检测(pdk/moves.cpp 的 detectMove)
    // ------------------------------------------------------------------

    public static MoveInfo detectMove(Hand m) {
        int size = m.getTotal();

        if (size == 0) {
            return MoveInfo.PASS;
        }
        if (size == 1) {
            for (int r = 0; r < Rank.RANKS; ++r) {
                if (m.countIndex(r) > 0) {
                    return new MoveInfo(CardType.SINGLE, r, 1);
                }
            }
            return MoveInfo.WRONG;
        }
        if (size == 2) {
            for (int r = 0; r < Rank.RANKS; ++r) {
                if (m.countIndex(r) == 2) {
                    return new MoveInfo(CardType.PAIR, r, 1);
                }
            }
            return MoveInfo.WRONG;
        }
        if (size == 3) {
            for (int r = 0; r < Rank.RANKS; ++r) {
                if (m.countIndex(r) == 3) {
                    return new MoveInfo(CardType.TRIPLE, r, 1);
                }
            }
            return MoveInfo.WRONG;
        }
        if (size == 4) {
            for (int r = 0; r < Rank.RANKS; ++r) {
                if (m.countIndex(r) == 4) {
                    return new MoveInfo(CardType.BOMB, r, 1);
                }
            }
            // 两连对(如 3344)
            List<Integer> pr = presentRanks(m);
            if (pr.size() == 2 && m.countIndex(pr.get(0)) == 2
                    && m.countIndex(pr.get(1)) == 2
                    && pr.get(1) - pr.get(0) == 1 && pr.get(1) < Rank.CHAIN_RANKS) {
                return new MoveInfo(CardType.PAIR_STRAIGHT, pr.get(0), 2);
            }
            return MoveInfo.WRONG;
        }
        if (size == 5) {
            // 五连顺
            List<Integer> pr = presentRanks(m);
            if (pr.size() == 5 && pr.get(4) < Rank.CHAIN_RANKS && consecutive(pr)) {
                return new MoveInfo(CardType.STRAIGHT, pr.get(0), 5);
            }
            // 三带二:一个三张 + 两个 1~2 张的散点
            int triRank = -1;
            boolean bad = false;
            for (int r = 0; r < Rank.RANKS; ++r) {
                int c = m.countIndex(r);
                if (c == 3) {
                    if (triRank >= 0) {
                        bad = true;
                        break;
                    }
                    triRank = r;
                } else if (c != 0 && c != 1 && c != 2) {
                    bad = true;
                    break;
                }
            }
            if (!bad && triRank >= 0) {
                return new MoveInfo(CardType.TRIPLE_TWO, triRank, 1);
            }
            return MoveInfo.WRONG;
        }

        // size >= 6
        List<Integer> pr = presentRanks(m);

        // 顺子:全部单张且连续
        if (pr.size() == size && pr.get(pr.size() - 1) < Rank.CHAIN_RANKS
                && consecutive(pr)) {
            return new MoveInfo(CardType.STRAIGHT, pr.get(0), size);
        }
        // 连对
        boolean allPairs = true;
        for (int r : pr) {
            if (m.countIndex(r) != 2) {
                allPairs = false;
                break;
            }
        }
        if (allPairs && pr.get(pr.size() - 1) < Rank.CHAIN_RANKS && consecutive(pr)) {
            return new MoveInfo(CardType.PAIR_STRAIGHT, pr.get(0), pr.size());
        }
        // 三顺(不带)
        boolean allTriples = true;
        for (int r : pr) {
            if (m.countIndex(r) != 3) {
                allTriples = false;
                break;
            }
        }
        if (allTriples && pr.get(pr.size() - 1) < Rank.CHAIN_RANKS && consecutive(pr)) {
            return new MoveInfo(CardType.TRIPLE_STRAIGHT, pr.get(0), pr.size());
        }

        // 飞机:count>=3 的连续段构成链,余牌为翅膀(某点 4 张 = 3 链 + 1 翅)。
        // 每个段的每个子链都检查,由长到短。
        for (int start = 0; start < Rank.CHAIN_RANKS; ) {
            if (m.countIndex(start) < 3) {
                ++start;
                continue;
            }
            int end = start;
            while (end + 1 < Rank.CHAIN_RANKS && m.countIndex(end + 1) >= 3) {
                ++end;
            }
            for (int len = end - start + 1; len >= CardType.MIN_PLANE; --len) {
                for (int s = start; s + len <= end + 1; ++s) {
                    Hand wings = m.copy();
                    for (int r = s; r < s + len; ++r) {
                        wings.addIndex(r, -3);
                    }
                    int wTotal = wings.getTotal();
                    if (wTotal == len) {
                        return new MoveInfo(CardType.PLANE_SOLO, s, len);
                    }
                    if (wTotal == 2 * len) {
                        boolean pairsOnly = true;
                        for (int r = 0; r < Rank.RANKS; ++r) {
                            int c = wings.countIndex(r);
                            if (c != 0 && c != 2) {
                                pairsOnly = false;
                                break;
                            }
                        }
                        if (pairsOnly) {
                            return new MoveInfo(CardType.PLANE_PAIR, s, len);
                        }
                    }
                }
            }
            start = end + 1;
        }
        return MoveInfo.WRONG;
    }

    /** 训练侧 pdk::beats:假定双方都是合法牌型 */
    public static boolean beats(Hand move, Hand rival) {
        MoveInfo a = detectMove(move);
        MoveInfo b = detectMove(rival);
        if (a.type() == CardType.WRONG || b.type() == CardType.WRONG) {
            return false;
        }
        if (a.type() == CardType.BOMB) {
            return b.type() != CardType.BOMB || a.rank() > b.rank();
        }
        if (b.type() == CardType.BOMB) {
            return false;
        }
        if (a.type() != b.type()) {
            return false;
        }
        if (Move.isChainType(a.type())) {
            return a.len() == b.len() && a.rank() > b.rank();
        }
        return a.rank() > b.rank();
    }

    /** 主动出牌的全部组合(不含过) */
    public static List<Hand> genAllMoves(Hand hand) {
        return new MoveGenerator(hand).genAll();
    }

    /**
     * 应牌合法动作(能管必管):toBeat 为空则自由出牌(不含过);
     * 有牌可压时只返回能压的动作(含炸弹,不含过);
     * 无牌可压时返回唯一的过(空 Hand)。
     */
    public static List<Hand> legalMoves(Hand hand, Hand toBeat) {
        if (toBeat.isEmpty()) {
            return genAllMoves(hand);
        }
        MoveInfo rival = detectMove(toBeat);
        if (rival.type() == CardType.WRONG) {
            return genAllMoves(hand);
        }
        MoveGenerator gen = new MoveGenerator(hand);
        List<Hand> moves = new ArrayList<>();
        if (rival.type() == CardType.BOMB) {
            for (Hand m : gen.genByType(CardType.BOMB, 0)) {
                if (beats(m, toBeat)) {
                    moves.add(m);
                }
            }
        } else {
            for (Hand m : gen.genByType(rival.type(), rival.len())) {
                if (beats(m, toBeat)) {
                    moves.add(m);
                }
            }
            // 炸弹可压一切普通牌型
            moves.addAll(gen.genByType(CardType.BOMB, 0));
        }
        if (moves.isEmpty()) {
            moves.add(new Hand());  // 压不过:过
        }
        return moves;
    }

    public static boolean isBomb(Hand m) {
        return m.getTotal() == 4 && detectMove(m).type() == CardType.BOMB;
    }

    /** 把牌型包装成某个玩家的 Move */
    public static Move toMove(Hand pattern, int playerId) {
        MoveInfo info = detectMove(pattern);
        if (info.type() == CardType.WRONG) {
            throw new IllegalArgumentException("illegal pattern: " + pattern);
        }
        if (info.type() == CardType.PASS) {
            return Move.pass(playerId);
        }
        return Move.of(info.type(), info.rank(), info.len(), pattern.getCards(), playerId);
    }

    private static List<Integer> presentRanks(Hand m) {
        List<Integer> v = new ArrayList<>();
        for (int r = 0; r < Rank.RANKS; ++r) {
            if (m.countIndex(r) > 0) {
                v.add(r);
            }
        }
        return v;
    }

    private static boolean consecutive(List<Integer> ranks) {
        for (int i = 1; i < ranks.size(); ++i) {
            if (ranks.get(i) - ranks.get(i - 1) != 1) {
                return false;
            }
        }
        return true;
    }
}
