package paodekuai

// PerfectPao 网络输入编码(忠实移植 cpp/src/perfect_pao_features.cpp,
// 对应 Java nn.PerfectPaoFeatures)。只用 PublicView 的公开信息。
//
// 11x13 牌矩阵(行主序 row*13+rank):
//   0-3 行 = 计数 one-hot; 4 单张; 5 对子; 6 三张; 7 炸弹(==4);
//   8/9/10 = 处于单张/对子/三张链内(链只覆盖 3..A)。
//
// 节点 xImp[1188] = 8 个静态矩阵(1144) + 44 个标量:
//   我手牌 / 两家合计未知牌 / 我·上家·下家已出 / 我·上家·下家最近一手;
//   标量 = oneHot(上家剩牌数-1,16) + oneHot(下家剩牌数-1,16)
//        + oneHot(min(炸弹数,8),9) + 最近出牌者是自己 + 下家报单 + 上家报单。
//
// 历史 seq[2145] = 最近 15 手(过牌为空矩阵)按时间序,前部补零;
// LSTM 视角 = 5 步 x 3 手 x 143。
//
// 动作特征 actionFeat[149] = 143 矩阵 + 6 标量:
//   isBomb / isLargest / 张数==下家剩牌 / 张数==上家剩牌
//   / minSteps(出完后)/16 / 有效标记 1.0。

const (
	// CardRows 牌矩阵行数
	CardRows = 11
	// CardMat 一手牌的 11x13 牌矩阵展平后的长度
	CardMat = CardRows * Ranks // 143
	// HistoryLen 历史序列保留的手数
	HistoryLen = 15
	// LstmSteps LSTM 时间步数
	LstmSteps = 5
	// LstmIn 单个时间步的输入维度
	LstmIn = 3 * CardMat // 429
	// StaticMatrices 节点向量里的牌矩阵个数
	StaticMatrices = 8
	// ImpMatrices 编码缓冲区里的矩阵总数(静态 + 历史)
	ImpMatrices = StaticMatrices + HistoryLen // 23
	// HandHot 手牌数 one-hot 档数
	HandHot = 16
	// BombHot 炸弹数 one-hot 档数
	BombHot = 9
	// ImpScalars 节点向量里的标量个数
	ImpScalars = 2*HandHot + BombHot + 3 // 44
	// NodeBin 节点向量里牌矩阵部分的维度
	NodeBin = StaticMatrices * CardMat // 1144
	// NodeSize 节点向量总维度 (1188)
	NodeSize = NodeBin + ImpScalars
	// SeqSize 历史序列总维度 (2145)
	SeqSize = HistoryLen * CardMat
	// ActionSize 单个候选动作特征维度 (149)
	ActionSize = CardMat + 6
	// NumActions 训练侧动作空间大小
	NumActions = 2048
)

// 矩阵内的行偏移,等价 C++ R_*。
const (
	rowSolo      = 4
	rowPair      = 5
	rowTrio      = 6
	rowBomb      = 7
	rowSoloChain = 8
	rowPairChain = 9
	rowTrioChain = 10
)

// inChain 点数 r 是否落在一条长度 >= minLen、每点 >= need 张的连续链内(3..A)。
func inChain(cnt CardSet, r, minLen, need int) bool {
	if r >= ChainRanks {
		return false
	}
	lo, hi := r, r
	for lo-1 >= 0 && int(cnt[lo-1]) >= need {
		lo--
	}
	for hi+1 < ChainRanks && int(cnt[hi+1]) >= need {
		hi++
	}
	return hi-lo+1 >= minLen
}

// cardMatrix 11x13 牌矩阵,写入 out 的 [off, off+143)。
func cardMatrix(cs CardSet, out []float32, off int) {
	for i := off; i < off+CardMat; i++ {
		out[i] = 0
	}
	for r := 0; r < Ranks; r++ {
		for k := 0; k < int(cs[r]); k++ {
			out[off+k*Ranks+r] = 1 // 第 k 行: 该点数至少出现 k+1 次
		}
		if cs[r] >= 1 {
			out[off+rowSolo*Ranks+r] = 1
		}
		if cs[r] >= 2 {
			out[off+rowPair*Ranks+r] = 1
		}
		if cs[r] >= 3 {
			out[off+rowTrio*Ranks+r] = 1
		}
		if cs[r] == 4 {
			out[off+rowBomb*Ranks+r] = 1
		}
	}
	for r := 0; r < ChainRanks; r++ {
		if inChain(cs, r, MinStraight, 1) {
			out[off+rowSoloChain*Ranks+r] = 1
		}
		if inChain(cs, r, MinPairChain, 2) {
			out[off+rowPairChain*Ranks+r] = 1
		}
		if inChain(cs, r, MinPlane, 3) {
			out[off+rowTrioChain*Ranks+r] = 1
		}
	}
}

