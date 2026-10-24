package paodekuai

import (
	"bufio"
	"compress/gzip"
	"os"
	"strconv"
	"strings"
	"testing"
)

// golden 记录的读取器,对应 Java 测试侧 GoldenReader / C++ test/golden_reader.h。
//
// 记录是行式的: 每个数值块以一行 "<KEY> <个数>" 表头开头,其后每行最多 20 个值,
// 所以按 token 逐个读取最稳妥。牌面用训练侧写法 "3456789TJQKA2",过牌为 "-"。
// 完整格式见 tools/pddump.cpp 文件头注释:
//
//	PAOGOLDEN 1 <games> <seed0>
//	GAME <g> / DEAL <seat> <cards> x3 / LEAD <seat>
//	DEC <d> TURN <seat> HIST <history length>
//	XIMP 1188 <floats> / SEQ 2145 <floats>
//	OPT <L> / OPTLINE <cards> / AFEAT 149 <floats> (x L)
//	LOGIT <L> <floats> / CHOSEN <slot> <cards> / ACT <seat> <cards> / ENDDEC
//	ENDGAME

const goldenRelPath = "data/golden/golden24.txt.gz"

type goldenReader struct {
	file    *os.File
	gz      *gzip.Reader
	scanner *bufio.Scanner
	tokens  []string
	index   int
}

// openGolden 从仓库根的 data/ 目录打开 golden 记录(三种语言共用同一份基准)。
func openGolden(t *testing.T) *goldenReader {
	t.Helper()
	path := FindSharedData(goldenRelPath)
	if path == "" {
		t.Fatalf("未找到 %s(已尝试当前目录及其上三层,该文件应放在仓库 data/golden/ 下)", goldenRelPath)
	}
	f, err := os.Open(path)
	if err != nil {
		t.Fatalf("打开 %s 失败: %v", path, err)
	}
	gz, err := gzip.NewReader(bufio.NewReader(f))
	if err != nil {
		f.Close()
		t.Fatalf("解压 %s 失败: %v", path, err)
	}
	scanner := bufio.NewScanner(gz)
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	return &goldenReader{file: f, gz: gz, scanner: scanner}
}

func (g *goldenReader) close() {
	g.gz.Close()
	g.file.Close()
}

// hasNext 是否还有未读的 token。
func (g *goldenReader) hasNext() bool {
	return g.index < len(g.tokens) || g.readLine()
}

func (g *goldenReader) nextToken() string {
	for g.index >= len(g.tokens) {
		if !g.readLine() {
			panic("golden 文件提前结束")
		}
	}
	tok := g.tokens[g.index]
	g.index++
	return tok
}

// readLine 读入下一非空行并填满缓冲区;文件结束返回 false。
func (g *goldenReader) readLine() bool {
	for g.scanner.Scan() {
		line := strings.TrimSpace(g.scanner.Text())
		if line == "" {
			continue
		}
		g.tokens = strings.Fields(line)
		g.index = 0
		return true
	}
	if err := g.scanner.Err(); err != nil {
		panic("读取 golden 失败: " + err.Error())
	}
	return false
}

// readFloats 读取 n 个 float32;十进制文本到 float32 的转换在 C++/Go 两侧都是
// 正确舍入的,因此逐位可比。
func (g *goldenReader) readFloats(n int) []float32 {
	out := make([]float32, n)
	for i := 0; i < n; i++ {
		v, err := strconv.ParseFloat(g.nextToken(), 32)
		if err != nil {
			panic("golden 里的浮点数读不出来: " + err.Error())
		}
		out[i] = float32(v)
	}
	return out
}

// expect 消费一行 "<key> <个数>" 表头并返回个数。
func (g *goldenReader) expect(key string) int {
	if got := g.nextToken(); got != key {
		panic("golden 结构异常: 期望 " + key + ", 实际 " + got)
	}
	n, err := strconv.Atoi(g.nextToken())
	if err != nil {
		panic("golden 的个数读不出来: " + err.Error())
	}
	return n
}

// expectToken 消费一个应当等于 key 的 token。
func (g *goldenReader) expectToken(key string) {
	if got := g.nextToken(); got != key {
		panic("golden 结构异常: 期望 " + key + ", 实际 " + got)
	}
}

func (g *goldenReader) nextInt() int {
	n, err := strconv.Atoi(g.nextToken())
	if err != nil {
		panic("golden 的整数读不出来: " + err.Error())
	}
	return n
}

// goldenDecision golden 里一个决策的原始记录。
type goldenDecision struct {
	turn       int
	hist       int
	xImp       []float32
	seq        []float32
	optLines   []string // "-" 表示过牌
	feats      [][]float32
	logits     []float32
	chosenSlot int
	chosen     string
	actSeat    int
	actCards   string
}

// readGoldenDecision 读完 DEC ... ENDDEC 之间的全部内容。
func (g *goldenReader) readGoldenDecision() *goldenDecision {
	d := &goldenDecision{}
	g.expectToken("TURN")
	d.turn = g.nextInt()
	g.expectToken("HIST")
	d.hist = g.nextInt()

	if n := g.expect("XIMP"); n != NodeSize {
		panic("XIMP 个数 " + strconv.Itoa(n) + " 与实现不符")
	}
	d.xImp = g.readFloats(NodeSize)

	if n := g.expect("SEQ"); n != SeqSize {
		panic("SEQ 个数 " + strconv.Itoa(n) + " 与实现不符")
	}
	d.seq = g.readFloats(SeqSize)

	options := g.expect("OPT")
	d.optLines = make([]string, 0, options)
	d.feats = make([][]float32, 0, options)
	for i := 0; i < options; i++ {
		g.expectToken("OPTLINE")
		cards := g.nextToken()
		if cards != "-" {
			if _, err := ParseCards(cards); err != nil {
				panic("候选动作牌面异常 " + cards + ": " + err.Error())
			}
		}
		d.optLines = append(d.optLines, cards)
		if n := g.expect("AFEAT"); n != ActionSize {
			panic("AFEAT 个数 " + strconv.Itoa(n) + " 与实现不符")
		}
		d.feats = append(d.feats, g.readFloats(ActionSize))
	}

	if n := g.expect("LOGIT"); n != options {
		panic("LOGIT 个数与候选数不符")
	}
	d.logits = g.readFloats(options)

	g.expectToken("CHOSEN")
	d.chosenSlot = g.nextInt()
	d.chosen = g.nextToken()

	g.expectToken("ACT")
	d.actSeat = g.nextInt()
	d.actCards = g.nextToken()
	g.expectToken("ENDDEC")
	return d
}

// goldenGame golden 里一局的发牌与先手。
type goldenGame struct {
	index     int
	lead      int
	deals     [3]string
	decisions []*goldenDecision
}

// readGoldenGame 读完 GAME ... 的发牌部分(不含决策)。
func (g *goldenReader) readGoldenGame() *goldenGame {
	game := &goldenGame{}
	game.index = g.nextInt()
	for {
		tag := g.nextToken()
		switch tag {
		case "DEAL":
			seat := g.nextInt()
			game.deals[seat] = g.nextToken()
		case "LEAD":
			game.lead = g.nextInt()
			return game
		default:
			panic("golden 发牌结构异常: " + tag)
		}
	}
}
