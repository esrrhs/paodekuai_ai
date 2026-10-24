# paodekuai_server HTTP API

真人固定坐 0 号位, 1/2 号位是策略网络 AI。跑得快没有叫分: 新局即发牌,
首出座位由引擎按「场上 4 张 3 随机指定一张」给出, 第一手必须包含 3。
能管必管: 有牌可压时不能过; 下家报单时自由出牌出单张必须顶最大单。

## 通用约定

- 所有响应带 CORS 头; 会话号从 `X-Session-Id` 请求头取 (缺省时服务端现造
  一个并回传)。
- 成功 200 + 状态 DTO; 业务失败 400 + 完整状态 DTO + `error` 字段
  (前端弹 toast)。
- 牌面两套写法:
  - 引擎/原生层: 紧凑字符串, 一个字符一张, `3456789TJQKA2` (T=10), 过牌 `-"-`;
  - HTTP 层: 符号字符串数组 `["3","10","J",...]`。

## 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/status` | `{status, backend, sessions, games}` |
| GET | `/api/game/state` | 当前视角完整状态 DTO |
| POST | `/api/game/new` | 新开一局 |
| POST | `/api/game/play` | `{"cards":["3","10","10"]}` 出牌 |
| POST | `/api/game/pass` | 过牌 (有牌可压时 400) |
| POST | `/api/game/ai-step` | 让当前 AI 行动一步 |
| GET | `/api/game/hint` | AI 给真人的一步提示 `{hint:[...], isPass, type}` |
| GET | `/*` | static/ 静态资源 |
| OPTIONS | 任意 | 204 + CORS 预检 |

## 状态 DTO

```
{
  message,                       // 本次动作的提示文案 (可选)
  stage,                         // "PLAYING" | "GAME_OVER"
  cardCounts: [n0, n1, n2],      // 三家剩余张数
  humanHand: ["3","4",...],      // 真人手牌 (符号数组)
  activePlayer,                  // 当前该谁行动
  isGameOver, winner, isHumanWinner,
  lastMove,                      // {playerId, type(中文名), cards} 桌面要压的牌; 自由出牌时缺席
  canPass,                       // 现在能不能过
  playedStats,                   // 记牌器: {"3":n,...,"2":n} 13 键, 已打出张数
  playerActions: [ {isPass, type, cards} | {} x3 ],  // 每人面前最近一手
  aiThoughts,                    // {"p1": {type,move,timeMs,evals:[{cards,probability}]}}
  logs,                          // 战报 (最多 100 条)
  revealedHands                  // 终局摊牌: 三家剩余手牌 (可选)
}
```

## 原生函数契约 (Lua ↔ C++)

牌面用紧凑写法; 一手牌的线格式是 `"type:rank:len:cards"`, type 是引擎
MoveType 序号 (0 过牌 .. 10 飞机带对), rank 是主牌点数下标 0..12, len 是链长
(非链型 1); 列表用 `|` 分隔。

| 函数 | 线格式 |
|---|---|
| `paodekuai.version()` | `"engine=cpp policy=loaded"` |
| `paodekuai.newgame(seed)` | → 会话 id (seed 0 = 随机) |
| `paodekuai.legal(id)` | `"t:r:l:cards|t:r:l:cards|..."`, 过牌 `"0:-1:0:-"` |
| `paodekuai.decide(id)` | `"t:r:l:cards|seat|p:cards,p:cards,..."`, 概率降序最多 6 个, p 是 0..1 |
| `paodekuai.apply(id, cards)` | 应用动作 (过牌传 `-`), → 新桌面 `"t:r:l:cards"`; 非法返回空串 |
| `paodekuai.state(id, viewer)` | JSON: `{over,winner,active,lead,viewer,myHand,toBeat,counts,lastMove,history,revealed}` |
| `paodekuai.minsteps(cards)` | oracle 最少出完手数 |
| `paodekuai.sessions()` / `paodekuai.free(id)` | 会话计数 / 显式释放 |
| `paodekuai.exit(code)` | 请求宿主退出 |
