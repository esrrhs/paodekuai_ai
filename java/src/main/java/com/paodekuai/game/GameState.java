package com.paodekuai.game;

import com.paodekuai.model.CardType;
import com.paodekuai.model.Hand;
import com.paodekuai.model.Move;
import com.paodekuai.model.Rank;
import com.paodekuai.rules.Deck;
import com.paodekuai.rules.MoveGenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 跑得快牌局状态机,忠实移植训练侧 pdk::Game(湖南三人 16 张规则)。
 *
 * 48 张牌全发(每人 16 张),无底牌;首出者随机指定(持指定 3 者先出),
 * 第一手必须包含 3;能管必管(有牌可压时不可过,炸弹在必管集合里);
 * 报单:有人剩 1 张时,自由出牌出单张必须顶手中最大单张;
 * 一人出完即终局,按剩牌数零和结算(全关翻倍)。
 */
public final class GameState {

    public static final int NUM_PLAYERS = Deck.NUM_PLAYERS;

    private final Hand[] hands = new Hand[NUM_PLAYERS];
    private final Hand[] played = new Hand[NUM_PLAYERS];
    private final Move[] lastMoves = new Move[NUM_PLAYERS];  // 各家最近动作(含过)
    private final List<Move> seq = new ArrayList<>();        // 完整动作序列(含过)

    private int turn;
    private int lastPlayer;
    private int bombCount;
    private boolean over;
    private int winner = -1;

    public GameState(List<Hand> hands, int firstTurn) {
        if (hands.size() != NUM_PLAYERS) {
            throw new IllegalArgumentException("paodekuai needs exactly 3 hands");
        }
        for (int s = 0; s < NUM_PLAYERS; ++s) {
            this.hands[s] = hands.get(s).copy();
            this.played[s] = new Hand();
            this.lastMoves[s] = Move.pass(s);
        }
        this.turn = firstTurn;
        this.lastPlayer = firstTurn;
    }

    /** 必须压过的牌;null 表示自由出牌 */
    public Move toBeat() {
        if (seq.isEmpty()) {
            return null;
        }
        Move last = seq.get(seq.size() - 1);
        if (!last.isPass()) {
            return last;
        }
        if (seq.size() >= 2) {
            Move prev = seq.get(seq.size() - 2);
            if (!prev.isPass()) {
                return prev;
            }
        }
        return null;
    }

    /** 当前行动玩家的全部合法动作(含引擎约束:首手带 3、报单顶牌) */
    public List<Move> legal() {
        if (over) {
            return List.of();
        }
        int seat = turn;
        Move tb = toBeat();
        boolean firstMove = seq.isEmpty();
        List<Move> out = new ArrayList<>();
        if (tb == null) {
            List<Hand> patterns = MoveGenerator.genAllMoves(hands[seat]);
            if (firstMove) {
                // 第一手必须包含 3
                patterns.removeIf(m -> m.countIndex(0) < 1);
            } else if (reported(nextSeat())) {
                // 下家报单:出单张必须顶手中最大单张
                int largest = -1;
                for (int r = 0; r < Rank.RANKS; ++r) {
                    if (hands[seat].countIndex(r) >= 1) {
                        largest = r;
                    }
                }
                int largestFinal = largest;
                patterns.removeIf(m -> {
                    MoveGenerator.MoveInfo info = MoveGenerator.detectMove(m);
                    return info.type() == CardType.SINGLE && info.rank() != largestFinal;
                });
            }
            for (Hand p : patterns) {
                out.add(MoveGenerator.toMove(p, seat));
            }
            return out;
        }
        Hand tbPattern = handOf(tb);
        for (Hand p : MoveGenerator.legalMoves(hands[seat], tbPattern)) {
            out.add(MoveGenerator.toMove(p, seat));
        }
        return out;
    }

    /** 应用动作(必须合法);处理过牌、炸弹计数、终局判定 */
    public void step(Move m) {
        if (over) {
            return;
        }
        if (!m.isPass()) {
            lastPlayer = turn;
            if (m.getType() == CardType.BOMB) {
                ++bombCount;
            }
            hands[turn].play(m);
            for (Rank r : m.getCards()) {
                played[turn].add(r);
            }
            if (hands[turn].getTotal() == 0) {
                over = true;
                winner = turn;
            }
        }
        lastMoves[turn] = m;
        seq.add(m);
        if (!over) {
            turn = (turn + 1) % NUM_PLAYERS;
        }
    }

    /** 剩一张即报单 */
    public boolean reported(int seat) {
        return hands[seat].getTotal() == 1;
    }

    /**
     * 零和结算(归一化到 16 张):赢家收两家剩牌数之和(全关 x2),
     * 输家付自己的剩牌数。
     */
    public double payoff(int seat) {
        if (!over) {
            return 0.0;
        }
        if (seat == winner) {
            double sum = 0.0;
            for (int s = 0; s < NUM_PLAYERS; ++s) {
                sum += payment(s);
            }
            return sum / Deck.CARDS_PER_PLAYER;
        }
        return -payment(seat) / Deck.CARDS_PER_PLAYER;
    }

    private double payment(int s) {
        if (s == winner) {
            return 0.0;
        }
        int left = hands[s].getTotal();
        int mult = left == Deck.CARDS_PER_PLAYER ? 2 : 1;  // 一张未出:翻倍
        return left * mult;
    }

    public PublicView getPublicView(int viewingPlayerId) {
        return new PublicView(viewingPlayerId, hands[viewingPlayerId].copy(),
                cardCounts(), Collections.unmodifiableList(new ArrayList<>(seq)),
                lastMoves.clone(), toBeat(), lastPlayer, turn, bombCount);
    }

    private int[] cardCounts() {
        int[] counts = new int[NUM_PLAYERS];
        for (int s = 0; s < NUM_PLAYERS; ++s) {
            counts[s] = hands[s].getTotal();
        }
        return counts;
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

    public int getTurn() {
        return turn;
    }

    public int getLastPlayer() {
        return lastPlayer;
    }

    public int getBombCount() {
        return bombCount;
    }

    public boolean isOver() {
        return over;
    }

    public int getWinner() {
        return winner;
    }

    public Hand getHand(int seat) {
        return hands[seat];
    }

    public List<Move> getSeq() {
        return Collections.unmodifiableList(seq);
    }

    public int prevSeat() {
        return (turn + NUM_PLAYERS - 1) % NUM_PLAYERS;
    }

    public int nextSeat() {
        return (turn + 1) % NUM_PLAYERS;
    }
}
