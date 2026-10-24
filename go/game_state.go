package paodekuai

// 跑得快引擎状态机(湖南三人 16 张规则);忠实移植 cpp/src/game_state.cpp,
// 对应 Java game.GameState。
//
// 48 张牌全发(每人 16 张, 无底牌),均匀指定一张 3、其持有者先出且首手必须含 3。
// 能管必管: 应牌时压不过才能过。报单: 只剩一张牌的玩家被"报单",
// 下家报单时出单必须顶手里最大的单。座位完全对称,出牌顺序 0 -> 1 -> 2 -> 0。

// GameState 跑得快牌局状态机。
type GameState struct {
	Hand     [3]CardSet
	Played   [3]CardSet
	LastMove [3]CardSet // 各家最近一手(过牌为空)
	Seq      []MoveRecord

	Turn       int // 行动座位
	LastPlayer int // 最近一个非过牌动作的座位
	BombCount  int
	Over       bool
	Winner     int
}

// NewGameState 由三家手牌与先手座位开局(golden 回放用);等价 Java
// GameState(List<Hand>, int) 与 C++ 测试里的字段直填。
func NewGameState(hands [3]CardSet, lead int) *GameState {
	g := &GameState{Hand: hands, Turn: lead, LastPlayer: lead, Winner: -1}
	return g
}

// Deal 发一副牌并指定先手: 均匀指定四张 3 之一,其持有者先出。
// 等价 C++ GameState::deal / Java GameState.deal。
func Deal(rng *Rng) *GameState {
	g := &GameState{Winner: -1}
	deck := ShuffledDeck(rng)
	for s := 0; s < 3; s++ {
		for i := s * HandSize; i < (s+1)*HandSize; i++ {
			g.Hand[s][deck[i]]++
		}
	}
	// 均匀指定四张 3 之一
	pick := rng.Below(4)
	acc := 0
	for s := 0; s < 3; s++ {
		acc += int(g.Hand[s][0])
		if pick < acc {
			g.Turn = s
			break
		}
	}
	g.LastPlayer = g.Turn
	return g
}

// ToBeat 当前必须压过的牌;空表示自由出牌。
func (g *GameState) ToBeat() CardSet {
	if len(g.Seq) == 0 {
		return CardSet{}
	}
	if !g.Seq[len(g.Seq)-1].Cards.Empty() {
		return g.Seq[len(g.Seq)-1].Cards
	}
	// 最后一个动作是过牌: 要么连续两家过(自由出牌), 要么必须压过再前一手
	if len(g.Seq) >= 2 && g.Seq[len(g.Seq)-2].Cards.Empty() {
		return CardSet{}
	}
	if len(g.Seq) >= 2 {
		return g.Seq[len(g.Seq)-2].Cards
	}
	return CardSet{}
}

// Legal 行动座位的合法动作(含首手带 3、能管必管、报单顶牌全部引擎约束)。
func (g *GameState) Legal() []CardSet {
	if g.Over {
		return nil
	}
	tb := g.ToBeat()
	firstMove := len(g.Seq) == 0
	if tb.Empty() {
		// 自由出牌: 任意牌型, 绝不过
		ms := genAllMoves(g.Hand[g.Turn])
		if firstMove {
			// 首手必须含 3
			w := 0
			for _, m := range ms {
				if m[0] >= 1 {
					ms[w] = m
					w++
				}
			}
			return ms[:w]
		}
		if g.Reported(g.NextSeat()) {
			// 下家报单: 出单必须顶手里最大的单
			largest := -1
			for r := 0; r < Ranks; r++ {
				if g.Hand[g.Turn][r] >= 1 {
					largest = r
				}
			}
			w := 0
			for _, m := range ms {
				info := detectMove(m)
				if info.Type != MTSingle || info.Rank == largest {
					ms[w] = m
					w++
				}
			}
			return ms[:w]
		}
		return ms
	}
	return legalMoves(g.Hand[g.Turn], tb) // 能管必管语义在 legalMoves 内部
}

// Step 执行一个动作(必须合法);处理过牌、炸弹计数与胜负判定。
func (g *GameState) Step(m CardSet) {
	if g.Over {
		return
	}
	if !m.Empty() {
		g.LastPlayer = g.Turn
		if detectMove(m).Type == MTBomb {
			g.BombCount++
		}
		g.Hand[g.Turn].Sub(m)
		g.Played[g.Turn].AddSet(m)
		if g.Hand[g.Turn].Total() == 0 {
			g.Over = true
			g.Winner = g.Turn
		}
	}
	g.LastMove[g.Turn] = m
	g.Seq = append(g.Seq, MoveRecord{PlayerId: g.Turn, Cards: m})
	if !g.Over {
		g.Turn = (g.Turn + 1) % 3
	}
}

// Reported 该座位是否只剩一张牌("报单")。
func (g *GameState) Reported(seat int) bool { return g.Hand[seat].Total() == 1 }

// Payoff 归一化到 16 张的零和收益: 赢家收两家剩牌(一家一张没出则翻倍)。
func (g *GameState) Payoff(seat int) float64 {
	if !g.Over {
		return 0.0
	}
	payment := func(s int) float64 {
		if s == g.Winner {
			return 0.0
		}
		left := g.Hand[s].Total()
		mult := 1
		if left == HandSize {
			mult = 2 // 一张没出: 双倍
		}
		return float64(left * mult)
	}
	if seat == g.Winner {
		sum := 0.0
		for s := 0; s < 3; s++ {
			sum += payment(s)
		}
		return sum / HandSize
	}
	return -payment(seat) / HandSize
}

// GetPublicView 构造 seat 的观察边界;策略网络只看这个。
func (g *GameState) GetPublicView(seat int) *PublicView {
	view := &PublicView{
		ViewingPlayerId: seat,
		MyHand:          g.Hand[seat],
		LastPlayerId:    g.LastPlayer,
		ActivePlayerId:  g.Turn,
		BombCount:       g.BombCount,
		ToBeat:          g.ToBeat(),
	}
	for s := 0; s < 3; s++ {
		view.CardCounts[s] = g.Hand[s].Total()
	}
	view.History = append([]MoveRecord(nil), g.Seq...)
	view.LastMove = g.LastMove
	return view
}

// PrevSeat 行动座位的上家。
func (g *GameState) PrevSeat() int { return (g.Turn + 2) % 3 }

// NextSeat 行动座位的下家。
func (g *GameState) NextSeat() int { return (g.Turn + 1) % 3 }
