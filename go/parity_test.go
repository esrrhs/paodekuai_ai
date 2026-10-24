package paodekuai

import (
	"fmt"
	"math"
	"os"
	"strings"
	"testing"
	"time"
)

// 用训练侧导出的 golden 记录逐位校验 Go 移植(跨语言功能对齐契约)。
//
// golden 由 tools/pddump.cpp 生成: 每局给出发牌与先手,每个决策给出 C++ 的
// xImp / seq / 全部合法候选及其 actionFeat / libtorch 的 logits / 实际所选动作。
// Go 侧从同一副牌起用自己的状态机回放,因此这里同时校验候选动作枚举、观察构造、
// 牌矩阵、手数 oracle 与手写前向。
//
// 判定标准(与 Java/C++ 侧的 golden 测试一致,不许放宽):
//  1. 候选动作集合与 golden 精确相等(顺序与牌面);
//  2. xImp / seq / 每个 actionFeat 逐位相等(Float32bits 比较);
//  3. logits 绝对偏差 < 1e-3 且 argmax 恒等于 golden 的 CHOSEN 槽位。

// testPolicy 由 TestMain 加载一次,所有测试共享(权重只读,可并发)。
var testPolicy *Policy

func TestMain(m *testing.M) {
	p, err := Load()
	if err != nil {
		fmt.Fprintln(os.Stderr, "加载权重失败:", err)
		os.Exit(1)
	}
	testPolicy = p
	os.Exit(m.Run())
}

// parityRun 汇总整轮回放的差异与统计。
type parityRun struct {
	problems          []string
	featureMismatches int
	setMismatches     int
	decisions         int
	games             int
	argmaxMismatch    int
	worst             float64
	worstDecision     int
	nanos             int64
}

// compareBlock 逐位比较一段特征;等价 C++ 测试里的逐元素比较。
func (r *parityRun) compareBlock(name string, decision int, mine, golden []float32) {
	if len(mine) != len(golden) {
		r.record(fmt.Sprintf("%s 第%d个决策: 长度 %d != %d", name, decision, len(mine), len(golden)))
		r.featureMismatches++
		return
	}
	for i := range golden {
		if mine[i] != golden[i] { // 逐位(IEEE 754 位型)比较
			r.featureMismatches++
			r.record(fmt.Sprintf("%s 第%d个决策 [%d]: go=%v cpp=%v",
				name, decision, i, math.Float32bits(mine[i]), math.Float32bits(golden[i])))
			return
		}
	}
}

func (r *parityRun) record(msg string) {
	if len(r.problems) < 20 { // 收集全部差异后一次性报告,免得第一处失败就丢掉上下文
		r.problems = append(r.problems, msg)
	}
}

func (r *parityRun) report() string {
	if len(r.problems) == 0 {
		return ""
	}
	return fmt.Sprintf("%s\n特征差异 %d 处, 候选集合差异 %d 处",
		strings.Join(r.problems, "\n"), r.featureMismatches, r.setMismatches)
}

func argmax(values []float32) int {
	best := 0
	for i := 1; i < len(values); i++ {
		if values[i] > values[best] {
			best = i
		}
	}
	return best
}

// TestGoldenParity 回放全部局面,校验候选集合、特征逐位一致与 logits 对齐。
func TestGoldenParity(t *testing.T) {
	reader := openGolden(t)
	defer reader.close()

	reader.expectToken("PAOGOLDEN")
	version := reader.nextInt()
	if version != 1 {
		t.Fatalf("golden 版本应为 1, 实际 %d", version)
	}
	if gameCount := reader.nextInt(); gameCount < 24 {
		t.Fatalf("golden 应有 >= 24 局, 实际 %d", gameCount)
	}
	reader.nextToken() // seed0

	run := &parityRun{worstDecision: -1}
	for reader.hasNext() {
		key := reader.nextToken()
		switch key {
		case "GAME":
			game := reader.readGoldenGame()
			run.games++
			run.replayGame(t, reader, game)
		default:
			t.Fatalf("意外的字段 %s", key)
		}
	}

	if got := run.report(); got != "" {
		t.Errorf("与 golden 不一致:\n%s", got)
	}
	if run.decisions <= 400 {
		t.Errorf("回放的决策数 %d, 应多于 400", run.decisions)
	}
	if run.worst >= 1e-3 {
		t.Errorf("最大 logit 偏差 %e, 应小于 1e-3(第%d个决策)", run.worst, run.worstDecision)
	}
	if run.argmaxMismatch != 0 {
		t.Errorf("argmax 与 golden CHOSEN 不一致 %d 个", run.argmaxMismatch)
	}

	summary := fmt.Sprintf("replayed %d decisions, max logit deviation %.3e, argmax mismatches %d",
		run.decisions, run.worst, run.argmaxMismatch)
	fmt.Println(summary)
	t.Log(summary)
}

