package com.paodekuai.nn;

import com.paodekuai.model.Hand;
import com.paodekuai.model.Rank;

import java.util.ArrayList;
import java.util.List;

/**
 * 手数下界 oracle:一副牌最少几手出完(PerfectDou 论文附录 E.1 算法 1 的
 * 跑得快版),逐分支移植自训练侧 pdk/oracle.cpp。
 *
 * 外层是记忆化 DFS(枚举顺子/连对/飞机),内层是按"单张组/对子组/三张组/
 * 炸弹组"计数抽象的 DP 下界 F(a,b,c,d)。特征里的 minSteps 必须与训练侧
 * 逐位一致,否则特征错位。
 */
public final class Oracle {

    private static final int MAX_A = 17;
    private static final int MAX_B = 9;
    private static final int MAX_C = 6;
    private static final int MAX_D = 5;
    private static final int HAND_SIZE = 16;

    private static final byte[] F = new byte[MAX_A * MAX_B * MAX_C * MAX_D];
    private static boolean fReady = false;

    private Oracle() {
    }

    private static int fIndex(int a, int b, int c, int d) {
        return ((a * MAX_B + b) * MAX_C + c) * MAX_D + d;
    }

    private static int fGet(int a, int b, int c, int d) {
        if (a < 0 || b < 0 || c < 0 || d < 0) {
            return 100;
        }
        if (a >= MAX_A || b >= MAX_B || c >= MAX_C || d >= MAX_D) {
            return a + b + c + d;
        }
        int v = F[fIndex(a, b, c, d)];
        return v < 0 ? a + b + c + d : v;
    }

    private static void initFTable() {
        java.util.Arrays.fill(F, (byte) -1);
        F[fIndex(0, 0, 0, 0)] = 0;
        for (int total = 1; total <= HAND_SIZE; ++total) {
            for (int a = 0; a < MAX_A; ++a) {
                for (int b = 0; b < MAX_B; ++b) {
                    for (int c = 0; c < MAX_C; ++c) {
                        for (int d = 0; d < MAX_D; ++d) {
                            if (a + 2 * b + 3 * c + 4 * d != total) {
                                continue;
                            }
                            int best = a + b + c + d;
                            if (a >= 1) {
                                best = Math.min(best, 1 + fGet(a - 1, b, c, d));
                            }
                            if (b >= 1) {
                                best = Math.min(best, 1 + fGet(a, b - 1, c, d));
                            }
                            if (c >= 1) {
                                best = Math.min(best, 1 + fGet(a, b, c - 1, d));
                            }
                            if (d >= 1) {
                                best = Math.min(best, 1 + fGet(a, b, c, d - 1));
                            }
                            if (c >= 1) {
                                // 三张 + 2 张散牌
                                List<int[]> kicks = new ArrayList<>();
                                enumLooseKickers(a, b, c - 1, d, 2, kicks);
                                for (int[] k : kicks) {
                                    best = Math.min(best, 1 + fGet(k[0], k[1], k[2], k[3]));
                                }
                            }
                            F[fIndex(a, b, c, d)] = (byte) best;
                        }
                    }
                }
            }
        }
        fReady = true;
    }

    private static int fDP(int a, int b, int c, int d) {
        if (a < 0 || b < 0 || c < 0 || d < 0) {
            return 100;
        }
        if (!fReady) {
            initFTable();
        }
        if (a >= MAX_A || b >= MAX_B || c >= MAX_C || d >= MAX_D) {
            return a + b + c + d;
        }
        int v = F[fIndex(a, b, c, d)];
        return v < 0 ? a + b + c + d : v;
    }

