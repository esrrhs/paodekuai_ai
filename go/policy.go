package paodekuai

import (
	"fmt"
	"math"
)

// 策略网络玩家: 完整枚举合法动作,逐手打分后直接取最高分出牌。
// 对应 Java 侧 ActorNetwork(推理)与 NeuralAiPlayer(决策)。
//
// 三个座位共用一个 actor(跑得快座位完全对称);观察只用公开信息。
//
// 浮点逐位一致三要素: 累加顺序固定(点积内累加后 +bias,LSTM 门预激活为
// (dot(wIh,x)+bIh)+dot(wHh,h)+bHh);全程 float32;head 不加 relu。

// DefaultModelRel 仓库内自带权重目录相对仓库根的位置。
const DefaultModelRel = "data/models/ts"

// Policy 已加载的策略网络。权重只读,可在多个 goroutine 间并发使用。
type Policy struct {
	actor *actorNet
}

// LoadPolicy 从目录加载 pao_ts.pt(连同 pao_manifest.json);
// 等价 Java ActorNetwork.load(Path) / C++ Policy::Load。
func LoadPolicy(dir string) (*Policy, error) {
	net, err := readWeights(dir)
	if err != nil {
		return nil, err
	}
	return &Policy{actor: net}, nil
}

// Load 从仓库默认目录 data/models/ts 加载权重;找不到权重时给出可操作的报错。
func Load() (*Policy, error) {
	dir := FindSharedData(DefaultModelRel)
	if dir == "" {
		return nil, fmt.Errorf("未找到 %s(该目录应与 go/ 平级放在仓库 data/ 下, "+
			"权重需先用 PerfectPaoCPP 训练产出再由 tools/export_actor.py 导出 TorchScript)",
			DefaultModelRel)
	}
	return LoadPolicy(dir)
}

// Score 给单个决策的全部候选动作打分;等价 Java ActorNetwork.score。
//
// xImp 为 1188 维节点特征,seq 为 2145 维历史序列([5][429] 展平),
// actionFeat 为 options*149 维候选动作特征(按槽位顺序紧密排列)。
func (p *Policy) Score(xImp, seq, actionFeat []float32, options int) ([]float32, error) {
	if p == nil || p.actor == nil {
		return nil, fmt.Errorf("策略尚未加载")
	}
	if len(xImp) < NodeSize {
		return nil, fmt.Errorf("xImp 长度 %d, 至少需要 %d", len(xImp), NodeSize)
	}
	if len(seq) < SeqSize {
		return nil, fmt.Errorf("seq 长度 %d, 至少需要 %d", len(seq), SeqSize)
	}
	if options < 0 || len(actionFeat) < options*ActionSize {
		return nil, fmt.Errorf("actionFeat 长度 %d 不足以放下 %d 个候选",
			len(actionFeat), options)
	}
	if options == 0 {
		return []float32{}, nil
	}
	return p.actor.score(xImp, seq, actionFeat, options), nil
}

// Decision 一次完整决策的结果;等价 C++ Policy::Decision。
type Decision struct {
	Move    CardSet   // 选中的具体出牌(空 = 过牌)
	Slot    int       // 选中槽位;无候选时 -1
	Options []CardSet // 候选具体出牌(空 = 过牌)
	Logits  []float32 // 每个候选的得分
	Probs   []float32 // softmax(仅展示用)
}

// Decide 完整决策路径: 编码 -> 枚举 -> 打分 -> argmax;
// 等价 Java NeuralAiPlayer.decide / C++ Policy::Decide。
func (p *Policy) Decide(view *PublicView) (Decision, error) {
	var d Decision
	options := LegalOptions(view)
	d.Options = make([]CardSet, len(options))
	for i, o := range options {
		d.Options[i] = o.Concrete
	}
	if len(options) == 0 {
		d.Slot = -1
		return d, nil
	}
	if len(options) == 1 {
		d.Slot = 0
		d.Move = options[0].Concrete
		d.Logits = []float32{0}
		d.Probs = []float32{1}
		return d, nil
	}
	xImp, seq := encode(view)
	actionFeat := make([]float32, len(options)*ActionSize)
	for i := range options {
		copy(actionFeat[i*ActionSize:(i+1)*ActionSize], options[i].Feature[:])
	}
	logits, err := p.Score(xImp, seq, actionFeat, len(options))
	if err != nil {
		return d, err
	}
	d.Logits = logits
	best := 0
	for i := 1; i < len(logits); i++ {
		if logits[i] > logits[best] {
			best = i
		}
	}
	d.Slot = best
	d.Move = options[best].Concrete
	// softmax 用 double, 只影响展示, 不影响 argmax
	maxLogit := float64(logits[best])
	sum := 0.0
	probs := make([]float64, len(logits))
	for i, v := range logits {
		probs[i] = math.Exp(float64(v) - maxLogit)
		sum += probs[i]
	}
	d.Probs = make([]float32, len(logits))
	for i, v := range probs {
		d.Probs[i] = float32(v / sum)
	}
	return d, nil
}

// dot 点积,float32 累加以保持与 libtorch 相同的量级。
func dot(a, b []float32) float32 {
	var acc float32
	for i := range a {
		acc += a[i] * b[i]
	}
	return acc
}

func sigmoid(x float32) float32 {
	return float32(1 / (1 + math.Exp(float64(-x))))
}

