package paodekuai

import (
	"archive/zip"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"os"
	"path/filepath"
	"strings"
)

// 纯 Go 的策略网络权重加载(不需要 cgo、libtorch 或 ONNX runtime)。
//
// data/models/ts/pao_ts.pt 是 TorchScript 归档: 张量数据以 STORED(不压缩)的 zip 条目
// <root>/data/<i> 存放,是小端 float32 行主序的裸字节,标准库 archive/zip 就能读。
// 条目序号 -> 层名 -> 形状 的映射写在同目录的 pao_manifest.json 里(由
// tools/export_actor.py 生成),这里按清单读取并对每一层硬校验形状,
// 与本工程写死的前向不一致时宁可报错也不要算出悄悄偏掉的分数。

// 参数名与结构,与 tools/export_actor.py 的 PARAM_NAMES 一一对应。
const (
	paramLSTMWeightIH = "lstm.weight_ih_l0"
	paramLSTMWeightHH = "lstm.weight_hh_l0"
	paramLSTMBiasIH   = "lstm.bias_ih_l0"
	paramLSTMBiasHH   = "lstm.bias_hh_l0"
	paramL1Weight     = "l1.weight"
	paramL1Bias       = "l1.bias"
	paramL2Weight     = "l2.weight"
	paramL2Bias       = "l2.bias"
	paramL3Weight     = "l3.weight"
	paramL3Bias       = "l3.bias"
	paramL4Weight     = "l4.weight"
	paramL4Bias       = "l4.bias"
	paramHeadWeight   = "head.weight"
	paramHeadBias     = "head.bias"
)

// 网络结构常量(与 cpp/include/paodekuai/policy.h 的 kPolicy* 一致)。
const (
	// LstmHidden LSTM 隐层宽度
	LstmHidden = 128
	// LstmGates LSTM 门块宽度 = 4 * LstmHidden
	LstmGates = 4 * LstmHidden // 512
	// NodeDim 拼接进 l1 的状态向量维度: xImp(1188) + LSTM 末步隐状态(128)
	NodeDim = NodeSize + LstmHidden // 1316
	// JointSize l1 的完整输入维度: node + 单个候选动作特征
	JointSize = NodeDim + ActionSize // 1465
	// MlpHidden 中间层宽度
	MlpHidden = 256
	// MlpWide 第 4 层宽度
	MlpWide = 512
)

// layerSpec 一层的期望形状;cols 为 -1 表示一维 bias。
type layerSpec struct {
	name string
	rows int
	cols int
}

// layerSpecs 清单里必须出现且只出现这些层(按本工程前向写死的形状)。
var layerSpecs = []layerSpec{
	{paramLSTMWeightIH, LstmGates, LstmIn},
	{paramLSTMWeightHH, LstmGates, LstmHidden},
	{paramLSTMBiasIH, LstmGates, -1},
	{paramLSTMBiasHH, LstmGates, -1},
	{paramL1Weight, MlpHidden, JointSize},
	{paramL1Bias, MlpHidden, -1},
	{paramL2Weight, MlpHidden, MlpHidden},
	{paramL2Bias, MlpHidden, -1},
	{paramL3Weight, MlpHidden, MlpHidden},
	{paramL3Bias, MlpHidden, -1},
	{paramL4Weight, MlpWide, MlpHidden},
	{paramL4Bias, MlpWide, -1},
	{paramHeadWeight, 1, MlpWide},
	{paramHeadBias, 1, -1},
}

// manifestParam 清单里的一条参数记录。
type manifestParam struct {
	Entry int    `json:"entry"`
	Name  string `json:"name"`
	Shape []int  `json:"shape"`
}

// actorManifest pao_manifest.json 的内容。
//
// 清单同时给出条目顺序与门块顺序,加载时一并校验,防止导出侧悄悄改约定。
type actorManifest struct {
	Root      string          `json:"root"`
	Dtype     string          `json:"dtype"`
	ByteOrder string          `json:"byteOrder"`
	GateOrder string          `json:"gateOrder"`
	Params    []manifestParam `json:"parameters"`
}

// actorNet 策略网络权重(三个座位共用一个 actor)。
type actorNet struct {
	wIh   []float32 // [512][429]
	wHh   []float32 // [512][128]
	bIh   []float32 // [512]
	bHh   []float32 // [512]
	l1w   []float32 // [256][1465]
	l1b   []float32 // [256]
	l2w   []float32 // [256][256]
	l2b   []float32 // [256]
	l3w   []float32 // [256][256]
	l3b   []float32 // [256]
	l4w   []float32 // [512][256]
	l4b   []float32 // [512]
	headw []float32 // [1][512]
	headb []float32 // [1]
}