// encode 写入节点向量(1188)与历史序列(2145);等价 C++ encode。
func encode(view *PublicView) (xImp []float32, seq []float32) {
	seat := view.ViewingPlayerId
	prev := view.PrevSeat()
	nxt := view.NextSeat()

	// 重放公开历史得到各家已出牌与炸弹数
	var played [3]CardSet
	bombCount := 0
	for _, rec := range view.History {
		if !rec.Cards.Empty() {
			played[rec.PlayerId].AddSet(rec.Cards)
			if detectMove(rec.Cards).Type == MTBomb {
				bombCount++
			}
		}
	}

	// 两家合计的未知牌池 = 整副牌(3..K 各 4 张, A 3 张, 2 一张) - 我手牌 - 全场已出
	others := CardSet{}
	for r := 0; r <= 10; r++ {
		others[r] = 4
	}
	others[11] = 3
	others[12] = 1
	others.Sub(view.MyHand)
	for s := 0; s < 3; s++ {
		others.Sub(played[s])
	}

	var matrices [ImpMatrices * CardMat]float32 // 未写满(历史为空)的槽位必须为 0
	cardMatrix(view.MyHand, matrices[:], 0*CardMat)
	cardMatrix(others, matrices[:], 1*CardMat)
	cardMatrix(played[seat], matrices[:], 2*CardMat)
	cardMatrix(played[prev], matrices[:], 3*CardMat)
	cardMatrix(played[nxt], matrices[:], 4*CardMat)
	cardMatrix(view.LastMove[seat], matrices[:], 5*CardMat)
	cardMatrix(view.LastMove[prev], matrices[:], 6*CardMat)
	cardMatrix(view.LastMove[nxt], matrices[:], 7*CardMat)
	// 最近 15 手按时间序尾部对齐,前部补零矩阵
	hlen := HistoryLen
	if len(view.History) < hlen {
		hlen = len(view.History)
	}
	for i := 0; i < hlen; i++ {
		m := view.History[len(view.History)-hlen+i].Cards
		cardMatrix(m, matrices[:], (StaticMatrices+(HistoryLen-hlen)+i)*CardMat)
	}

	var scalars [ImpScalars]float32
	oneHot(scalars[:], 0, HandHot, view.CardCounts[prev]-1)
	oneHot(scalars[:], HandHot, HandHot, view.CardCounts[nxt]-1)
	oneHot(scalars[:], 2*HandHot, BombHot, min(bombCount, BombHot-1))
	if view.LastPlayerId == seat {
		scalars[2*HandHot+BombHot+0] = 1
	}
	if view.CardCounts[nxt] == 1 {
		scalars[2*HandHot+BombHot+1] = 1
	}
	if view.CardCounts[prev] == 1 {
		scalars[2*HandHot+BombHot+2] = 1
	}

	xImp = make([]float32, NodeSize)
	copy(xImp, matrices[:NodeBin])
	copy(xImp[NodeBin:], scalars[:])
	seq = make([]float32, SeqSize)
	copy(seq, matrices[StaticMatrices*CardMat:StaticMatrices*CardMat+SeqSize])
	return xImp, seq
}

// oneHot 等价 C++ 的 oneHot lambda。
func oneHot(dst []float32, off, n, value int) {
	if value >= 0 && value < n {
		dst[off+value] = 1
	}
}

// isLargest 是否没有同牌型的剩余牌能压过这手(炸弹除外):
// 单张 2、对/三/带的 A、炸弹 A、链尾到 A。
func isLargest(a MoveInfo) bool {
	switch a.Type {
	case MTSingle:
		return a.Rank == 12 // 全场唯一的 2
	case MTBomb, MTPair, MTTriple, MTTripleTwo:
		return a.Rank == 11 // A(2 不成对)
	case MTStraight, MTPairStraight, MTTripleStraight, MTPlaneSolo, MTPlanePair:
		return a.Rank+a.Len-1 == 11 // 链尾到 A
	}
	return false
}

// LegalOption 一个候选动作;等价 C++ LegalOption / Java PerfectPaoFeatures.LegalOption。
type LegalOption struct {
	Slot     int                 // 本决策点内的槽位 0..n-1
	Concrete CardSet             // 具体出牌(空 = 过牌)
	Feature  [ActionSize]float32 // 149 维动作特征
}

// legalOptions 当前视角下的全部合法候选(含首手带 3、报单顶牌等引擎约束),
// 顺序与训练侧一致;等价 C++ legalOptions。
func legalOptions(view *PublicView) []LegalOption {
	var out []LegalOption
	nxt := view.NextSeat()

	var concrete []CardSet
	if view.ToBeat.Empty() {
		concrete = genAllMoves(view.MyHand)
		if len(view.History) == 0 {
			// 首手必须含 3
			w := 0
			for _, m := range concrete {
				if m[0] >= 1 {
					concrete[w] = m
					w++
				}
			}
			concrete = concrete[:w]
		} else if view.CardCounts[nxt] == 1 {
			// 下家报单: 出单必须顶最大的单
			largest := -1
			for r := 0; r < Ranks; r++ {
				if view.MyHand[r] >= 1 {
					largest = r
				}
			}
			w := 0
			for _, m := range concrete {
				info := detectMove(m)
				if info.Type != MTSingle || info.Rank == largest {
					concrete[w] = m
					w++
				}
			}
			concrete = concrete[:w]
		}
	} else {
		concrete = legalMoves(view.MyHand, view.ToBeat)
	}

	for _, chosen := range concrete {
		info := detectMove(chosen)
		if info.Type == MTWrong && !chosen.Empty() {
			continue
		}
		var o LegalOption
		o.Slot = len(out)
		o.Concrete = chosen
		size := chosen.Total()
		cardMatrix(chosen, o.Feature[:], 0)
		ex := o.Feature[CardMat:]
		if info.Type == MTBomb {
			ex[0] = 1
		}
		if isLargest(info) {
			ex[1] = 1
		}
		if size > 0 && size == view.CardCounts[nxt] {
			ex[2] = 1
		}
		if size > 0 && size == view.CardCounts[view.PrevSeat()] {
			ex[3] = 1
		}
		after := view.MyHand
		after.Sub(chosen)
		ex[4] = float32(minSteps(after)) / float32(HandSize)
		ex[5] = 1 // 有效动作标记, 用于区分全零矩阵的过牌
		out = append(out, o)
	}
	return out
}