    /**
     * 枚举从 (a,b,c,d) 组计数里拿 need(<=2) 张散牌后得到的新组计数。
     * 被拆的组降级:三张拿 1 张剩对子,炸弹拿 1 张剩三张,以此类推。
     */
    private static void enumLooseKickers(int a, int b, int c, int d, int need,
                                         List<int[]> out) {
        for (int s = 0; s <= Math.min(need, a); ++s) {
            int r1 = need - s;
            if (r1 == 0) {
                out.add(new int[]{a - s, b, c, d});
                continue;
            }
            for (int bp = 0; bp <= Math.min(b, r1); ++bp) {
                for (int pu = Math.max(bp, 0); pu <= Math.min(2 * bp, r1); ++pu) {
                    int r2 = r1 - pu;
                    int pairLeftSingles = 2 * bp - pu;
                    if (r2 == 0) {
                        out.add(new int[]{a - s + pairLeftSingles, b - bp, c, d});
                        continue;
                    }
                    for (int bt = 0; bt <= Math.min(c, r2); ++bt) {
                        for (int tu = bt; tu <= Math.min(3 * bt, r2); ++tu) {
                            int r3 = r2 - tu;
                            for (int t2 = 0; t2 <= bt; ++t2) {
                                for (int t1 = 0; t1 + t2 <= bt; ++t1) {
                                    int t3 = bt - t1 - t2;
                                    if (t1 + 2 * t2 + 3 * t3 != tu) {
                                        continue;
                                    }
                                    int r4 = r3;
                                    if (r4 == 0) {
                                        out.add(new int[]{a - s + pairLeftSingles + t2,
                                                b - bp + t1, c - bt, d});
                                        continue;
                                    }
                                    for (int bd = 0; bd <= Math.min(d, r4); ++bd) {
                                        for (int bu = bd; bu <= Math.min(4 * bd, r4); ++bu) {
                                            if (bu != r4) {
                                                continue;
                                            }
                                            for (int u3 = 0; u3 <= bd; ++u3) {
                                                for (int u2 = 0; u2 + u3 <= bd; ++u2) {
                                                    for (int u1 = 0; u1 + u2 + u3 <= bd; ++u1) {
                                                        int u4 = bd - u1 - u2 - u3;
                                                        if (u1 + 2 * u2 + 3 * u3 + 4 * u4 != bu) {
                                                            continue;
                                                        }
                                                        out.add(new int[]{
                                                                a - s + pairLeftSingles + t2 + u3,
                                                                b - bp + t1 + u2,
                                                                c - bt + u1, d - bd});
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 记忆化 DFS(线程局部直接映射缓存)
    // ------------------------------------------------------------------

    private static final int CACHE_SLOTS = 65536;
    private static final int CACHE_MASK = CACHE_SLOTS - 1;

    private static final class Memo {
        final long[] keys = new long[CACHE_SLOTS];
        final byte[] vals = new byte[CACHE_SLOTS];
    }

    private static final ThreadLocal<Memo> MEMO = ThreadLocal.withInitial(Memo::new);

    private static long packKey(int[] cnt) {
        long k = 0;
        for (int r = 0; r < Rank.RANKS; ++r) {
            k |= (long) cnt[r] << (3 * r);
        }
        return k;
    }

    private static int hashKey(long k) {
        k ^= (k >>> 33);
        k *= 0xff51afd7ed558ccdL;
        k ^= (k >>> 33);
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= (k >>> 33);
        return (int) (k & CACHE_MASK);
    }

    /** 是否存在顺子/连对/三顺的可能(3..A) */
    private static boolean hasPotentialChain(int[] cnt) {
        int maxSolo = 0, curSolo = 0;
        int maxPair = 0, curPair = 0;
        int maxTrio = 0, curTrio = 0;
        for (int r = 0; r < Rank.CHAIN_RANKS; ++r) {
            int c = cnt[r];
            if (c >= 1) {
                maxSolo = Math.max(maxSolo, ++curSolo);
            } else {
                curSolo = 0;
            }
            if (c >= 2) {
                maxPair = Math.max(maxPair, ++curPair);
            } else {
                curPair = 0;
            }
            if (c >= 3) {
                maxTrio = Math.max(maxTrio, ++curTrio);
            } else {
                curTrio = 0;
            }
        }
        return maxSolo >= CardTypeMin.STRAIGHT || maxPair >= CardTypeMin.PAIR_CHAIN
                || maxTrio >= CardTypeMin.PLANE;
    }

    private static final class CardTypeMin {
        static final int STRAIGHT = 5;
        static final int PAIR_CHAIN = 2;
        static final int PLANE = 2;
    }

    /** 忽略链型的下界 */
    private static int lowerBound(int[] cnt) {
        int a = 0, b = 0, c = 0, d = 0;
        for (int r = 0; r < Rank.RANKS; ++r) {
            if (cnt[r] == 1) {
                ++a;
            } else if (cnt[r] == 2) {
                ++b;
            } else if (cnt[r] == 3) {
                ++c;
            } else if (cnt[r] == 4) {
                ++d;
            }
        }
        return fDP(a, b, c, d);
    }

    /** 枚举 cnt 里 need 张牌的全部多重集 */
    private interface WingFn {
        void accept(int[] multiset);
    }

    private static void enumWingMultisets(int[] cnt, int rank, int need, int[] pick,
                                          WingFn fn) {
        if (need == 0) {
            fn.accept(pick.clone());
            return;
        }
        if (rank >= Rank.RANKS) {
            return;
        }
        int mx = Math.min(cnt[rank], need);
        for (int take = 0; take <= mx; ++take) {
            pick[rank] = take;
            enumWingMultisets(cnt, rank + 1, need - take, pick, fn);
            pick[rank] = 0;
        }
    }

    private static int solve(int[] cnt) {
        long key = packKey(cnt);
        if (key == 0) {
            return 0;
        }
        Memo memo = MEMO.get();
        int h = hashKey(key);
        if (memo.keys[h] == key && memo.vals[h] > 0) {
            return memo.vals[h];
        }

        if (!hasPotentialChain(cnt)) {
            int ans = lowerBound(cnt);
            memo.keys[h] = key;
            memo.vals[h] = (byte) ans;
            return ans;
        }

        int[] bestBox = {lowerBound(cnt)};

        for (int repeat = 1; repeat <= 3; ++repeat) {
            int minLen = switch (repeat) {
                case 1 -> 5;
                case 2 -> 2;
                default -> 2;
            };
            boolean withWings = repeat == 3;
            int i = 0;
            while (i < Rank.CHAIN_RANKS) {
                if (cnt[i] < repeat) {
                    ++i;
                    continue;
                }
                int j = i;
                while (j + 1 < Rank.CHAIN_RANKS && cnt[j + 1] >= repeat) {
                    ++j;
                }
                int runLen = j - i + 1;
                for (int len = minLen; len <= runLen; ++len) {
                    for (int s = i; s + len <= j + 1; ++s) {
                        int[] rem = cnt.clone();
                        for (int x = 0; x < len; ++x) {
                            rem[s + x] -= repeat;
                        }
                        // "<=":非链下界可能与链路径打平,相等也必须探索
                        if (1 + lowerBound(rem) <= bestBox[0]) {
                            bestBox[0] = Math.min(bestBox[0], 1 + solve(rem));
                        }
                        if (withWings) {
                            // 飞机 + len 张单翅
                            if (4 * len <= HAND_SIZE) {
                                int[] pick = new int[Rank.RANKS];
                                enumWingMultisets(rem, 0, len, pick, w -> {
                                    int[] after = rem.clone();
                                    for (int r = 0; r < Rank.RANKS; ++r) {
                                        after[r] -= w[r];
                                    }
                                    if (1 + lowerBound(after) <= bestBox[0]) {
                                        bestBox[0] = Math.min(bestBox[0], 1 + solve(after));
                                    }
                                });
                            }
                            // 飞机 + len 个对翅(不同点数)
                            if (5 * len <= HAND_SIZE) {
                                List<Integer> pairRanks = new ArrayList<>();
                                for (int r = 0; r < Rank.RANKS; ++r) {
                                    if (rem[r] >= 2) {
                                        pairRanks.add(r);
                                    }
                                }
                                int np = pairRanks.size();
                                if (np >= len) {
                                    int[] sel = new int[len];
                                    combos(pairRanks, 0, 0, len, sel, rem, holder -> {
                                        if (1 + lowerBound(holder) <= bestBox[0]) {
                                            bestBox[0] = Math.min(bestBox[0], 1 + solve(holder));
                                        }
                                    });
                                }
                            }
                        }
                    }
                }
                i = j + 1;
            }
        }

        int best = bestBox[0];
        memo.keys[h] = key;
        memo.vals[h] = (byte) best;
        return best;
    }

    private interface ComboFn {
        void accept(int[] after);
    }

    private static void combos(List<Integer> pairRanks, int idx, int start, int len,
                               int[] sel, int[] rem, ComboFn fn) {
        if (idx == len) {
            int[] after = rem.clone();
            for (int r : sel) {
                after[r] -= 2;
            }
            fn.accept(after);
            return;
        }
        for (int x = start; x + (len - idx - 1) < pairRanks.size(); ++x) {
            sel[idx] = pairRanks.get(x);
            combos(pairRanks, idx + 1, x + 1, len, sel, rem, fn);
        }
    }

    /** 手牌最少出完手数;结果按线程记忆化 */
    public static int minSteps(Hand hand) {
        int[] cnt = new int[Rank.RANKS];
        for (int r = 0; r < Rank.RANKS; ++r) {
            cnt[r] = hand.countIndex(r);
        }
        return solve(cnt);
    }

    /** 清空线程局部缓存(长跑服务可选) */
    public static void clearCache() {
        MEMO.remove();
    }
}
