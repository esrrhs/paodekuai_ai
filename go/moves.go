package paodekuai

// 与训练侧一致的动作枚举与牌型判定(忠实移植 cpp/src/moves.cpp,
// 逐分支对应 Java rules.MoveGenerator)。
//
// 湖南跑得快牌型: 单张/对子/三张/三带二(恰好 5 张, 一对或两张不同单)/
// 顺子(>=5, 3..A)/连对(>=2 对)/三顺(>=2 组, 不带)/飞机(三顺+等量散单或对翅)/
// 炸弹(四张同点)。无火箭、无王、无四带二;2 永不入链。
//
// 能管必管: 应牌时若存在能压过的牌(含炸弹)则不允许过,过牌只在压不过时合法。

// 牌型编码,与 C++ MoveType 一一对应。
const (
	MTPass           = 0  // 过牌
	MTSingle         = 1  // 单张
	MTPair           = 2  // 对子
	MTTriple         = 3  // 三张
	MTBomb           = 4  // 炸弹(四张同点)
	MTTripleTwo      = 5  // 三带二(恰好 5 张)
	MTStraight       = 6  // 顺子(>=5 张)
	MTPairStraight   = 7  // 连对(>=2 对)
	MTTripleStraight = 8  // 三顺(>=2 组, 不带)
	MTPlaneSolo      = 9  // 飞机带单
	MTPlanePair      = 10 // 飞机带对
	MTWrong          = 11 // 无法归类
)

const (
	// MinStraight 顺子最少张数
	MinStraight = 5
	// MinPairChain 连对最少组数
	MinPairChain = 2
	// MinPlane 飞机/三顺最少组数
	MinPlane = 2
)

// MoveInfo 牌型信息: Type + 主牌点数索引(链牌为链的最小点数) + 链长;
// 等价 C++ MoveInfo / Java MoveInfo。
type MoveInfo struct {
	Type int
	Rank int
	Len  int
}

// presentRanks 手牌里出现过的点数(升序)。
func presentRanks(m CardSet) []int {
	var v []int
	for r := 0; r < Ranks; r++ {
		if m[r] > 0 {
			v = append(v, r)
		}
	}
	return v
}

// consecutive 是否逐点数差 1 连续。
func consecutive(ranks []int) bool {
	for i := 1; i < len(ranks); i++ {
		if ranks[i]-ranks[i-1] != 1 {
			return false
		}
	}
	return true
}

// fromRanks 把给定各点数重复 repeat 次。
func fromRanks(ranks []int, repeat int) CardSet {
	var m CardSet
	for _, r := range ranks {
		m[r] += int8(repeat)
	}
	return m
}

// enumLooseMultisets 枚举 cnt 里取 need 张散牌的全部多重集合
// (oracle 的翅膀枚举与三带二的散牌枚举共用)。
func enumLooseMultisets(cnt CardSet, rank, need int, pick *CardSet, fn func(CardSet)) {
	if need == 0 {
		fn(*pick)
		return
	}
	if rank >= Ranks {
		return
	}
	mx := need
	if int(cnt[rank]) < mx {
		mx = int(cnt[rank])
	}
	for take := 0; take <= mx; take++ {
		pick[rank] = int8(take)
		enumLooseMultisets(cnt, rank+1, need-take, pick, fn)
	}
	pick[rank] = 0
}

// enumRankCombos 从 cands 里选 need 个不同点数的组合(升序)。
func enumRankCombos(cands []int, idx, need int, sel *[]int, fn func([]int)) {
	if need == 0 {
		fn(*sel)
		return
	}
	for i := idx; i+need <= len(cands); i++ {
		*sel = append(*sel, cands[i])
		enumRankCombos(cands, i+1, need-1, sel, fn)
		*sel = (*sel)[:len(*sel)-1]
	}
}

// ---------------------------------------------------------------------------
// 动作生成器
// ---------------------------------------------------------------------------

// moveGenerator 与训练侧一致的枚举器,逐成员对应 C++ MoveGenerator。
type moveGenerator struct {
	hand CardSet
}

func newMoveGenerator(hand CardSet) *moveGenerator { return &moveGenerator{hand: hand} }

// genAll 自由出牌的全部组合(从不过);对应 C++ MoveGenerator::genAll。
func (g *moveGenerator) genAll() []CardSet {
	var out []CardSet
	out = append(out, g.genSingles()...)
	out = append(out, g.genPairs()...)
	out = append(out, g.genTriples()...)
	out = append(out, g.genBombs()...)
	out = append(out, g.genTripleTwo()...)
	out = append(out, g.genSerials(1, MinStraight, 0)...)  // 顺子
	out = append(out, g.genSerials(2, MinPairChain, 0)...) // 连对
	out = append(out, g.genSerials(3, MinPlane, 0)...)     // 三顺
	out = append(out, g.genPlaneSolo(0)...)
	out = append(out, g.genPlanePair(0)...)
	return dedupeMoves(out)
}

