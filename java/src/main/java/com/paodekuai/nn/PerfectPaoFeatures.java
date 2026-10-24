package com.paodekuai.nn;

import com.paodekuai.game.PublicView;
import com.paodekuai.model.CardType;
import com.paodekuai.model.Hand;
import com.paodekuai.model.Move;
import com.paodekuai.model.Rank;
import com.paodekuai.rules.Deck;
import com.paodekuai.rules.MoveGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * 节点/动作特征工程,逐位对齐训练侧 pdk/features.cpp。
 *
 * 11x13 牌矩阵(行主序 row*13+rank):
 *   行 0-3 张数 one-hot,行 4/5/6 单/对/三存在,行 7 炸弹(恰 4 张),
 *   行 8/9/10 是否处于顺子/连对/三顺链内。
 *
 * 节点特征 xImp[1188] = 8 个静态矩阵(1144)+ 44 标量:
 *   我手牌 / 两家合计未知牌 / 我已出 / 上家已出 / 下家已出 /
 *   我最近动作 / 上家最近动作 / 下家最近动作;
 *   标量 = oneHot(上家手数-1,16) + oneHot(下家手数-1,16) +
 *          oneHot(min(炸弹数,8),9) + 是否轮空(上轮自由出) +
 *          下家报单 + 上家报单。
 *
 * 历史序列 seq[2145] = 最近 15 手(含过,空矩阵)各转矩阵,按时间序尾部对齐,
 * LSTM 视角每 3 手拼 1 个时间步(5 步 x 429)。
 *
 * 动作特征[149] = 143 牌矩阵 + 6 标量:
 *   是否炸弹 / 是否该类型最大 / 张数==下家手数 / 张数==上家手数 /
 *   出完此手后的 oracle 手数 /16 / 有效标记(恒 1)。
 *
 * 全部只用 PublicView 公开信息,绝不读对手私有手牌。
 */
public final class PerfectPaoFeatures {

    public static final int CARD_ROWS = 11;
    public static final int CARD_MAT = CARD_ROWS * Rank.RANKS;      // 143
    public static final int HISTORY_LEN = 15;
    public static final int LSTM_STEPS = 5;                          // 5 步 x 3 手
    public static final int LSTM_IN = 3 * CARD_MAT;                  // 429
    public static final int STATIC_MATRICES = 8;
    public static final int IMP_MATRICES = STATIC_MATRICES + HISTORY_LEN; // 23
    public static final int IMP_BIN = IMP_MATRICES * CARD_MAT;       // 3289
    public static final int HAND_HOT = 16;
    public static final int BOMB_HOT = 9;
    public static final int IMP_SCALARS = 2 * HAND_HOT + BOMB_HOT + 3; // 44
    public static final int NODE_BIN = STATIC_MATRICES * CARD_MAT;   // 1144
    public static final int NODE_SIZE = NODE_BIN + IMP_SCALARS;      // 1188
    public static final int SEQ_SIZE = HISTORY_LEN * CARD_MAT;       // 2145
    public static final int ACTION_SIZE = CARD_MAT + 6;              // 149
    public static final int NUM_ACTIONS = 2048;

    // 行下标
    private static final int R_SOLO = 4;
    private static final int R_PAIR = 5;
    private static final int R_TRIO = 6;
    private static final int R_BOMB = 7;
    private static final int R_SOLO_CHAIN = 8;
    private static final int R_PAIR_CHAIN = 9;
    private static final int R_TRIO_CHAIN = 10;

    private PerfectPaoFeatures() {
    }

    /** 通用 11x13 牌矩阵,写入 out[offset..offset+143) */
    public static void cardMatrix(Hand cs, float[] out, int offset) {
        java.util.Arrays.fill(out, offset, offset + CARD_MAT, 0f);
        for (int r = 0; r < Rank.RANKS; ++r) {
            int c = cs.countIndex(r);
            for (int k = 0; k < c; ++k) {
                out[offset + k * Rank.RANKS + r] = 1f;
            }
            if (c >= 1) {
                out[offset + R_SOLO * Rank.RANKS + r] = 1f;
            }
            if (c >= 2) {
                out[offset + R_PAIR * Rank.RANKS + r] = 1f;
            }
            if (c >= 3) {
                out[offset + R_TRIO * Rank.RANKS + r] = 1f;
            }
            if (c == 4) {
                out[offset + R_BOMB * Rank.RANKS + r] = 1f;
            }
        }
        for (int r = 0; r < Rank.CHAIN_RANKS; ++r) {
            if (inChain(cs, r, CardType.MIN_STRAIGHT, 1)) {
                out[offset + R_SOLO_CHAIN * Rank.RANKS + r] = 1f;
            }
            if (inChain(cs, r, CardType.MIN_PAIR_CHAIN, 2)) {
                out[offset + R_PAIR_CHAIN * Rank.RANKS + r] = 1f;
            }
            if (inChain(cs, r, CardType.MIN_PLANE, 3)) {
                out[offset + R_TRIO_CHAIN * Rank.RANKS + r] = 1f;
            }
        }
    }

