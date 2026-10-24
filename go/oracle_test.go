package paodekuai

import "testing"

// 手数下界 oracle 的定点检查(参考值直接取自 C++ 实现的输出)。
//
// 该值直接进入候选动作特征(actionFeat 的第 5 个标量),golden 对齐测试已经把它在
// 549 个决策 × 全部候选上逐位校验过;这里补上可人工推算的小牌型与暴力对照,
// 防止后续改动跑偏。

func TestMinSteps(t *testing.T) {
	cases := []struct {
		cards string
		want  int
	}{
		{"-", 0},
		{"3", 1},
		{"33", 1},
		{"333", 1},
		{"3333", 1},             // 炸弹
		{"33344", 1},            // 三带二
		{"34567", 1},            // 顺子
		{"3344", 1},             // 两连对(连对最少 2 对)
		{"334455", 1},           // 三连对
		{"333444", 1},           // 三顺/飞机不带
		{"3334445566", 1},       // 飞机带对
		{"456789", 1},           // 六张顺子
		{"55566677", 1},         // 555666 三顺 + 77 对翅(飞机带对)
		{"33445566", 1},         // 四连对
		{"2222", 1},             // 2 的炸弹
		{"3456", 4},             // 连不成顺, 只能四张单
		{"2223", 2},             // 三带二要两张散牌, 只剩一张 3
		{"33334", 2},            // 炸弹 + 单张
		{"3334445", 2},          // 飞机带不了单翅, 只能拆
		{"2345678", 2},          // 34567 顺子 + 2
		{"3334445556", 2},       // 333444555 + 6
		{"34567889", 2},         // 345678 顺子 + 8 9
		{"33334567", 2},         // 34567 顺子 + 333 三张
		{"TTTTJQKA", 2},         // TJQKA 顺子 + T 炸弹
		{"33488TTTJJJQQQKK", 2}, // 16 张乱牌: 两条三顺/顺子组合
		{"3445556677999JAA", 4}, // 16 张乱牌
		{"3334568899TTTQQK", 4}, // 16 张乱牌
	}
	for _, c := range cases {
		counts, err := ParseCards(c.cards)
		if err != nil {
			t.Fatalf("%s: %v", c.cards, err)
		}
		if got := MinSteps(counts); got != c.want {
			t.Errorf("%s: 期望 %d 步, 实际 %d", c.cards, c.want, got)
		}
	}
}

// bruteSteps 直接按"每一步出掉一个合法牌型"递归求最优,是与 DP/oracle 无关的暴力对照。
func bruteSteps(cnt CardSet, memo map[uint64]int) int {
	if cnt.Total() == 0 {
		return 0
	}
	key := cnt.Key()
	if v, ok := memo[key]; ok {
		return v
	}
	best := cnt.Total() // 上界: 全按单张出
	for _, m := range GenAllMoves(cnt) {
		after := cnt
		after.Sub(m)
		if v := 1 + bruteSteps(after, memo); v < best {
			best = v
		}
	}
	memo[key] = best
	return best
}

// 小规模手牌上与暴力枚举逐手对照: oracle 必须给出真正的最少步数。
func TestMinStepsMatchesBruteForce(t *testing.T) {
	cases := []string{
		"3334445", "33344456", "3456789", "33445566", "3334445556",
		"2345678", "3344556", "333444556", "345677",
		"3344567", "55566677", "34567889", "33334567", "TTTTJQKA",
	}
	memo := make(map[uint64]int, 4096)
	for _, s := range cases {
		counts, err := ParseCards(s)
		if err != nil {
			t.Fatalf("%s: %v", s, err)
		}
		want := bruteSteps(counts, memo)
		if got := MinSteps(counts); got != want {
			t.Errorf("%s: oracle=%d 暴力=%d", s, got, want)
		}
	}
}

// 真实 16 张手牌上的界限: 至少 1 步,最多等于张数;整副牌也应在合理范围内。
func TestMinStepsBounds(t *testing.T) {
	counts, err := ParseCards("3344556677889TJQ")
	if err != nil {
		t.Fatal(err)
	}
	base := MinSteps(counts)
	if base < 1 || base > counts.Total() {
		t.Fatalf("16 张手牌的手数下界应在 1..%d, 实际 %d", counts.Total(), base)
	}
	// 整副牌(48 张)也应在合理范围内,顺带走一遍深递归
	full := CardSet{}
	for r := 0; r <= 10; r++ {
		full[r] = 4
	}
	full[11] = 3
	full[12] = 1
	if got := MinSteps(full); got < 1 || got > DeckSize {
		t.Errorf("整副牌的手数应在 1..%d, 实际 %d", DeckSize, got)
	}
}