// genByType 某一类的全部动作(length > 0 时链长限定为 length)。
func (g *moveGenerator) genByType(typ, length int) []CardSet {
	var out []CardSet
	switch typ {
	case MTSingle:
		out = g.genSingles()
	case MTPair:
		out = g.genPairs()
	case MTTriple:
		out = g.genTriples()
	case MTBomb:
		out = g.genBombs()
	case MTTripleTwo:
		out = g.genTripleTwo()
	case MTStraight:
		out = g.genSerials(1, MinStraight, length)
	case MTPairStraight:
		out = g.genSerials(2, MinPairChain, length)
	case MTTripleStraight:
		out = g.genSerials(3, MinPlane, length)
	case MTPlaneSolo:
		out = g.genPlaneSolo(length)
	case MTPlanePair:
		out = g.genPlanePair(length)
	}
	return dedupeMoves(out)
}

func (g *moveGenerator) genSingles() []CardSet {
	r := make([]CardSet, 0, Ranks)
	for rnk := 0; rnk < Ranks; rnk++ {
		if g.hand[rnk] >= 1 {
			var m CardSet
			m[rnk] = 1
			r = append(r, m)
		}
	}
	return r
}

func (g *moveGenerator) genPairs() []CardSet {
	r := make([]CardSet, 0, Ranks)
	for rnk := 0; rnk < Ranks; rnk++ {
		if g.hand[rnk] >= 2 {
			var m CardSet
			m[rnk] = 2
			r = append(r, m)
		}
	}
	return r
}

func (g *moveGenerator) genTriples() []CardSet {
	r := make([]CardSet, 0, Ranks)
	for rnk := 0; rnk < Ranks; rnk++ {
		if g.hand[rnk] >= 3 {
			var m CardSet
			m[rnk] = 3
			r = append(r, m)
		}
	}
	return r
}

func (g *moveGenerator) genBombs() []CardSet {
	var r []CardSet
	for rnk := 0; rnk < Ranks; rnk++ {
		if g.hand[rnk] == 4 {
			var m CardSet
			m[rnk] = 4
			r = append(r, m)
		}
	}
	return r
}

// genTripleTwo 三带二: 两张散牌(一对或两张不同单), 散牌不用三张本身的点数,
// 因此 3333 只会是炸弹;对应 C++ MoveGenerator::genTripleTwo。
func (g *moveGenerator) genTripleTwo() []CardSet {
	var r []CardSet
	for t := 0; t < Ranks; t++ {
		if g.hand[t] < 3 {
			continue
		}
		rest := g.hand
		rest[t] = 0
		var pick CardSet
		enumLooseMultisets(rest, 0, 2, &pick, func(w CardSet) {
			var m CardSet
			m[t] = 3
			for rr := 0; rr < Ranks; rr++ {
				if w[rr] != 0 {
					m[rr] += w[rr]
				}
			}
			r = append(r, m)
		})
	}
	return r
}

// genSerials repeat = 1 单张链 / 2 对子链 / 3 三张链;链只覆盖 3..A(2 不入链)。
// fixedLen 为 0 时枚举所有长度,否则只枚举该长度;对应 C++ genSerials。
func (g *moveGenerator) genSerials(repeat, minLen, fixedLen int) []CardSet {
	var ranks []int
	for r := 0; r < ChainRanks; r++ {
		if int(g.hand[r]) >= repeat {
			ranks = append(ranks, r)
		}
	}
	// runs 每项为 {起始下标, 段长}
	type run struct{ start, longest int }
	var runs []run
	for i := 0; i < len(ranks); {
		j := i
		for j+1 < len(ranks) && ranks[j+1]-ranks[j] == 1 {
			j++
		}
		runs = append(runs, run{i, j - i + 1})
		i = j + 1
	}
	var out []CardSet
	for _, rn := range runs {
		start, longest := rn.start, rn.longest
		if fixedLen > 0 {
			if longest < fixedLen {
				continue
			}
			for idx := 0; idx+fixedLen <= longest; idx++ {
				out = append(out, fromRanks(ranks[start+idx:start+idx+fixedLen], repeat))
			}
		} else {
			for length := minLen; length <= longest; length++ {
				for idx := 0; idx+length <= longest; idx++ {
					out = append(out, fromRanks(ranks[start+idx:start+idx+length], repeat))
				}
			}
		}
	}
	return out
}

