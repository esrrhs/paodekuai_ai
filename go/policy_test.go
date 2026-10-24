package paodekuai

import (
	"path/filepath"
	"strings"
	"testing"
)

// 推理入口的冒烟测试: 端到端跑一个真实决策,并检查动作确实来自完整枚举的候选集。

func TestPolicyDecideOnFirstTurn(t *testing.T) {
	reader := openGolden(t)
	defer reader.close()
	reader.expectToken("PAOGOLDEN")
	reader.nextInt()
	reader.nextInt()
	reader.nextToken()
	if tok := reader.nextToken(); tok != "GAME" {
		t.Fatalf("期望 GAME, 实际 %s", tok)
	}
	game := reader.readGoldenGame()
	var hands [3]CardSet
	for s := 0; s < 3; s++ {
		cs, err := ParseCards(game.deals[s])
		if err != nil {
			t.Fatal(err)
		}
		hands[s] = cs
	}
	st := NewGameState(hands, game.lead)

	view := st.GetPublicView(st.Turn)
	d, err := testPolicy.Decide(view)
	if err != nil {
		t.Fatalf("决策失败: %v", err)
	}
	xImp, seq := Encode(view)
	options := LegalOptions(view)
	if len(d.Logits) != len(options) {
		t.Errorf("得分个数 %d 应等于候选个数 %d", len(d.Logits), len(options))
	}
	found := false
	for _, m := range options {
		if m.Concrete == d.Move {
			found = true
			break
		}
	}
	if !found {
		t.Errorf("选出的 %s 不在候选集中", d.Move.Str())
	}
	if d.Slot < 0 || d.Slot >= len(options) {
		t.Errorf("槽位 %d 越界", d.Slot)
	}
	if _, err := testPolicy.Score(xImp, seq, nil, 0); err != nil {
		t.Errorf("0 个候选应返回空得分: %v", err)
	}
}

// TestPolicyDecideFollowsMustBeat 应牌时的决策必须来自能管必管的候选集:
// 座位 0 出 3, 座位 1 出 A, 座位 2 压不过只能过, 轮到座位 0 必须顶出唯一的 2。
func TestPolicyDecideFollowsMustBeat(t *testing.T) {
	var hands [3]CardSet
	hands[0] = mustParse(t, "3344556789TJQKA2")
	hands[1] = mustParse(t, "6789TJJJQQQKKKAA")
	hands[2] = mustParse(t, "33445566778899TT")
	st := NewGameState(hands, 0)
	st.Step(mustParse(t, "3"))
	st.Step(mustParse(t, "A"))
	st.Step(CardSet{}) // 座位 2 压不过 A, 合法过牌

	if st.Turn != 0 {
		t.Fatalf("出牌权应回到座位 0, 实际 %d", st.Turn)
	}
	if toBeat := st.ToBeat(); toBeat.Str() != "A" {
		t.Fatalf("应牌目标应为 A, 实际 %q", toBeat.Str())
	}
	view := st.GetPublicView(st.Turn)
	d, err := testPolicy.Decide(view)
	if err != nil {
		t.Fatalf("决策失败: %v", err)
	}
	if d.Move.Str() != "2" {
		t.Errorf("能管必管: 应顶出 2, 实际 %q", d.Move.Str())
	}
	if len(d.Options) != 1 {
		t.Errorf("对 A 只有 2 可压, 候选应恰 1 个, 实际 %d", len(d.Options))
	}
	// 候选不含过牌(有牌可压)
	for _, m := range d.Options {
		if m.Empty() {
			t.Errorf("能管必管: 候选不应含过牌")
		}
	}
}

func TestPolicyScoreValidatesInput(t *testing.T) {
	xImp := make([]float32, NodeSize)
	seq := make([]float32, SeqSize)
	feats := make([]float32, ActionSize)
	if _, err := testPolicy.Score(xImp[:10], seq, feats, 1); err == nil {
		t.Errorf("xImp 太短应报错")
	}
	if _, err := testPolicy.Score(xImp, seq[:10], feats, 1); err == nil {
		t.Errorf("seq 太短应报错")
	}
	if _, err := testPolicy.Score(xImp, seq, feats[:10], 1); err == nil {
		t.Errorf("actionFeat 不够长应报错")
	}
	if got, err := testPolicy.Score(xImp, seq, feats, 0); err != nil || len(got) != 0 {
		t.Errorf("0 个候选应返回空得分, 实际 %v %v", got, err)
	}
}

// 权重缺失时要给出可操作的报错,而不是静默跳过。
func TestLoadPolicyMissingDirectory(t *testing.T) {
	_, err := LoadPolicy(filepath.Join("this", "directory", "does", "not", "exist"))
	if err == nil || !strings.Contains(err.Error(), "manifest") {
		t.Errorf("应报缺少清单, 实际 %v", err)
	}
}