// readWeights 从 dir 下的 pao_ts.pt 与 pao_manifest.json 读出全部权重。
func readWeights(dir string) (*actorNet, error) {
	manifestPath := filepath.Join(dir, "pao_manifest.json")
	archivePath := filepath.Join(dir, "pao_ts.pt")

	raw, err := os.ReadFile(manifestPath)
	if err != nil {
		return nil, fmt.Errorf("读取清单 %s 失败: %w", manifestPath, err)
	}
	var mf actorManifest
	if err := json.Unmarshal(raw, &mf); err != nil {
		return nil, fmt.Errorf("解析清单 %s 失败: %w", manifestPath, err)
	}
	if mf.Dtype != "float32" {
		return nil, fmt.Errorf("%s: 不支持的权重类型 %q", manifestPath, mf.Dtype)
	}
	if mf.ByteOrder != "little" {
		return nil, fmt.Errorf("%s: 不支持的字节序 %q", manifestPath, mf.ByteOrder)
	}
	// 门块顺序写错会让 logits 偏到千分位以上,这里挡住清单侧的变更
	if mf.GateOrder != "" && !strings.Contains(mf.GateOrder, "i (input gate), f (forget gate)") {
		return nil, fmt.Errorf("%s: gateOrder 与本工程前向实现不符: %q", manifestPath, mf.GateOrder)
	}
	if mf.Root == "" {
		mf.Root = "pao_ts"
	}
	if len(mf.Params) != len(layerSpecs) {
		return nil, fmt.Errorf("%s: 参数个数 %d, 期望 %d(网络结构与本工程不一致)",
			manifestPath, len(mf.Params), len(layerSpecs))
	}

	zf, err := zip.OpenReader(archivePath)
	if err != nil {
		return nil, fmt.Errorf("打开权重 %s 失败: %w", archivePath, err)
	}
	defer zf.Close()

	byName := make(map[string][]float32, len(layerSpecs))
	got := make(map[string]bool, len(layerSpecs))
	for _, p := range mf.Params {
		spec := findLayerSpec(p.Name)
		if spec == nil {
			return nil, fmt.Errorf("%s: 清单里出现未知层 %s", manifestPath, p.Name)
		}
		if got[p.Name] {
			return nil, fmt.Errorf("%s: 层 %s 重复出现", manifestPath, p.Name)
		}
		if err := shapeMatches(p, *spec); err != nil {
			return nil, fmt.Errorf("%s: 层 %s 形状不符(网络与本工程前向实现不一致): %v",
				manifestPath, p.Name, err)
		}
		name := fmt.Sprintf("%s/data/%d", mf.Root, p.Entry)
		entry := lookupEntry(&zf.Reader, name)
		if entry == nil {
			return nil, fmt.Errorf("%s: 缺少条目 %s(清单与归档不匹配)", archivePath, name)
		}
		if entry.Method != zip.Store {
			return nil, fmt.Errorf("%s: 条目 %s 不是 STORED(压缩权重无法用标准库裸读)",
				archivePath, name)
		}
		numel := int64(1)
		for _, d := range p.Shape {
			numel *= int64(d)
		}
		if int64(entry.UncompressedSize64) != numel*4 {
			return nil, fmt.Errorf("%s: 条目 %s 大小 %d, 按形状 %v 期望 %d",
				archivePath, name, entry.UncompressedSize64, p.Shape, numel*4)
		}
		rc, err := entry.Open()
		if err != nil {
			return nil, fmt.Errorf("%s: 打开条目 %s 失败: %w", archivePath, name, err)
		}
		buf, err := io.ReadAll(rc)
		rc.Close()
		if err != nil {
			return nil, fmt.Errorf("%s: 读取条目 %s 失败: %w", archivePath, name, err)
		}
		byName[p.Name] = floatsFromLE(buf)
		got[p.Name] = true
	}
	for _, spec := range layerSpecs {
		if !got[spec.name] {
			return nil, fmt.Errorf("%s: 清单缺少层 %s", manifestPath, spec.name)
		}
	}

	return &actorNet{
		wIh:   byName[paramLSTMWeightIH],
		wHh:   byName[paramLSTMWeightHH],
		bIh:   byName[paramLSTMBiasIH],
		bHh:   byName[paramLSTMBiasHH],
		l1w:   byName[paramL1Weight],
		l1b:   byName[paramL1Bias],
		l2w:   byName[paramL2Weight],
		l2b:   byName[paramL2Bias],
		l3w:   byName[paramL3Weight],
		l3b:   byName[paramL3Bias],
		l4w:   byName[paramL4Weight],
		l4b:   byName[paramL4Bias],
		headw: byName[paramHeadWeight],
		headb: byName[paramHeadBias],
	}, nil
}

func findLayerSpec(name string) *layerSpec {
	for i := range layerSpecs {
		if layerSpecs[i].name == name {
			return &layerSpecs[i]
		}
	}
	return nil
}

// shapeMatches 清单形状必须与本工程写死的层形状完全一致。
func shapeMatches(p manifestParam, spec layerSpec) error {
	if spec.cols < 0 {
		// bias: 一维
		if len(p.Shape) != 1 || p.Shape[0] != spec.rows {
			return fmt.Errorf("期望一维 %d, 实际 %v", spec.rows, p.Shape)
		}
		return nil
	}
	if len(p.Shape) != 2 || p.Shape[0] != spec.rows || p.Shape[1] != spec.cols {
		return fmt.Errorf("期望 [%d,%d], 实际 %v", spec.rows, spec.cols, p.Shape)
	}
	return nil
}

func lookupEntry(z *zip.Reader, name string) *zip.File {
	for _, cand := range z.File {
		if cand.Name == name {
			return cand
		}
	}
	return nil
}

// floatsFromLE 小端 float32 裸字节 -> []float32。
func floatsFromLE(buf []byte) []float32 {
	out := make([]float32, len(buf)/4)
	for i := range out {
		out[i] = math.Float32frombits(binary.LittleEndian.Uint32(buf[i*4:]))
	}
	return out
}