// genPlaneSolo 飞机带单: 每组三张带一张散牌;散牌可含链上点数的第 4 张;
// 对应 C++ genPlaneSolo。
func (g *moveGenerator) genPlaneSolo(fixedLen int) []CardSet {
	planes := g.genSerials(3, MinPlane, fixedLen)
	var out []CardSet
	for _, pl := range planes {
		chainLen := 0
		rest := g.hand
		for r := 0; r < Ranks; r++ {
			if pl[r] == 3 {
				chainLen++
				rest[r] -= 3
			}
		}
		var pick CardSet
		enumLooseMultisets(rest, 0, chainLen, &pick, func(w CardSet) {
			m := pl
			for r := 0; r < Ranks; r++ {
				if w[r] != 0 {
					m[r] += w[r]
				}
			}
			out = append(out, m)
		})
	}
	return out
}

// genPlanePair 飞机带对: 每组三张带一个不同点数的对子;对应 C++ genPlanePair。
func (g *moveGenerator) genPlanePair(fixedLen int) []CardSet {
	planes := g.genSerials(3, MinPlane, fixedLen)
	var out []CardSet
	for _, pl := range planes {
		chainLen := 0
		var pairCands []int
		for r := 0; r < Ranks; r++ {
			if pl[r] == 3 {
				chainLen++
				continue
			}
			if g.hand[r] >= 2 {
				pairCands = append(pairCands, r)
			}
		}
		var sel []int
		enumRankCombos(pairCands, 0, chainLen, &sel, func(ps []int) {
			m := pl
			for _, r := range ps {
				m[r] += 2
			}
			out = append(out, m)
		})
	}
	return out
}

// dedupeMoves 按 64 位指纹去重,保留首次出现顺序;等价 C++ MoveGenerator::dedupe。
func dedupeMoves(moves []CardSet) []CardSet {
	seen := make(map[uint64]struct{}, len(moves)*2)
	out := make([]CardSet, 0, len(moves))
	for _, m := range moves {
		k := m.Key()
		if _, ok := seen[k]; ok {
			continue
		}
		seen[k] = struct{}{}
		out = append(out, m)
	}
	return out
}

// ---------------------------------------------------------------------------

