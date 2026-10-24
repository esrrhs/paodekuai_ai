package paodekuai

import "testing"

// 牌型判定的定点检查;参考值直接取自 C++ detectMove 的输出。

func TestDetectMove(t *testing.T) {
	cases := []struct {
		cards  string
		mt     int
		rank   int
		length int
	}{
		{"-", MTPass, -1, 1},
		{"3", MTSingle, 0, 1},
		{"2", MTSingle, 12, 1},
		{"33", MTPair, 0, 1},
		{"22", MTPair, 12, 1},
		{"333", MTTriple, 0, 1},
		{"222", MTTriple, 12, 1},
		{"3333", MTBomb, 0, 1},
		{"AAAA", MTBomb, 11, 1},
		{"33344", MTTripleTwo, 0, 1},  // 三带一对
		{"33345", MTTripleTwo, 0, 1},  // 三带两单
		{"22233", MTTripleTwo, 12, 1}, // 2 的三张带一对
		{"34567", MTStraight, 0, 5},
		{"TJQKA", MTStraight, 7, 5},
		{"3344", MTPairStraight, 0, 2}, // 两连对恰好四张
		{"334455", MTPairStraight, 0, 3},
		{"333444", MTTripleStraight, 0, 2},
		{"333444555", MTTripleStraight, 0, 3},
		{"33344456", MTPlaneSolo, 0, 2},
		{"3334445566", MTPlanePair, 0, 2},
		{"33334444", MTPlaneSolo, 0, 2}, // 链上各留 1 张做单翅
		{"3456", MTWrong, -1, 1},        // 四张连不成顺(顺子至少 5 张)
		{"3345", MTWrong, -1, 1},        // 连对至少 2 对且相邻
		{"3334", MTWrong, -1, 1},        // 三带一不存在
		{"3334445", MTWrong, -1, 1},     // 飞机翅数不够
		{"JKQA2", MTWrong, -1, 1},       // A 到 2 被切断, 2 不入链
		{"A2", MTWrong, -1, 1},
		{"3334445678", MTWrong, -1, 1}, // 链 333444 只有 2 个翅却带了 4 张
	}
	for _, c := range cases {
		cs, err := ParseCards(c.cards)
		if err != nil {
			t.Fatalf("%s: %v", c.cards, err)
		}
		info := DetectMove(cs)
		if info.Type != c.mt || info.Rank != c.rank || info.Len != c.length {
			t.Errorf("detect(%s): 期望 (%d,%d,%d), 实际 (%d,%d,%d)", c.cards,
				c.mt, c.rank, c.length, info.Type, info.Rank, info.Len)
		}
	}
}

// 三带二恰好 5 张;3333 只能是炸弹。
func TestTripleTwoIsExactlyFive(t *testing.T) {
	h, _ := ParseCards("3333455")
	found := map[uint64]bool{}
	for _, m := range GenAllMoves(h) {
		found[m.Key()] = true
	}
	for _, want := range []string{"33345", "33355"} {
		cs, _ := ParseCards(want)
		if !found[cs.Key()] {
			t.Errorf("候选里应有 %s", want)
		}
	}
	bomb, _ := ParseCards("3333")
	if DetectMove(bomb).Type != MTBomb {
		t.Errorf("3333 应判为炸弹")
	}
}

func TestGenAllMovesCoversShapes(t *testing.T) {
	h, err := ParseCards("333345566778T")
	if err != nil {
		t.Fatal(err)
	}
	moves := GenAllMoves(h)
	// 全枚举不含过、无重复、均可归类且不超出手牌
	seen := map[uint64]bool{}
	for _, m := range moves {
		if m.Empty() {
			t.Errorf("自由出牌不应包含过牌")
		}
		k := m.Key()
		if seen[k] {
			t.Errorf("候选动作有重复: %s", m.Str())
		}
		seen[k] = true
		if DetectMove(m).Type == MTWrong {
			t.Errorf("候选 %s 无法归类", m.Str())
		}
		if !h.Contains(m) {
			t.Errorf("候选 %s 超出了手牌", m.Str())
		}
	}
	// 关键形状都在(与 Java MoveGeneratorTest 相同的手牌)
	for _, want := range []string{"3", "33", "333", "3333", "33345", "33355",
		"34567", "345678", "556677"} {
		cs, err := ParseCards(want)
		if err != nil {
			t.Fatal(err)
		}
		if !seen[cs.Key()] {
			t.Errorf("候选里应有 %s", want)
		}
	}
	// 连对至少 2 对: 3344555 里 3344 在, 34 不在
	h2, _ := ParseCards("3344555")
	found2 := map[uint64]bool{}
	for _, m := range GenAllMoves(h2) {
		found2[m.Key()] = true
	}
	pc, _ := ParseCards("3344")
	if !found2[pc.Key()] {
		t.Errorf("候选里应有 3344(两连对)")
	}
}

