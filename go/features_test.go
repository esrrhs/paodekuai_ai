package paodekuai

import "testing"

// 维度常量与牌矩阵的定点检查,用例对应 Java PerfectPaoFeaturesTest。

func TestDimensionConstants(t *testing.T) {
	cases := []struct {
		name string
		got  int
		want int
	}{
		{"CardRows", CardRows, 11},
		{"CardMat", CardMat, 143},
		{"HistoryLen", HistoryLen, 15},
		{"LstmSteps", LstmSteps, 5},
		{"LstmIn", LstmIn, 429},
		{"StaticMatrices", StaticMatrices, 8},
		{"ImpMatrices", ImpMatrices, 23},
		{"HandHot", HandHot, 16},
		{"BombHot", BombHot, 9},
		{"ImpScalars", ImpScalars, 44},
		{"NodeBin", NodeBin, 1144},
		{"NodeSize", NodeSize, 1188},
		{"SeqSize", SeqSize, 2145},
		{"ActionSize", ActionSize, 149},
		{"NumActions", NumActions, 2048},
		{"LstmHidden", LstmHidden, 128},
		{"LstmGates", LstmGates, 512},
		{"NodeDim", NodeDim, 1316},
		{"JointSize", JointSize, 1465},
	}
	for _, c := range cases {
		if c.got != c.want {
			t.Errorf("%s 应为 %d, 实际 %d", c.name, c.want, c.got)
		}
	}
}

// counts(10, 4, 12, 1, ...) 这种成对写法,等价 Java 测试里的 counts(int...)。
func counts(pairs ...int) CardSet {
	var c CardSet
	for i := 0; i < len(pairs); i += 2 {
		c[pairs[i]] = int8(pairs[i+1])
	}
	return c
}

func TestCardMatrixMarksExpectedRows(t *testing.T) {
	out := make([]float32, CardMat)

	// KKKK: 张数 4 -> 行 0..3 点亮, 行 7(炸弹) 点亮; K 的索引是 10
	cardMatrix(counts(10, 4), out, 0)
	for row := 0; row < 4; row++ {
		if out[row*Ranks+10] != 1 {
			t.Errorf("第%d行应点亮", row)
		}
	}
	for _, row := range []int{rowSolo, rowPair, rowTrio, rowBomb} {
		if out[row*Ranks+10] != 1 {
			t.Errorf("行 %d 应点亮", row)
		}
	}

	// 单张 2: 行 4 点亮;2 不入链, 链行不亮
	cardMatrix(counts(12, 1), out, 0)
	if out[rowSolo*Ranks+12] != 1 {
		t.Errorf("单张 2 的第 4 行应点亮")
	}
	for _, row := range []int{rowSoloChain, rowPairChain, rowTrioChain} {
		if out[row*Ranks+12] != 0 {
			t.Errorf("2 不应出现在链行 %d", row)
		}
	}

	// 34567 顺子: 行 8(单张链) 对这五个点数都点亮
	cardMatrix(counts(0, 1, 1, 1, 2, 1, 3, 1, 4, 1), out, 0)
	for r := 0; r <= 4; r++ {
		if out[rowSoloChain*Ranks+r] != 1 {
			t.Errorf("链上的点数 %d 应点亮", r)
		}
	}
	if out[rowPairChain*Ranks+2] != 0 {
		t.Errorf("连对不成立")
	}

	// 334455 连对: 行 9 点亮
	cardMatrix(counts(0, 2, 1, 2, 2, 2), out, 0)
	for r := 0; r <= 2; r++ {
		if out[rowPairChain*Ranks+r] != 1 {
			t.Errorf("连对链上的点数 %d 应点亮", r)
		}
	}

	// 333444 三顺: 行 10 点亮
	cardMatrix(counts(0, 3, 1, 3), out, 0)
	for r := 0; r <= 2; r++ {
		if out[rowTrioChain*Ranks+r] != 1 {
			t.Errorf("三顺链上的点数 %d 应点亮", r)
		}
	}
	// inChain 与训练侧一致: 只看邻接点数, 本点数无牌也会被链区间盖住(索引 2 已验证)
	if out[rowTrioChain*Ranks+3] != 0 {
		t.Errorf("链外不该有链")
	}

	// 空牌矩阵全零
	cardMatrix(CardSet{}, out, 0)
	for i, v := range out {
		if v != 0 {
			t.Fatalf("空牌的矩阵应全零, [%d]=%v", i, v)
		}
	}
}