// detectMove 判定一手工牌,无法归类时 Type 为 MTWrong;逐分支对应 C++ detectMove。
func detectMove(m CardSet) MoveInfo {
	info := MoveInfo{Type: MTWrong, Rank: -1, Len: 1}
	size := m.Total()

	if size == 0 {
		info.Type = MTPass
		return info
	}
	if size == 1 {
		for r := 0; r < Ranks; r++ {
			if m[r] != 0 {
				info.Type = MTSingle
				info.Rank = r
				return info
			}
		}
	}
	if size == 2 {
		for r := 0; r < Ranks; r++ {
			if m[r] == 2 {
				info.Type = MTPair
				info.Rank = r
				return info
			}
		}
		return info // WRONG
	}
	if size == 3 {
		for r := 0; r < Ranks; r++ {
			if m[r] == 3 {
				info.Type = MTTriple
				info.Rank = r
				return info
			}
		}
		return info
	}
	if size == 4 {
		for r := 0; r < Ranks; r++ {
			if m[r] == 4 {
				info.Type = MTBomb
				info.Rank = r
				return info
			}
		}
		// 两连对(如 3344)恰好四张
		pr := presentRanks(m)
		if len(pr) == 2 && m[pr[0]] == 2 && m[pr[1]] == 2 &&
			pr[1]-pr[0] == 1 && pr[1] < ChainRanks {
			info.Type = MTPairStraight
			info.Rank = pr[0]
			info.Len = 2
			return info
		}
		return info
	}
	if size == 5 {
		// 3..A 内五个连续点数的顺子
		pr := presentRanks(m)
		if len(pr) == 5 && pr[len(pr)-1] < ChainRanks && consecutive(pr) {
			info.Type = MTStraight
			info.Rank = pr[0]
			info.Len = 5
			return info
		}
		// 三带二(一对或两张单)
		triRank := -1
		bad := false
		for r := 0; r < Ranks; r++ {
			if m[r] == 3 {
				if triRank >= 0 {
					bad = true
					break
				}
				triRank = r
			} else if m[r] != 0 && m[r] != 1 && m[r] != 2 {
				bad = true
				break
			}
		}
		if !bad && triRank >= 0 {
			info.Type = MTTripleTwo
			info.Rank = triRank
		}
		return info
	}

	// size >= 6
	pr := presentRanks(m)

	// 顺子: 全部单张、连续、在 3..A 内
	if len(pr) == size && pr[len(pr)-1] < ChainRanks && consecutive(pr) {
		info.Type = MTStraight
		info.Rank = pr[0]
		info.Len = size
		return info
	}
	// 连对
	allPairs := true
	for _, r := range pr {
		if m[r] != 2 {
			allPairs = false
			break
		}
	}
	if allPairs && pr[len(pr)-1] < ChainRanks && consecutive(pr) {
		info.Type = MTPairStraight
		info.Rank = pr[0]
		info.Len = len(pr)
		return info
	}
	// 三顺(不带)
	allTriples := true
	for _, r := range pr {
		if m[r] != 3 {
			allTriples = false
			break
		}
	}
	if allTriples && pr[len(pr)-1] < ChainRanks && consecutive(pr) {
		info.Type = MTTripleStraight
		info.Rank = pr[0]
		info.Len = len(pr)
		return info
	}

	// 飞机: 点数张数 >= 3 的连续段构成链,余牌是翅
	// (某点数 4 张时 3 张入链、1 张做翅)。每段内的所有子链都检查,长链优先。
	for start := 0; start < ChainRanks; {
		if m[start] < 3 {
			start++
			continue
		}
		end := start
		for end+1 < ChainRanks && m[end+1] >= 3 {
			end++
		}
		for L := end - start + 1; L >= MinPlane; L-- {
			for s := start; s+L <= end+1; s++ {
				wings := m
				for r := s; r < s+L; r++ {
					wings[r] -= 3
				}
				wTotal := wings.Total()
				if wTotal == L {
					info.Type = MTPlaneSolo
					info.Rank = s
					info.Len = L
					return info
				}
				if wTotal == 2*L {
					pairsOnly := true
					for r := 0; r < Ranks; r++ {
						if wings[r] != 0 && wings[r] != 2 {
							pairsOnly = false
							break
						}
					}
					if pairsOnly {
						info.Type = MTPlanePair
						info.Rank = s
						info.Len = L
						return info
					}
				}
			}
		}
		start = end + 1
	}
	return info // WRONG
}

// beats 判断 move 能否压过 rival(双方都假定是合法牌型);对应 C++ beats。
func beats(move, rival CardSet) bool {
	a := detectMove(move)
	b := detectMove(rival)
	if a.Type == MTWrong || b.Type == MTWrong {
		return false
	}
	if a.Type == MTBomb && b.Type != MTBomb {
		return true
	}
	if a.Type == MTBomb && b.Type == MTBomb {
		return a.Rank > b.Rank
	}
	if a.Type != b.Type {
		return false
	}
	switch a.Type {
	case MTPlaneSolo, MTPlanePair, MTStraight, MTPairStraight, MTTripleStraight:
		return a.Len == b.Len && a.Rank > b.Rank
	}
	return a.Rank > b.Rank // 单张/对子/三张/三带二
}

// isBomb 该手是否炸弹。
func isBomb(m CardSet) bool {
	return m.Total() == 4 && detectMove(m).Type == MTBomb
}

// genAllMoves 自由出牌的全部组合(从不过);对应 C++ genAllMoves。
func genAllMoves(hand CardSet) []CardSet {
	return newMoveGenerator(hand).genAll()
}

// legalMoves 应牌时的合法动作(能管必管)。toBeat 为空时自由出牌(不含过)。
// 压不过时唯一动作是过牌;对应 C++ legalMoves。
func legalMoves(hand, toBeat CardSet) []CardSet {
	if toBeat.Empty() {
		return genAllMoves(hand)
	}
	rival := detectMove(toBeat)
	if rival.Type == MTWrong {
		return genAllMoves(hand)
	}
	gen := newMoveGenerator(hand)
	var moves []CardSet
	if rival.Type == MTBomb {
		for _, m := range gen.genByType(MTBomb, 0) {
			if beats(m, toBeat) {
				moves = append(moves, m)
			}
		}
	} else {
		for _, m := range gen.genByType(rival.Type, rival.Len) {
			if beats(m, toBeat) {
				moves = append(moves, m)
			}
		}
		// 炸弹压所有非炸弹牌型(能管必管含炸弹)
		moves = append(moves, gen.genByType(MTBomb, 0)...)
	}
	if len(moves) == 0 {
		moves = append(moves, CardSet{}) // 压不过: 过牌
	}
	return moves
}