    /** 点数 r 是否处于长度 >= minLen、每点 >= need 张的连续链内(3..A) */
    private static boolean inChain(Hand cnt, int r, int minLen, int need) {
        if (r >= Rank.CHAIN_RANKS) {
            return false;
        }
        int lo = r, hi = r;
        while (lo - 1 >= 0 && cnt.countIndex(lo - 1) >= need) {
            --lo;
        }
        while (hi + 1 < Rank.CHAIN_RANKS && cnt.countIndex(hi + 1) >= need) {
            ++hi;
        }
        return hi - lo + 1 >= minLen;
    }

    /** 编码结果:xImp 是节点特征,seq 是 LSTM 历史序列 */
    public record Encoded(float[] xImp, float[] seq) {
    }

    /**
     * 编码行动玩家(viewingPlayerId 必须等于 activePlayerId,即轮到该玩家)。
     * 上/下家按玩家编号相对计算,与训练侧 seat/prevSeat/nextSeat 一致。
     */
    public static Encoded encode(PublicView view) {
        int seat = view.getViewingPlayerId();
        int prev = (seat + 2) % 3;
        int nxt = (seat + 1) % 3;

        // 重放历史得到各家累计已出牌
        Hand[] played = new Hand[3];
        for (int s = 0; s < 3; ++s) {
            played[s] = new Hand();
        }
        for (Move m : view.getMoveHistory()) {
            if (!m.isPass()) {
                for (Rank r : m.getCards()) {
                    played[m.getPlayerId()].add(r);
                }
            }
        }

        // 两家合计未知牌池 = 48 - 我手牌 - 全场已出
        Hand others = Deck.fullDeck();
        others.sub(view.getMyHand());
        for (int s = 0; s < 3; ++s) {
            others.sub(played[s]);
        }

        float[] matrices = new float[IMP_MATRICES * CARD_MAT];
        cardMatrix(view.getMyHand(), matrices, 0);
        cardMatrix(others, matrices, 1 * CARD_MAT);
        cardMatrix(played[seat], matrices, 2 * CARD_MAT);
        cardMatrix(played[prev], matrices, 3 * CARD_MAT);
        cardMatrix(played[nxt], matrices, 4 * CARD_MAT);
        cardMatrix(handOf(view.getLastMoveOf(seat)), matrices, 5 * CARD_MAT);
        cardMatrix(handOf(view.getLastMoveOf(prev)), matrices, 6 * CARD_MAT);
        cardMatrix(handOf(view.getLastMoveOf(nxt)), matrices, 7 * CARD_MAT);
        // 最近 15 手按时间序尾部对齐,前部补零(空矩阵)
        List<Move> history = view.getMoveHistory();
        int hlen = Math.min(HISTORY_LEN, history.size());
        for (int i = 0; i < hlen; ++i) {
            Move m = history.get(history.size() - hlen + i);
            cardMatrix(handOf(m), matrices, (STATIC_MATRICES + (HISTORY_LEN - hlen) + i) * CARD_MAT);
        }

        float[] scalars = new float[IMP_SCALARS];
        oneHot(scalars, 0, HAND_HOT, view.getCardCount(prev) - 1);
        oneHot(scalars, HAND_HOT, HAND_HOT, view.getCardCount(nxt) - 1);
        oneHot(scalars, 2 * HAND_HOT, BOMB_HOT, Math.min(view.getBombCount(), BOMB_HOT - 1));
        scalars[2 * HAND_HOT + BOMB_HOT + 0] = view.getLastPlayerId() == seat ? 1f : 0f;
        scalars[2 * HAND_HOT + BOMB_HOT + 1] = view.getCardCount(nxt) == 1 ? 1f : 0f;
        scalars[2 * HAND_HOT + BOMB_HOT + 2] = view.getCardCount(prev) == 1 ? 1f : 0f;

        float[] xImp = new float[NODE_SIZE];
        System.arraycopy(matrices, 0, xImp, 0, NODE_BIN);
        System.arraycopy(scalars, 0, xImp, NODE_BIN, IMP_SCALARS);
        float[] seq = new float[SEQ_SIZE];
        System.arraycopy(matrices, STATIC_MATRICES * CARD_MAT, seq, 0, SEQ_SIZE);
        return new Encoded(xImp, seq);
    }

