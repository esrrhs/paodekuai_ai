// Package paodekuai 是 paodekuai_ai Java/C++ 版的 Go 移植:
// 跑得快(湖南三人 16 张)引擎状态机、与训练侧一致的动作枚举、PerfectPao 输入编码,
// 以及纯 Go(不依赖 cgo/libtorch/ONNX)的策略网络推理。
//
// 权重与对齐基准与 Java/C++ 完全共用(仓库根目录 data/ 下),
// 候选动作集合、xImp/seq/actionFeat 逐位对齐,logits 差异只来自浮点求和顺序。
package paodekuai

import (
	"fmt"
	"os"
	"path/filepath"
)

// 点数索引 0..12 = 3 4 5 6 7 8 9 T J Q K A 2,点数大小即索引顺序(3 < ... < K < A < 2)。
// 牌集一律用 [13]int8 张数向量表示,全零即"过牌"。
const (
	// Ranks 点数档数(3..2)
	Ranks = 13
	// ChainRanks 能成链的点数档数(3..A);2 永不参与链
	ChainRanks = 12
	// DeckSize 整副牌张数: 3..K 各 4 张, A 3 张, 2 一张
	DeckSize = 48
	// HandSize 每人手牌张数(三人 16 张, 无底牌)
	HandSize = 16
)

// rankChars 牌面字符,即训练侧/golden 文件的写法 "3456789TJQKA2"。
var rankChars = [Ranks]byte{'3', '4', '5', '6', '7', '8', '9', 'T', 'J', 'Q', 'K', 'A', '2'}

// RankChar 点数索引 -> 牌面字符;等价 C++ rankChar。
func RankChar(rankIndex int) byte { return rankChars[rankIndex] }

// CharRank 牌面字符 -> 点数索引,不认识返回 -1;等价 C++ charRank。
func CharRank(ch byte) int {
	for r := 0; r < Ranks; r++ {
		if rankChars[r] == ch {
			return r
		}
	}
	return -1
}

// CardSet 一手牌(13 个点数的张数多重集);空集(全零)表示过牌。
// 定长数组,赋值即拷贝。
type CardSet [Ranks]int8

// Total 手牌总张数。
func (c CardSet) Total() int {
	s := 0
	for _, x := range c {
		s += int(x)
	}
	return s
}

// Empty 是否为空(即过牌)。
func (c CardSet) Empty() bool { return c.Total() == 0 }

// Add 加入 n 张指定点数的牌。
func (c *CardSet) Add(rank, n int) { c[rank] += int8(n) }

// AddSet 并入另一手牌。
func (c *CardSet) AddSet(o CardSet) {
	for r := 0; r < Ranks; r++ {
		c[r] += o[r]
	}
}

// Sub 减去另一手牌。
func (c *CardSet) Sub(o CardSet) {
	for r := 0; r < Ranks; r++ {
		c[r] -= o[r]
	}
}

// Contains 是否包含另一手牌的每一点数张数。
func (c CardSet) Contains(o CardSet) bool {
	for r := 0; r < Ranks; r++ {
		if c[r] < o[r] {
			return false
		}
	}
	return true
}

// Clear 清空。
func (c *CardSet) Clear() { *c = CardSet{} }

// Key 张数多重集的稳定 64 位指纹: 每点数占 3 位(13*3 = 39 位)。
// 等价 C++ CardSet::key,用于动作去重与 oracle 记忆化。
func (c CardSet) Key() uint64 {
	var k uint64
	for r := 0; r < Ranks; r++ {
		k |= uint64(c[r]) << (3 * r)
	}
	return k
}

// Str 升序点数的牌面写法,如 "334455A2";过牌为 ""。
func (c CardSet) Str() string {
	buf := make([]byte, 0, c.Total())
	for r := 0; r < Ranks; r++ {
		for k := 0; k < int(c[r]); k++ {
			buf = append(buf, rankChars[r])
		}
	}
	return string(buf)
}

// ParseCards 牌面字符串 -> CardSet;遇到无法识别的字符报错。
// "-" 或空串表示过牌(全零);等价 C++ CardSet::parse(golden 里 "-" 为过)。
func ParseCards(cards string) (CardSet, error) {
	var m CardSet
	if cards == "-" || cards == "" {
		return m, nil
	}
	for i := 0; i < len(cards); i++ {
		r := CharRank(cards[i])
		if r < 0 {
			return m, fmt.Errorf("无法识别的牌面字符 %q", cards[i:i+1])
		}
		m[r]++
	}
	return m, nil
}

// Rng 确定性 splitmix64 伪随机数,与训练侧/C++ 完全一致。
type Rng struct{ state uint64 }

// NewRng 构造 RNG;seed 为 0 时使用黄金比例常数(与 C++ Rng(0) 一致)。
func NewRng(seed uint64) *Rng {
	s := seed
	if s == 0 {
		s = 0x9e3779b97f4a7c15
	}
	return &Rng{state: s}
}

// NextU64 splitmix64 输出。
func (r *Rng) NextU64() uint64 {
	r.state += 0x9e3779b97f4a7c15
	z := r.state
	z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9
	z = (z ^ (z >> 27)) * 0x94d049bb133111eb
	return z ^ (z >> 31)
}

// Below [0, n) 内的均匀整数。
func (r *Rng) Below(n int) int { return int(r.NextU64() % uint64(n)) }

// ShuffledDeck 洗好的 48 张牌(点数索引): 3..K 各 4 张, A 3 张, 2 一张。
// Fisher-Yates,与 C++ shuffledDeck 逐步一致。
func ShuffledDeck(rng *Rng) [DeckSize]int {
	var deck [DeckSize]int
	i := 0
	for r := 0; r <= 10; r++ { // 3..K, 各 4 张
		for k := 0; k < 4; k++ {
			deck[i] = r
			i++
		}
	}
	for k := 0; k < 3; k++ { // A 3 张
		deck[i] = 11
		i++
	}
	deck[i] = 12 // 2 一张
	for j := DeckSize - 1; j > 0; j-- {
		k := rng.Below(j + 1)
		deck[j], deck[k] = deck[k], deck[j]
	}
	return deck
}

// 共享资产的定位: data/ 与 java/ go/ cpp/ 平级,同一份文件在不同启动方式下的
// 相对路径不一样(从 go/ 跑测试、从仓库根跑 CI 都可能),这里按顺序试探当前
// 目录及其上层。等价 Java nn.SharedData / C++ FindSharedData。

// maxSharedParents 最多向上找几层目录。
const maxSharedParents = 3

// FindSharedData 返回仓库根 relativeToRepoRoot 的实际路径,找不到返回 "";
// 等价 Java SharedData.find。
func FindSharedData(relativeToRepoRoot string) string {
	base, err := os.Getwd()
	if err != nil {
		return ""
	}
	for up := 0; up <= maxSharedParents; up++ {
		p := base
		for i := 0; i < up; i++ {
			p = filepath.Join(p, "..")
		}
		candidate := filepath.Join(p, filepath.FromSlash(relativeToRepoRoot))
		if _, err := os.Stat(candidate); err == nil {
			return candidate
		}
	}
	return ""
}

// ResolveSharedData 同 FindSharedData,但找不到时返回按当前目录解释的路径,
// 让调用方抛出带有具体文件名的错误信息;等价 Java SharedData.resolve。
func ResolveSharedData(relativeToRepoRoot string) string {
	if found := FindSharedData(relativeToRepoRoot); found != "" {
		return found
	}
	abs, err := filepath.Abs(filepath.FromSlash(relativeToRepoRoot))
	if err != nil {
		return relativeToRepoRoot
	}
	return abs
}