// replayGame 回放一局;遇到结构性偏差直接失败,其余差异累积到 run 里统一报告。
func (r *parityRun) replayGame(t *testing.T, g *goldenReader, game *goldenGame) {
	t.Helper()
	var hands [3]CardSet
	for s := 0; s < 3; s++ {
		cs, err := ParseCards(game.deals[s])
		if err != nil {
			t.Fatalf("第 %d 局发牌异常: %v", game.index, err)
		}
		hands[s] = cs
	}
	state := NewGameState(hands, game.lead)

	for {
		marker := g.nextToken()
		if marker == "ENDGAME" {
			return
		}
		if marker != "DEC" {
			t.Fatalf("第%d个决策: 期望 DEC, 实际 %s(第 %d 局)", r.decisions, marker, game.index)
		}
		g.nextToken() // 决策序号
		dec := g.readGoldenDecision()
		if dec.turn != state.Turn {
			t.Fatalf("第%d个决策: TURN %d 与本地状态 %d 不符(第 %d 局)",
				r.decisions, dec.turn, state.Turn, game.index)
		}
		if dec.hist != len(state.Seq) {
			t.Fatalf("第%d个决策: HIST %d 与本地历史 %d 不符(第 %d 局)",
				r.decisions, dec.hist, len(state.Seq), game.index)
		}
		if dec.actSeat != state.Turn {
			t.Fatalf("第%d个决策: ACT 座位 %d 与 TURN 不符", r.decisions, dec.actSeat)
		}
		chosenCards := dec.chosen
		if chosenCards == "-" {
			chosenCards = ""
		}
		actCards := dec.actCards
		if actCards == "-" {
			actCards = ""
		}
		if chosenCards != actCards {
			t.Fatalf("第%d个决策: CHOSEN 与 ACT 牌面不一致", r.decisions)
		}

		view := state.GetPublicView(state.Turn)
		xImp, seq := Encode(view)
		r.compareBlock("xImp", r.decisions, xImp, dec.xImp)
		r.compareBlock("seq", r.decisions, seq, dec.seq)

		options := LegalOptions(view)
		if len(options) != len(dec.optLines) {
			r.setMismatches++
			r.record(fmt.Sprintf("第%d个决策候选个数不同 cpp=%d go=%d",
				r.decisions, len(dec.optLines), len(options)))
		} else {
			for i, o := range options {
				want := dec.optLines[i]
				if want == "-" {
					want = ""
				}
				if mine := o.Concrete.Str(); mine != want {
					r.setMismatches++
					r.record(fmt.Sprintf("第%d个决策候选 %d: go=%q cpp=%q",
						r.decisions, i, mine, want))
				}
				r.compareBlock(fmt.Sprintf("actionFeat[%s]", dec.optLines[i]), r.decisions,
					o.Feature[:], dec.feats[i])
			}
		}

		// 用校验过的自有特征喂给手写前向
		actionFeat := make([]float32, len(options)*ActionSize)
		for i := range options {
			copy(actionFeat[i*ActionSize:(i+1)*ActionSize], options[i].Feature[:])
		}
		start := time.Now()
		scores, err := testPolicy.Score(xImp, seq, actionFeat, len(options))
		r.nanos += int64(time.Since(start))
		if err != nil {
			t.Fatalf("第%d个决策: 推理失败: %v", r.decisions, err)
		}
		for i := range dec.logits {
			diff := math.Abs(float64(scores[i]) - float64(dec.logits[i]))
			if diff > r.worst {
				r.worst = diff
				r.worstDecision = r.decisions
			}
		}
		if argmax(scores) != dec.chosenSlot {
			r.argmaxMismatch++
			r.record(fmt.Sprintf("第%d个决策: Go argmax=%d golden CHOSEN=%d(%s)",
				r.decisions, argmax(scores), dec.chosenSlot, dec.chosen))
		}
		if dec.chosenSlot < 0 || dec.chosenSlot >= len(options) {
			t.Fatalf("第%d个决策: 槽位 %d 超出候选范围 %d",
				r.decisions, dec.chosenSlot, len(options))
		}

		// 用自己候选集里 golden 槽位的那手推进,同时校验它与 CHOSEN 牌面一致
		if mine := options[dec.chosenSlot].Concrete.Str(); mine != chosenCards {
			t.Fatalf("第%d个决策: 槽位 %d 的候选 %q 与 CHOSEN %q 不一致",
				r.decisions, dec.chosenSlot, mine, chosenCards)
		}
		state.Step(options[dec.chosenSlot].Concrete)
		r.decisions++
	}
}

// TestForwardIsDeterministic 同一决策重复推理结果必须完全一致。
func TestForwardIsDeterministic(t *testing.T) {
	xImp := make([]float32, NodeSize)
	seq := make([]float32, SeqSize)
	for i := range xImp {
		xImp[i] = float32(i%3) * 0.25
	}
	for i := range seq {
		seq[i] = float32(i%5) * 0.1
	}
	feats := make([]float32, 2*ActionSize)
	for i := range feats {
		feats[i] = float32(i%7) * 0.5
	}
	a, err := testPolicy.Score(xImp, seq, feats, 2)
	if err != nil {
		t.Fatalf("推理失败: %v", err)
	}
	b, err := testPolicy.Score(xImp, seq, feats, 2)
	if err != nil {
		t.Fatalf("推理失败: %v", err)
	}
	if a[0] != b[0] || a[1] != b[1] {
		t.Errorf("同样输入两次结果不同: %v vs %v", a, b)
	}
}
