package paodekuai

import "sync"

// 手数下界 oracle: 一手牌最少几步出完。忠实移植 cpp/src/oracle.cpp,
// 对应 Java nn.Oracle。该值直接参与神经网络输入特征(候选动作的
// minSteps(出完后)/16),因此必须与训练侧逐位一致。
//
// 无链手牌按"组计数"(a 单张 / b 对子 / c 三张 / d 炸弹)做 DP 查表,
// 顺子/连对/三顺/飞机由外层记忆化 DFS 枚举。

const (
	kMaxA = 17
	kMaxB = 9
	kMaxC = 6
	kMaxD = 5

	fImpossible = 100
)

// fDPTable F(a,b,c,d) 打平成一维, -1 表示尚未计算;等价 C++ F。
var fDPTable [kMaxA * kMaxB * kMaxC * kMaxD]int8

var fOnce sync.Once

func fIndex(a, b, c, d int) int {
	return ((a*kMaxB+b)*kMaxC+c)*kMaxD + d
}

func fSet(a, b, c, d, value int) {
	fDPTable[fIndex(a, b, c, d)] = int8(value)
}

func fGet(a, b, c, d int) int {
	if a < 0 || b < 0 || c < 0 || d < 0 {
		return fImpossible
	}
	if a >= kMaxA || b >= kMaxB || c >= kMaxC || d >= kMaxD {
		return a + b + c + d
	}
	v := int(fDPTable[fIndex(a, b, c, d)])
	if v < 0 {
		return a + b + c + d
	}
	return v
}

// initFTable 建组计数表;等价 C++ initFTable(跑得快只有"三带二"一种合并出牌,
// 炸弹不带翅,也没有火箭)。
func initFTable() {
	for i := range fDPTable {
		fDPTable[i] = -1
	}
	fSet(0, 0, 0, 0, 0)
	for total := 1; total <= HandSize; total++ {
		for a := 0; a < kMaxA; a++ {
			for b := 0; b < kMaxB; b++ {
				for c := 0; c < kMaxC; c++ {
					for d := 0; d < kMaxD; d++ {
						if a+2*b+3*c+4*d != total {
							continue
						}
						best := a + b + c + d
						if a >= 1 {
							best = min(best, 1+fGet(a-1, b, c, d))
						}
						if b >= 1 {
							best = min(best, 1+fGet(a, b-1, c, d))
						}
						if c >= 1 {
							best = min(best, 1+fGet(a, b, c-1, d))
						}
						if d >= 1 {
							best = min(best, 1+fGet(a, b, c, d-1))
						}
						// 三带二
						if c >= 1 {
							enumLooseKickers(a, b, c-1, d, 2, func(na, nb, nc, nd int) {
								best = min(best, 1+fGet(na, nb, nc, nd))
							})
						}
						fSet(a, b, c, d, best)
					}
				}
			}
		}
	}
}

func fDP(a, b, c, d int) int {
	fOnce.Do(initFTable)
	return fGet(a, b, c, d)
}

