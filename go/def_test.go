package paodekuai

import "testing"

// 牌面字符、解析、指纹与随机数的定点检查(参考值直接取自 C++ 实现的输出)。

func TestRankCharAndCharRank(t *testing.T) {
	const alphabet = "3456789TJQKA2"
	for i := 0; i < Ranks; i++ {
		if got := RankChar(i); got != alphabet[i] {
			t.Errorf("点数 %d 的字符应为 %c, 实际 %c", i, alphabet[i], got)
		}
		if got := CharRank(alphabet[i]); got != i {
			t.Errorf("字符 %c 的点数索引应为 %d, 实际 %d", alphabet[i], i, got)
		}
	}
	for _, bad := range []byte{'B', 'R', 'X', '1', '0'} {
		if got := CharRank(bad); got != -1 {
			t.Errorf("字符 %c 不应被识别, 实际 %d", bad, got)
		}
	}
}

func TestParseAndStr(t *testing.T) {
	cases := []struct{ cards, want string }{
		{"334455A2", "334455A2"},
		{"3456789TJQKA2", "3456789TJQKA2"},
		{"-", ""},
		{"", ""},
		{"TT33", "33TT"}, // 升序输出
	}
	for _, c := range cases {
		cs, err := ParseCards(c.cards)
		if err != nil {
			t.Fatalf("%s: %v", c.cards, err)
		}
		if got := cs.Str(); got != c.want {
			t.Errorf("ParseCards(%q).Str() = %q, 期望 %q", c.cards, got, c.want)
		}
	}
	if _, err := ParseCards("3B"); err == nil {
		t.Errorf("无法识别的字符应报错(跑得快没有王)")
	}
	// 加减与包含
	a, _ := ParseCards("3344")
	b, _ := ParseCards("44")
	if !a.Contains(b) || b.Contains(a) {
		t.Errorf("Contains 语义错误")
	}
	a.Sub(b)
	if a.Str() != "33" {
		t.Errorf("Sub 后应为 33, 实际 %s", a.Str())
	}
	a.Add(0, 1)
	if a.Str() != "333" {
		t.Errorf("Add 后应为 333, 实际 %s", a.Str())
	}
}

// 39 位指纹要能唯一表示一个多重集。
func TestKeyIsFingerprint(t *testing.T) {
	a, _ := ParseCards("34567")
	b, _ := ParseCards("34567")
	c, _ := ParseCards("34568")
	if a.Key() != b.Key() {
		t.Errorf("同一手牌的指纹应相同")
	}
	if a.Key() == c.Key() {
		t.Errorf("不同手牌的指纹不应相同")
	}
	if a.Key() >= 1<<39 {
		t.Errorf("指纹超出 39 位: %d", a.Key())
	}
	// 每点数占 3 位: 4444 全在第 1 点数
	d, _ := ParseCards("4444")
	if want := uint64(4) << 3; d.Key() != want {
		t.Errorf("4444 的指纹应为 %d, 实际 %d", want, d.Key())
	}
}

// 与 C++ 实现逐步对照的 splitmix64 参考值。
func TestRngMatchesCPP(t *testing.T) {
	r := NewRng(0)
	want0 := []uint64{0x6e789e6aa1b965f4, 0x06c45d188009454f,
		0xf88bb8a8724c81ec, 0x1b39896a51a8749b}
	for i, w := range want0 {
		if got := r.NextU64(); got != w {
			t.Errorf("seed0 第 %d 个 nextU64 应为 %x, 实际 %x", i+1, w, got)
		}
	}
	r7 := NewRng(777)
	want7 := []uint64{0x79d720b462a1724e, 0xa710687caae04440, 0x4fd675fd151af7f4}
	for i, w := range want7 {
		if got := r7.NextU64(); got != w {
			t.Errorf("seed777 第 %d 个 nextU64 应为 %x, 实际 %x", i+1, w, got)
		}
	}
	// seed 为 0 与未播种一致
	if NewRng(0).NextU64() != NewRng(0).NextU64() {
		t.Errorf("同种子应当可复现")
	}
	rb := NewRng(777)
	wantBelow := []int{2, 0, 0, 3, 0, 1, 1, 1}
	for i, w := range wantBelow {
		if got := rb.Below(4); got != w {
			t.Errorf("seed777 below(4) 第 %d 个应为 %d, 实际 %d", i+1, w, got)
		}
	}
}

func TestShuffledDeckMatchesCPP(t *testing.T) {
	// C++ shuffledDeck(Rng(777)) 的前 20 张
	want := [20]int{5, 4, 2, 4, 1, 11, 2, 6, 2, 1, 9, 0, 8, 7, 8, 6, 4, 10, 1, 9}
	deck := ShuffledDeck(NewRng(777))
	for i, w := range want {
		if deck[i] != w {
			t.Fatalf("deck[%d] 应为 %d, 实际 %d", i, w, deck[i])
		}
	}
	// 牌张构成: 3..K 各 4 张, A 3 张, 2 一张
	var counts [Ranks]int
	for _, r := range deck {
		counts[r]++
	}
	for r := 0; r <= 10; r++ {
		if counts[r] != 4 {
			t.Errorf("点数 %d 应有 4 张, 实际 %d", r, counts[r])
		}
	}
	if counts[11] != 3 {
		t.Errorf("A 应有 3 张, 实际 %d", counts[11])
	}
	if counts[12] != 1 {
		t.Errorf("2 应有 1 张, 实际 %d", counts[12])
	}
}

// 与 C++ GameState::deal 逐步对照的发牌参考值。
func TestDealMatchesCPP(t *testing.T) {
	cases := []struct {
		seed uint64
		lead int
		h    [3]string
	}{
		{0, 0, [3]string{"33488TTTJJJQQQKK", "3445556677999JAA", "3456677889TQKKA2"}},
		{1, 2, [3]string{"444556699TTJJQK2", "3345677789JQKKKA", "335678889TTJQQAA"}},
		{2, 0, [3]string{"3334568899TTTQQK", "35567779TJJJQKA2", "4445667889JQKKAA"}},
		{3, 2, [3]string{"557899TTTJJQQKKA", "344455667779JQK2", "3334668889TJQKAA"}},
		{4, 1, [3]string{"33445678889TQKKA", "346678TJJQQKKAA2", "34555677999TTJJQ"}},
	}
	for _, c := range cases {
		g := Deal(NewRng(c.seed))
		if g.Turn != c.lead {
			t.Errorf("seed %d 先手应为 %d, 实际 %d", c.seed, c.lead, g.Turn)
		}
		if g.LastPlayer != c.lead {
			t.Errorf("seed %d lastPlayer 应为先手 %d, 实际 %d", c.seed, c.lead, g.LastPlayer)
		}
		for s := 0; s < 3; s++ {
			if got := g.Hand[s].Str(); got != c.h[s] {
				t.Errorf("seed %d 手牌 %d 应为 %s, 实际 %s", c.seed, s, c.h[s], got)
			}
			if g.Hand[s].Total() != HandSize {
				t.Errorf("seed %d 手牌 %d 应为 16 张", c.seed, s)
			}
		}
	}
}