// 飞机带翅要枚举每一种具体带牌组合。
func TestPlaneWingEnumeration(t *testing.T) {
	h, _ := ParseCards("333444567")
	found := map[uint64]bool{}
	for _, m := range GenAllMoves(h) {
		found[m.Key()] = true
	}
	for _, want := range []string{"33344456", "33344457", "33344467"} {
		cs, err := ParseCards(want)
		if err != nil {
			t.Fatal(err)
		}
		if !found[cs.Key()] {
			t.Errorf("候选里应有 %s", want)
		}
	}
	for _, absent := range []string{"33344455", "3334445678"} {
		cs, _ := ParseCards(absent)
		if found[cs.Key()] {
			t.Errorf("手上没有这些牌, 候选里不应有 %s", absent)
		}
	}
}

// 能管必管: 压不过才能过,能压绝不过。
func TestLegalMovesMustBeat(t *testing.T) {
	h, _ := ParseCards("334456789")

	// 对 3 可被对 4 压(手里只有一对 4 能成对)
	beats3 := LegalMoves(h, mustParse(t, "33"))
	if !containsMove(beats3, "44") {
		t.Errorf("应对 33 给出 44, 实际 %v", movesStr(beats3))
	}
	for _, m := range beats3 {
		if m.Empty() {
			t.Errorf("有牌可压时不应出现过牌")
		}
	}

	// 压不过对 K: 只有过
	vsK := LegalMoves(h, mustParse(t, "KK"))
	if len(vsK) != 1 || !vsK[0].Empty() {
		t.Errorf("压不过对 K 只能过, 实际 %v", movesStr(vsK))
	}

	// 有牌可压时绝不过(能管必管)
	vs5 := LegalMoves(h, mustParse(t, "5"))
	if len(vs5) == 0 {
		t.Fatalf("对单 5 不应没有动作")
	}
	for _, m := range vs5 {
		if m.Empty() {
			t.Errorf("能管必管: 不应出现过牌")
		}
	}

	// 面对炸弹只有更大的炸弹可压
	h2, _ := ParseCards("3333445567")
	vsPlane := LegalMoves(h2, mustParse(t, "33344456"))
	if !containsMove(vsPlane, "3333") {
		t.Errorf("面对飞机应能出炸弹 3333, 实际 %v", movesStr(vsPlane))
	}
	vsBombA := LegalMoves(h2, mustParse(t, "AAAA"))
	for _, m := range vsBombA {
		if DetectMove(m).Type == MTBomb {
			t.Errorf("面对炸弹 A 只有更大的炸弹, 实际 %v", movesStr(vsBombA))
		}
	}

	// 炸弹之间的压制
	if !Beats(mustParse(t, "2222"), mustParse(t, "3333")) {
		t.Errorf("2222 应压 3333")
	}
	if Beats(mustParse(t, "3333"), mustParse(t, "2222")) {
		t.Errorf("3333 不应压 2222")
	}
	if Beats(mustParse(t, "3344"), mustParse(t, "3344")) {
		t.Errorf("同点数连对不互压")
	}
}

// 多重集的顺序无关性: 候选集合按指纹比较。
func TestLegalMovesSetShapes(t *testing.T) {
	// 顺子应牌: 同长度更大的顺子
	h, _ := ParseCards("3456789")
	got := LegalMoves(h, mustParse(t, "34567"))
	want := map[uint64]bool{}
	for _, s := range []string{"45678", "56789"} {
		want[mustParse(t, s).Key()] = true
	}
	for _, m := range got {
		if m.Empty() {
			continue
		}
		info := DetectMove(m)
		if info.Type == MTBomb {
			continue
		}
		if !want[m.Key()] {
			t.Errorf("对 34567 只应给出更长/更大的顺子或炸弹, 多出了 %s", m.Str())
		}
	}
	for _, w := range []string{"45678", "56789"} {
		if !containsMove(got, w) {
			t.Errorf("应有顺子 %s", w)
		}
	}
}

func mustParse(t *testing.T, cards string) CardSet {
	t.Helper()
	cs, err := ParseCards(cards)
	if err != nil {
		t.Fatalf("%s: %v", cards, err)
	}
	return cs
}

func containsMove(moves []CardSet, cards string) bool {
	cs, err := ParseCards(cards)
	if err != nil {
		panic(err)
	}
	for _, m := range moves {
		if m == cs {
			return true
		}
	}
	return false
}

func movesStr(moves []CardSet) []string {
	out := make([]string, len(moves))
	for i, m := range moves {
		if m.Empty() {
			out[i] = "-"
		} else {
			out[i] = m.Str()
		}
	}
	return out
}