func tanh32(x float32) float32 {
	return float32(math.Tanh(float64(x)))
}

// lstmLastHidden 一层 LSTM、5 个时间步,只取最后一步的隐状态 h(cell 丢弃);
// 等价 TorchScript 里 self.lstm(seq)[1][0]。
//
// 门块顺序为 i(输入门), f(遗忘门), g(候选/cell 输入, tanh), o(输出门),即 PyTorch
// weight_ih_l0 的实际拼接顺序(manifest 的 gateOrder 也是这个口径)。别按 cuDNN 习惯
// 写成 i,h,f,c —— 那样 logits 会偏到千分位以上、argmax 大面积出错。
func (a *actorNet) lstmLastHidden(seq []float32) [LstmHidden]float32 {
	var gates [LstmGates]float32
	var cell [LstmHidden]float32   // c 初始化为全零
	var hidden [LstmHidden]float32 // h 初始化为全零
	for t := 0; t < LstmSteps; t++ {
		x := seq[t*LstmIn : (t+1)*LstmIn]
		// 历史序列前部补零, 整步全零时可以跳过一次 429 维点积
		xNonZero := false
		for _, v := range x {
			if v != 0 {
				xNonZero = true
				break
			}
		}
		for g := 0; g < LstmGates; g++ {
			accI := float32(0)
			if xNonZero {
				accI = dot(a.wIh[g*LstmIn:(g+1)*LstmIn], x)
			}
			acc := accI + a.bIh[g]
			accH := dot(a.wHh[g*LstmHidden:(g+1)*LstmHidden], hidden[:])
			gates[g] = acc + accH + a.bHh[g]
		}
		for k := 0; k < LstmHidden; k++ {
			iGate := sigmoid(gates[k])
			fGate := sigmoid(gates[LstmHidden+k])
			gGate := tanh32(gates[2*LstmHidden+k])
			oGate := sigmoid(gates[3*LstmHidden+k])
			cell[k] = fGate*cell[k] + iGate*gGate
			hidden[k] = oGate * tanh32(cell[k])
		}
	}
	return hidden
}

// linear 全连接: dst = W x + b(relu 为真时再过 relu, 就地);W 为 [out][in] 行主序。
// 累加顺序与其余语言实现一致: 先求点积再加偏置。
func linear(dst, src, w, b []float32, out, in int, relu bool) {
	for o := 0; o < out; o++ {
		acc := dot(w[o*in:(o+1)*in], src) + b[o]
		if relu && acc < 0 {
			acc = 0
		}
		dst[o] = acc
	}
}

// score 一次决策的前向: LSTM 末步隐状态与 xImp 拼成 node,每个候选再拼上自己的
// 动作特征,过 4 层 relu 全连接与 head(不加 relu),得到每个候选的得分。
//
// 与 TorchScript 的 concat(node, actionFeat) 数学上完全一致,这里把 l1 拆成
// "node 部分先算一次" + "每个候选只累加 149 维里非零的那几维"
// (加 0.0 是恒等变换,跳过零维不改变结果),差别只在浮点求和顺序。
func (a *actorNet) score(xImp, seq, actionFeat []float32, options int) []float32 {
	hidden := a.lstmLastHidden(seq)

	// node = concat(xImp, h_last) -> 1316
	node := make([]float32, NodeDim)
	copy(node, xImp[:NodeSize])
	copy(node[NodeSize:], hidden[:])

	// l1 的 node 部分对同一状态的所有候选相同, 先算一次(bias 在前累加, 与 C++ 一致)
	pre1 := make([]float32, MlpHidden)
	copy(pre1, a.l1b)
	for o := 0; o < MlpHidden; o++ {
		row := a.l1w[o*JointSize : o*JointSize+NodeDim]
		acc := pre1[o]
		for j := 0; j < NodeDim; j++ {
			acc += row[j] * node[j]
		}
		pre1[o] = acc
	}

	var nzIdx [ActionSize]int
	buf1 := make([]float32, MlpHidden)
	buf2 := make([]float32, MlpHidden)
	buf3 := make([]float32, MlpWide)
	logits := make([]float32, options)
	for i := 0; i < options; i++ {
		feat := actionFeat[i*ActionSize : (i+1)*ActionSize]
		nz := nzIdx[:0]
		for j := 0; j < ActionSize; j++ {
			if feat[j] != 0 {
				nz = append(nz, j) // 149 维里大部分是 0
			}
		}
		for o := 0; o < MlpHidden; o++ {
			row := a.l1w[o*JointSize+NodeDim : (o+1)*JointSize]
			acc := pre1[o]
			for _, j := range nz {
				acc += row[j] * feat[j]
			}
			if acc < 0 {
				acc = 0
			}
			buf1[o] = acc // relu(l1)
		}
		linear(buf2, buf1, a.l2w, a.l2b, MlpHidden, MlpHidden, true)
		linear(buf1, buf2, a.l3w, a.l3b, MlpHidden, MlpHidden, true)
		linear(buf3, buf1, a.l4w, a.l4b, MlpWide, MlpHidden, true)
		acc := a.headb[0]
		for j := 0; j < MlpWide; j++ {
			acc += a.headw[j] * buf3[j]
		}
		logits[i] = acc // head 不加 relu
	}
	return logits
}