    private static Hand handOf(Move m) {
        Hand h = new Hand();
        if (!m.isPass()) {
            for (Rank r : m.getCards()) {
                h.add(r);
            }
        }
        return h;
    }

    private static void oneHot(float[] dst, int offset, int n, int value) {
        if (value >= 0 && value < n) {
            dst[offset + value] = 1f;
        }
    }

    /** 是否没有同牌型剩余牌能压过这手(炸弹除外):单张 2、对/三/带 AA、炸弹 A、链尾到 A */
    static boolean isLargest(MoveGenerator.MoveInfo a) {
        return switch (a.type()) {
            case SINGLE -> a.rank() == 12;            // 全场唯一的 2
            case BOMB, PAIR, TRIPLE, TRIPLE_TWO -> a.rank() == 11;  // A(2 不成对)
            case STRAIGHT, PAIR_STRAIGHT, TRIPLE_STRAIGHT, PLANE_SOLO, PLANE_PAIR ->
                    a.rank() + a.len() - 1 == 11;     // 链尾到 A
            default -> false;
        };
    }

    /** 一个候选动作 */
    public static final class LegalOption {
        public final int slot;          // 本决策点内的槽位 0..n-1
        public final Move move;         // 引擎动作
        public final Hand pattern;      // 牌型(过为空手牌)
        public final float[] feature;   // 149 维动作特征

        LegalOption(int slot, Move move, Hand pattern, float[] feature) {
            this.slot = slot;
            this.move = move;
            this.pattern = pattern;
            this.feature = feature;
        }
    }

    /** 当前视角下的全部合法候选(含引擎约束:首手带 3、报单顶牌),顺序与训练侧一致 */
    public static List<LegalOption> legalOptions(PublicView view) {
        List<LegalOption> out = new ArrayList<>();
        int seat = view.getViewingPlayerId();
        int prev = (seat + 2) % 3;
        int nxt = (seat + 1) % 3;
        Hand myHand = view.getMyHand();
        Move tb = view.getToBeat();

        List<Hand> patterns;
        if (tb == null || tb.isPass()) {
            patterns = MoveGenerator.genAllMoves(myHand);
            if (view.getMoveHistory().isEmpty()) {
                // 第一手必须包含 3
                patterns.removeIf(m -> m.countIndex(0) < 1);
            } else if (view.getCardCount(nxt) == 1) {
                // 下家报单:出单张必须顶最大
                int largest = -1;
                for (int r = 0; r < Rank.RANKS; ++r) {
                    if (myHand.countIndex(r) >= 1) {
                        largest = r;
                    }
                }
                int largestFinal = largest;
                patterns.removeIf(m -> {
                    MoveGenerator.MoveInfo info = MoveGenerator.detectMove(m);
                    return info.type() == CardType.SINGLE && info.rank() != largestFinal;
                });
            }
        } else {
            patterns = MoveGenerator.legalMoves(myHand, handOf(tb));
        }

        for (Hand chosen : patterns) {
            MoveGenerator.MoveInfo info = MoveGenerator.detectMove(chosen);
            if (info.type() == CardType.WRONG && !chosen.isEmpty()) {
                continue;
            }
            float[] feature = new float[ACTION_SIZE];
            cardMatrix(chosen, feature, 0);
            int size = chosen.getTotal();
            feature[CARD_MAT + 0] = info.type() == CardType.BOMB ? 1f : 0f;
            feature[CARD_MAT + 1] = isLargest(info) ? 1f : 0f;
            feature[CARD_MAT + 2] = size > 0 && size == view.getCardCount(nxt) ? 1f : 0f;
            feature[CARD_MAT + 3] = size > 0 && size == view.getCardCount(prev) ? 1f : 0f;
            Hand after = myHand.copy();
            after.sub(chosen);
            feature[CARD_MAT + 4] = Oracle.minSteps(after) / (float) 16;
            feature[CARD_MAT + 5] = 1f;  // 有效标记,区分全零的过牌矩阵
            out.add(new LegalOption(out.size(), MoveGenerator.toMove(chosen, seat),
                    chosen, feature));
        }
        return out;
    }
}
