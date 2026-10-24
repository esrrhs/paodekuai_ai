package paodekuai

// 对外 API: 名字与 Java 侧调用形状一一对应,便于逐行对照移植。
//
//	Java                                              Go
//	MoveGenerator.genAllMoves(hand)                   GenAllMoves(hand)
//	MoveGenerator.detectMove(m) -> MoveInfo           DetectMove(m) -> MoveInfo
//	MoveGenerator.legalMoves(hand, toBeat)            LegalMoves(hand, toBeat)
//	Oracle.minSteps(hand)                             MinSteps(hand)
//	PerfectPaoFeatures.cardMatrix(cs, out, off)       CardMatrix(cs, out, off)
//	PerfectPaoFeatures.encode(view) -> Encoded        Encode(view) -> (xImp, seq)
//	PerfectPaoFeatures.legalOptions(view)             LegalOptions(view)
//	ActorNetwork.load(dir) / score(...)               LoadPolicy(dir) / (*Policy).Score(...)
//	NeuralAiPlayer.decide(view)                       (*Policy).Decide(view)
//	GameState.deal(rng)/toBeat()/legal()/step(...)    Deal(rng)/(*GameState).ToBeat()/Legal()/Step(...)

// GenAllMoves 自由出牌时的全部组合(从不过);等价 Java MoveGenerator.genAllMoves。
func GenAllMoves(hand CardSet) []CardSet {
	return genAllMoves(hand)
}

// LegalMoves 应牌时的合法动作(能管必管);等价 Java MoveGenerator.legalMoves。
func LegalMoves(hand, toBeat CardSet) []CardSet {
	return legalMoves(hand, toBeat)
}

// DetectMove 牌型判定;无法归类时 Type 为 MTWrong。
// 等价 Java MoveGenerator.detectMove。
func DetectMove(m CardSet) MoveInfo { return detectMove(m) }

// IsBomb 该手是否炸弹;等价 Java MoveGenerator.isBomb。
func IsBomb(m CardSet) bool { return isBomb(m) }

// Beats 判断 move 能否压过 rival(双方都假定是合法牌型)。
func Beats(move, rival CardSet) bool { return beats(move, rival) }

// MinSteps 一手牌的最少出牌步数(与训练侧 oracle 逐位一致)。
// 等价 Java Oracle.minSteps。
func MinSteps(hand CardSet) int { return minSteps(hand) }

// CardMatrix 11x13 牌矩阵,写入 out 的 [off, off+143);
// 等价 Java PerfectPaoFeatures.cardMatrix。
func CardMatrix(cs CardSet, out []float32, off int) { cardMatrix(cs, out, off) }

// Encode 节点向量(1188)与历史序列(2145);等价 Java PerfectPaoFeatures.encode。
func Encode(view *PublicView) (xImp []float32, seq []float32) { return encode(view) }

// LegalOptions 当前视角下的全部合法候选(顺序与训练侧一致);
// 等价 Java PerfectPaoFeatures.legalOptions。
func LegalOptions(view *PublicView) []LegalOption { return legalOptions(view) }