// enumLooseKickers 枚举"拿 need(<=2) 张散牌"的所有拆组方式,拆剩的组自动降档
// (对子用掉 1 张剩单张、三张用掉 1 张剩对子、炸弹用掉 1 张剩三张);
// 回调收到新的组计数;等价 C++ enumLooseKickers。
func enumLooseKickers(a, b, c, d, need int, fn func(a, b, c, d int)) {
	for s := 0; s <= min(need, a); s++ {
		r1 := need - s
		if r1 == 0 {
			fn(a-s, b, c, d)
			continue
		}
		// 对子: bp 个被拆的对子, pu 张从中拿走(每对 1..2 张)
		for bp := 0; bp <= min(b, r1); bp++ {
			for pu := max(bp, 0); pu <= min(2*bp, r1); pu++ {
				r2 := r1 - pu
				pairLeftSingles := 2*bp - pu
				if r2 == 0 {
					fn(a-s+pairLeftSingles, b-bp, c, d)
					continue
				}
				// 三张: bt 个被拆, tu 张从中拿走(每组 1..3 张)
				for bt := 0; bt <= min(c, r2); bt++ {
					for tu := bt; tu <= min(3*bt, r2); tu++ {
						r3 := r2 - tu
						for t2 := 0; t2 <= bt; t2++ {
							for t1 := 0; t1+t2 <= bt; t1++ {
								t3 := bt - t1 - t2
								if t1+2*t2+3*t3 != tu {
									continue
								}
								r4 := r3
								if r4 == 0 {
									fn(a-s+pairLeftSingles+t2, b-bp+t1, c-bt, d)
									continue
								}
								// 炸弹: bd 个被拆, bu 张从中拿走(每组 1..4 张)
								for bd := 0; bd <= min(d, r4); bd++ {
									for bu := bd; bu <= min(4*bd, r4); bu++ {
										if bu != r4 {
											continue
										}
										for u3 := 0; u3 <= bd; u3++ {
											for u2 := 0; u2+u3 <= bd; u2++ {
												for u1 := 0; u1+u2+u3 <= bd; u1++ {
													u4 := bd - u1 - u2 - u3
													if u1+2*u2+3*u3+4*u4 != bu {
														continue
													}
													fn(a-s+pairLeftSingles+t2+u3,
														b-bp+t1+u2, c-bt+u1, d-bd)
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

// ---------------------------------------------------------------------------
// 显式点数张数上的记忆化 DFS(直接映射缓存, 与 C++/Java 同为 65536 槽)。
// MinSteps 是纯函数, 缓存只影响速度不影响结果, Go 侧用一把锁保护共享缓存。
// ---------------------------------------------------------------------------

const (
	cacheSlots = 65536
	cacheMask  = cacheSlots - 1
)

type oracleMemo struct {
	keys [cacheSlots]uint64
	vals [cacheSlots]int8
}

var (
	oracleMu sync.Mutex
	memo     = &oracleMemo{}
)

// hashKey MurmurHash3 64 位混淆;与 C++ 位一致(全程 uint64 运算)。
func hashKey(k uint64) uint64 {
	k ^= k >> 33
	k *= 0xff51afd7ed558ccd
	k ^= k >> 33
	k *= 0xc4ceb9fe1a85ec53
	k ^= k >> 33
	return k & cacheMask
}

// hasPotentialChain 是否存在可能的顺子/连对/三顺链(3..A),用于走无链快路径。
func hasPotentialChain(cnt CardSet) bool {
	maxSolo, curSolo := 0, 0
	maxPair, curPair := 0, 0
	maxTrio, curTrio := 0, 0
	for r := 0; r < ChainRanks; r++ {
		c := int(cnt[r])
		if c >= 1 {
			curSolo++
			maxSolo = max(maxSolo, curSolo)
		} else {
			curSolo = 0
		}
		if c >= 2 {
			curPair++
			maxPair = max(maxPair, curPair)
		} else {
			curPair = 0
		}
		if c >= 3 {
			curTrio++
			maxTrio = max(maxTrio, curTrio)
		} else {
			curTrio = 0
		}
	}
	return maxSolo >= MinStraight || maxPair >= MinPairChain || maxTrio >= MinPlane
}

// lowerBound 忽略链牌的组数下界。
func lowerBound(cnt CardSet) int {
	a, b, c, d := 0, 0, 0, 0
	for r := 0; r < Ranks; r++ {
		switch cnt[r] {
		case 1:
			a++
		case 2:
			b++
		case 3:
			c++
		case 4:
			d++
		}
	}
	return fDP(a, b, c, d)
}

// solve 记忆化的最少步数 DFS;等价 C++ solve。
func solve(cnt CardSet) int {
	key := cnt.Key()
	if key == 0 {
		return 0
	}
	h := hashKey(key)
	if memo.keys[h] == key && memo.vals[h] > 0 {
		return int(memo.vals[h])
	}

	// 快路径: 不可能有链时组数下界即为精确解
	if !hasPotentialChain(cnt) {
		ans := lowerBound(cnt)
		memo.keys[h] = key
		memo.vals[h] = int8(ans)
		return ans
	}

	// "<="(不是"<"): 无链下界可能恰好等于走链的步数, 相等的估计也必须继续探索;
	// rem 的牌数严格更少, DFS 必然终止。
	best := lowerBound(cnt)

	enumerateChains(cnt, 1, MinStraight, false, &best)
	enumerateChains(cnt, 2, MinPairChain, false, &best)
	enumerateChains(cnt, 3, MinPlane, true, &best)

	memo.keys[h] = key
	memo.vals[h] = int8(best)
	return best
}

// enumerateChains 在连续可连的区段上枚举所有链长与起点,飞机再枚举带翅;
// 等价 C++ solve 内的 enumerateChains lambda。
func enumerateChains(cnt CardSet, repeat, minLen int, withWings bool, best *int) {
	i := 0
	for i < ChainRanks {
		if int(cnt[i]) < repeat {
			i++
			continue
		}
		j := i
		for j+1 < ChainRanks && int(cnt[j+1]) >= repeat {
			j++
		}
		runLen := j - i + 1
		for length := minLen; length <= runLen; length++ {
			for s := i; s+length <= j+1; s++ {
				rem := cnt
				for x := 0; x < length; x++ {
					rem[s+x] -= int8(repeat)
				}
				if 1+lowerBound(rem) <= *best {
					if v := 1 + solve(rem); v < *best {
						*best = v
					}
				}
				if withWings {
					// 飞机 + length 个散单翅
					if 4*length <= HandSize {
						var pick CardSet
						enumLooseMultisets(rem, 0, length, &pick, func(w CardSet) {
							after := rem
							for r := 0; r < Ranks; r++ {
								after[r] -= w[r]
							}
							if 1+lowerBound(after) <= *best {
								if v := 1 + solve(after); v < *best {
									*best = v
								}
							}
						})
					}
					// 飞机 + length 个对翅(不同点数)
					if 5*length <= HandSize {
						var pairRanks []int
						for r := 0; r < Ranks; r++ {
							if rem[r] >= 2 {
								pairRanks = append(pairRanks, r)
							}
						}
						if len(pairRanks) >= length {
							choosePairCombos(rem, pairRanks, length, 0, 0,
								make([]int, length), best)
						}
					}
				}
			}
		}
		i = j + 1
	}
}

// choosePairCombos 飞机带对: 从可对的点数里选 length 个不同点数的组合。
func choosePairCombos(rem CardSet, pairRanks []int, length, idx, start int,
	sel []int, best *int) {
	if idx == length {
		after := rem
		for _, r := range sel {
			after[r] -= 2
		}
		if 1+lowerBound(after) <= *best {
			if v := 1 + solve(after); v < *best {
				*best = v
			}
		}
		return
	}
	for x := start; x+(length-idx-1) < len(pairRanks); x++ {
		sel[idx] = pairRanks[x]
		choosePairCombos(rem, pairRanks, length, idx+1, x+1, sel, best)
	}
}

// minSteps 一手牌的最少出牌步数;等价 C++ minSteps。
func minSteps(hand CardSet) int {
	fOnce.Do(initFTable)
	oracleMu.Lock()
	defer oracleMu.Unlock()
	return solve(hand)
}