// encode 的结构与标量位: 剩牌数 one-hot、炸弹数、最近出牌者/报单标记。
func TestEncodeScalars(t *testing.T) {
	g := Deal(NewRng(42))
	seat := g.Turn
	view := g.GetPublicView(seat)
	xImp, seq := Encode(view)
	if len(xImp) != NodeSize || len(seq) != SeqSize {
		t.Fatalf("encode 维度应为 %d/%d, 实际 %d/%d", NodeSize, SeqSize, len(xImp), len(seq))
	}

	// 我手牌矩阵 == xImp 开头的 143 维
	ref := make([]float32, CardMat)
	cardMatrix(view.MyHand, ref, 0)
	for i := 0; i < CardMat; i++ {
		if xImp[i] != ref[i] {
			t.Fatalf("xImp 开头应是我手牌矩阵, [%d] 不符", i)
		}
	}
	// 首手时 seq 全零(历史为空)
	for i, v := range seq {
		if v != 0 {
			t.Fatalf("开局历史为空, seq 应全零, [%d]=%v", i, v)
		}
	}

	// 开局标量: 各家 16 张 -> oneHot(15) 点亮 15 号位; 炸弹 0 -> 0 号位;
	// lastPlayer == seat(先手) -> 1; 无人报单
	s := NodeBin
	checkScalar := func(tag string, got float32, want float32) {
		t.Helper()
		if got != want {
			t.Errorf("%s: 期望 %v, 实际 %v", tag, want, got)
		}
	}
	checkScalar("开局 prev 剩牌 oneHot[15]", xImp[s+15], 1)
	checkScalar("开局 nxt 剩牌 oneHot[15]", xImp[s+HandHot+15], 1)
	checkScalar("开局炸弹 oneHot[0]", xImp[s+2*HandHot], 1)
	checkScalar("开局 lastPlayer==seat", xImp[s+2*HandHot+BombHot+0], 1)
	checkScalar("开局 nxt 报单", xImp[s+2*HandHot+BombHot+1], 0)
	checkScalar("开局 prev 报单", xImp[s+2*HandHot+BombHot+2], 0)
	for i := 0; i < HandHot-1; i++ {
		checkScalar("开局 prev 剩牌 oneHot 清零", xImp[s+i], 0)
	}

	// 打一轮(出 + 两过)后回到先手: 标量随状态变化
	legal := g.Legal()
	if len(legal) == 0 {
		t.Fatal("首手应有合法动作")
	}
	played := legal[0]
	g.Step(played)
	g.Step(CardSet{})
	g.Step(CardSet{})
	v2 := g.GetPublicView(g.Turn)
	x2, seq2 := Encode(v2)

	// 按座位相对关系断言
	prev, nxt := v2.PrevSeat(), v2.NextSeat()
	checkScalar("prev 剩牌 oneHot",
		x2[s+view2At(v2, prev)], 1)
	checkScalar("nxt 剩牌 oneHot",
		x2[s+HandHot+view2At(v2, nxt)], 1)
	checkScalar("炸弹数 oneHot",
		x2[s+2*HandHot+min(g.BombCount, BombHot-1)], 1)
	if x2[s+2*HandHot+BombHot+0] != boolToFloat(g.LastPlayer == seat) {
		t.Errorf("lastPlayer 标量不符: lastPlayer=%d seat=%d", g.LastPlayer, seat)
	}
	if x2[s+2*HandHot+BombHot+1] != boolToFloat(v2.CardCounts[nxt] == 1) {
		t.Errorf("下家报单标量不符")
	}
	if x2[s+2*HandHot+BombHot+2] != boolToFloat(v2.CardCounts[prev] == 1) {
		t.Errorf("上家报单标量不符")
	}
	// 历史矩阵: 最近 3 手是 出牌/过/过, 槽位 12..14;槽位 12 应是出的那手
	first := seq2[(HistoryLen-3)*CardMat : (HistoryLen-2)*CardMat]
	refPlayed := make([]float32, CardMat)
	cardMatrix(played, refPlayed, 0)
	for i := range first {
		if first[i] != refPlayed[i] {
			t.Fatalf("seq 尾部应是对应历史手牌的矩阵")
		}
	}
	for i := 0; i < (HistoryLen-3)*CardMat; i++ {
		if seq2[i] != 0 {
			t.Fatalf("seq 应前部补零, [%d]=%v", i, seq2[i])
		}
	}
}

// view2At oneHot(value) 的点亮下标 = value(剩牌数 - 1)。
func view2At(v *PublicView, seat int) int {
	return v.CardCounts[seat] - 1
}

func boolToFloat(b bool) float32 {
	if b {
		return 1
	}
	return 0
}

// isLargest: 单张 2、A 的对/三/炸弹、链尾到 A。
func TestIsLargest(t *testing.T) {
	cases := []struct {
		cards string
		want  bool
	}{
		{"2", true},
		{"A", false},
		{"AA", true},
		{"AAA", true},
		{"AAAA", true},
		{"AAA44", true}, // 三张是 A
		{"TJQKA", true},
		{"9TJQKA", true},
		{"34567", false},
		{"33344", false}, // 三带二看三张的点数
		{"AAA44", true},  // 三张是 A
		{"AAAA", true},   // A 的炸弹是最大炸弹
		{"2222", false},  // 2 的炸弹会被 AAAA 压
	}
	for _, c := range cases {
		cs, err := ParseCards(c.cards)
		if err != nil {
			t.Fatalf("%s: %v", c.cards, err)
		}
		if got := isLargest(DetectMove(cs)); got != c.want {
			t.Errorf("isLargest(%s) 应为 %v, 实际 %v", c.cards, c.want, got)
		}
	}
}
