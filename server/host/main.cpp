// paodekuai_server -- 跑得快网页端的 fakelua 宿主。
//
// 分工只有一条界线: 规则与推理留在已经过三语逐位对齐验证的 C++ 引擎里, Lua 负责
// HTTP 路由、会话隔离、流程编排、日志与前端数据组装。因此这里注册的 paodekuai.*
// 全是「一手牌进、字符串出」的薄函数, 不做任何展示层决策。
//
// 字符串契约见 server/API.md 的「原生函数」一节:
//   牌面   用引擎的训练侧写法 3456789TJQKA2, 过牌是 "-"
//   一手   "type:rank:len:cards", type 是引擎 MoveType 序号 (0..10), rank 是主牌
//          点数下标 0..12, len 是链长 (非链型 1)
//   列表   用 "|" 分隔, Lua 侧 string.split 即可
//
// 生命周期沿用 flua 的约定 (fakelua/cmd/main.cpp): init 一次, update 每帧一次,
// fini 一次。每次调用都是独立的顶层 Call, 帧 arena 在 Call 入口重置 —— 所以绝不
// 能在 Lua 里写 while true 把请求包进单个 Call, 否则临时表与字符串永不回收。
//
// 会话表由本宿主持有 (Lua 只拿到整数 id), fakelua 没有 GC, 宿主也不会自动回收:
// Lua 必须在会话结束或闲置超时后调用 paodekuai.free(id)。
#include <algorithm>
#include <array>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <iostream>
#include <memory>
#include <random>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

#include "fakelua.h"

#include "paodekuai/api.h"

using namespace fakelua;

namespace {

struct Options
{
    bool once = false;
    int jitType = 0;
    std::string modelDir;
    std::string script;

    bool Parse(int argc, char **argv, std::string *error)
    {
        for (int i = 1; i < argc; ++i) {
            const std::string a = argv[i];
            if (a == "--once") {
                once = true;
            } else if (a.rfind("--jit_type=", 0) == 0) {
                jitType = std::atoi(a.c_str() + 11);
                if (jitType < 0 || jitType > 2) {
                    *error = "--jit_type 只能是 0(tcc) 1(gcc) 2(interp)";
                    return false;
                }
            } else if (a.rfind("--model_dir=", 0) == 0) {
                modelDir = a.substr(12);
            } else if (a == "--help" || a == "-h") {
                *error = "help";
                return false;
            } else if (!a.empty() && a[0] == '-') {
                *error = "未知选项: " + a;
                return false;
            } else if (script.empty()) {
                script = a;
            } else {
                *error = "只接受一个 lua 路径, 多出来的是: " + a;
                return false;
            }
        }
        if (script.empty()) {
            *error = "缺少 lua 目录或文件";
        }
        return error->empty();
    }
};

constexpr const char *kUsage =
    "用法: ./paodekuai_server <lua 目录或文件> [--model_dir=...] [--once] [--jit_type=n]\n"
    "\n"
    "入口固定为 package \"main\" 的 init / update / fini:\n"
    "  function init() ... end      启动一次, 建 HTTP 服务\n"
    "  function update() ... end    每帧一次, 泵 IO 并分发请求\n"
    "  function fini() ... end      退出前一次\n"
    "\n"
    "跑得快引擎以 paodekuai.* 原生函数暴露 (newgame/legal/decide/apply/state/...),\n"
    "规则只有一份, Lua 只做 HTTP 与会话编排。监听端口取环境变量 PAODEKUAI_PORT。\n"
    "自检: PAODEKUAI_SMOKE=1 ./paodekuai_server lua --once\n";

constexpr const char *kInitEntry = "main.init";
constexpr const char *kUpdateEntry = "main.update";
constexpr const char *kFiniEntry = "main.fini";

std::atomic<bool> g_exit_requested{false};
std::atomic<int> g_exit_code{0};

// 一局牌的全部私有状态。跑得快没有叫分: 发牌即开局, 首出座位由引擎按
// 「4 张 3 里随机指定一张」的规则给出。
struct Session
{
    paodekuai::GameState state;
};

class Registry
{
public:
    int64_t Insert(std::unique_ptr<Session> s)
    {
        const int64_t id = ++nextId_;
        items_.emplace(id, std::move(s));
        return id;
    }

    // id 在会话存续期内稳定, Lua 把它存在会话表里。fakelua 的 HTTP 分发全部在
    // update 帧内的主线程上串行执行, 所以裸指针不需要并发保护。
    Session *Get(int64_t id)
    {
        const auto it = items_.find(id);
        return it == items_.end() ? nullptr : it->second.get();
    }

    bool Erase(int64_t id)
    {
        return items_.erase(id) > 0;
    }

    size_t Size() const { return items_.size(); }

private:
    std::unordered_map<int64_t, std::unique_ptr<Session>> items_;
    int64_t nextId_ = 0;
};

Registry g_registry;
std::unique_ptr<paodekuai::Policy> g_policy;
std::string g_policyError;

// 一手牌 -> "type:rank:len:cards"
std::string FormatCards(const paodekuai::CardSet &cs)
{
    paodekuai::MoveInfo info = paodekuai::detectMove(cs);
    if (cs.empty()) {
        return "0:-1:0:-";  // 过牌
    }
    return std::to_string(info.type) + ":" + std::to_string(info.rank) + ":" +
           std::to_string(info.len) + ":" + cs.str();
}

// 一个坏请求不该让整个进程退出: fakelua 里错误逃出 update 等于服务终止, 所以引擎
// 异常一律在原生边界转成回退值, 原因写 stderr。
template<typename Fn>
std::string Guard(const std::string &fallback, Fn &&fn)
{
    try {
        return fn();
    } catch (const std::exception &e) {
        std::fprintf(stderr, "paodekuai_server: 原生调用失败: %s\n", e.what());
        return fallback;
    }
}

// 合法动作 -> "type:rank:len:cards|..." 列表
std::string LegalList(const Session &s)
{
    std::string out;
    for (const paodekuai::CardSet &cs : s.state.legal()) {
        if (!out.empty()) {
            out += "|";
        }
        out += FormatCards(cs);
    }
    return out;
}

// JSON 里「没有值」一律写成空串而不是 null: Lua 侧的数组一旦出现 nil 空洞,
// 长度就不可靠
std::string Quote(const std::string &v)
{
    return "\"" + v + "\"";
}

// 公开状态: 严格按 viewer 过滤, 只有 viewer 自己的手牌是明文, 其余家只给张数。
// 终局时三家手牌在 revealed 里公开。
std::string StateJson(const Session &s, int viewer)
{
    const paodekuai::PublicView view = s.state.getPublicView(viewer);
    std::ostringstream j;
    j << "{\"over\":" << (s.state.over ? "true" : "false")
      << ",\"winner\":" << s.state.winner
      << ",\"active\":" << s.state.turn
      << ",\"lead\":" << view.lastPlayerId
      << ",\"viewer\":" << viewer
      << ",\"myHand\":\"" << view.myHand.str() << "\""
      << ",\"toBeat\":" << Quote(FormatCards(view.toBeat))
      << ",\"counts\":[" << view.cardCounts[0] << "," << view.cardCounts[1] << ","
      << view.cardCounts[2] << "]"
      << ",\"lastMove\":[";
    for (int i = 0; i < 3; ++i) {
        j << (i ? "," : "") << Quote(FormatCards(view.lastMove[i]));
    }
    j << "],\"history\":[";
    bool first = true;
    for (const paodekuai::MoveRecord &rec : view.history) {
        j << (first ? "" : ",") << "\"" << rec.playerId << ":"
          << (rec.cards.empty() ? std::string("-") : rec.cards.str()) << "\"";
        first = false;
    }
    j << "]";
    if (s.state.over) {
        j << ",\"revealed\":[";
        for (int i = 0; i < 3; ++i) {
            j << (i ? "," : "") << Quote(s.state.hand[i].str());
        }
        j << "]";
    }
    j << "}";
    return j.str();
}

// ---- 原生回调 ---------------------------------------------------------------

std::string NoSession()
{
    return "{\"error\":\"no session\"}";
}

std::string NativeVersion()
{
    return g_policy != nullptr ? "engine=cpp policy=loaded" : "engine=cpp policy=" + g_policyError;
}

int64_t NativeNewGame(int64_t seed)
{
    auto sess = std::make_unique<Session>();
    const uint64_t s = seed > 0 ? static_cast<uint64_t>(seed)
                                : static_cast<uint64_t>(std::random_device{}());
    paodekuai::Rng rng(s);
    sess->state = paodekuai::GameState::deal(rng);
    return g_registry.Insert(std::move(sess));
}

std::string NativeLegal(int64_t id)
{
    return Guard(std::string(), [id] {
        Session *sess = g_registry.Get(id);
        if (sess == nullptr || sess->state.over) {
            return std::string();
        }
        return LegalList(*sess);
    });
}

// 策略网络出一手: "type:rank:len:cards|座位|概率:牌面,..." (最多前 6 个候选)
// 中文名与排版全在 Lua 侧, 这里只出数字与牌面。
std::string NativeDecide(int64_t id)
{
    return Guard(std::string(), [id] {
        Session *sess = g_registry.Get(id);
        if (sess == nullptr || sess->state.over || g_policy == nullptr) {
            return std::string();
        }
        const int active = sess->state.turn;
        const paodekuai::Policy::Decision d = g_policy->Decide(sess->state.getPublicView(active));
        std::ostringstream out;
        out << FormatCards(d.move) << "|" << active;
        std::vector<int> order(d.options.size());
        for (int i = 0; i < static_cast<int>(order.size()); ++i) {
            order[static_cast<size_t>(i)] = i;
        }
        std::sort(order.begin(), order.end(), [&](int a, int b) {
            return d.probs[static_cast<size_t>(a)] > d.probs[static_cast<size_t>(b)];
        });
        const int top = static_cast<int>(std::min<size_t>(6, d.options.size()));
        for (int i = 0; i < top; ++i) {
            const int k = order[static_cast<size_t>(i)];
            char buf[32];
            std::snprintf(buf, sizeof buf, "%.5f", d.probs[static_cast<size_t>(k)]);
            out << (i == 0 ? "|" : ",") << buf << ":"
                << (d.options[static_cast<size_t>(k)].empty() ? std::string("-")
                                                              : d.options[static_cast<size_t>(k)].str());
        }
        return out.str();
    });
}

std::string NativeApply(int64_t id, std::string cards)
{
    return Guard(std::string(), [id, &cards] {
        Session *sess = g_registry.Get(id);
        if (sess == nullptr || sess->state.over) {
            return std::string();
        }
        paodekuai::CardSet chosen;
        if (cards != "-") {
            try {
                chosen = paodekuai::CardSet::parse(cards);
            } catch (const std::exception &) {
                return std::string();
            }
        }
        for (const paodekuai::CardSet &cs : sess->state.legal()) {
            if (cs == chosen) {
                sess->state.step(chosen);
                return FormatCards(chosen);
            }
        }
        return std::string();
    });
}

std::string NativeState(int64_t id, int64_t viewer)
{
    return Guard(NoSession(), [id, viewer] {
        Session *sess = g_registry.Get(id);
        if (sess == nullptr) {
            return NoSession();
        }
        return StateJson(*sess, static_cast<int>(viewer >= 0 && viewer <= 2 ? viewer : 0));
    });
}

int64_t NativeMinSteps(std::string cards)
{
    try {
        return paodekuai::minSteps(paodekuai::CardSet::parse(cards));
    } catch (const std::exception &) {
        return -1;
    }
}

int64_t NativeSessions()
{
    return static_cast<int64_t>(g_registry.Size());
}

std::string NativeFree(int64_t id)
{
    return g_registry.Erase(id) ? "1" : "0";
}

int64_t ArgInt(State *s, CVar *args, int n, int idx)
{
    return GetNativeIntArg(s, args, n, idx, 0);
}

std::string ArgStr(State *s, CVar *args, int n, int idx)
{
    if (idx >= n) {
        return std::string();
    }
    return inter::FakeluaToNativeString(s, inter::GetNativeArg(s, args, n, idx));
}

template<typename Arg>
Arg OneArg(State *s, CVar *args, int n, int idx)
{
    if constexpr (std::is_same_v<Arg, std::string>) {
        return ArgStr(s, args, n, idx);
    } else {
        return ArgInt(s, args, n, idx);
    }
}

template<typename Ret, typename... Args, std::size_t... I>
Ret CallPack(State *s, CVar *args, int n, Ret (*fn)(Args...), std::index_sequence<I...>)
{
    return fn(OneArg<Args>(s, args, n, static_cast<int>(I))...);
}

template<typename Ret, typename... Args>
void Native(State *s, const std::string &name, Ret (*fn)(Args...))
{
    RegisterNativeFunction(s, name, static_cast<int>(sizeof...(Args)), false,
                           [fn](State *state, CVar *args, int n) -> CVar {
        using Seq = std::index_sequence_for<Args...>;
        if constexpr (std::is_same_v<Ret, std::string>) {
            return inter::NativeToFakeluaString(state, CallPack(state, args, n, fn, Seq{}));
        } else {
            return inter::NativeToFakeluaLonglong(state,
                                                  static_cast<int64_t>(CallPack(state, args, n, fn, Seq{})));
        }
    });
}

void RegisterEngineApi(State *s)
{
    Native(s, "paodekuai.version", NativeVersion);
    Native(s, "paodekuai.newgame", NativeNewGame);
    Native(s, "paodekuai.legal", NativeLegal);
    Native(s, "paodekuai.decide", NativeDecide);
    Native(s, "paodekuai.apply", NativeApply);
    Native(s, "paodekuai.state", NativeState);
    Native(s, "paodekuai.minsteps", NativeMinSteps);
    Native(s, "paodekuai.sessions", NativeSessions);
    Native(s, "paodekuai.free", NativeFree);

    // 只有这个回调要碰 CVar: 它把退出请求记到宿主的全局标志上
    RegisterNativeFunction(s, "paodekuai.exit", 1, true,
                           [](State *state, CVar *args, int n) -> CVar {
        g_exit_code.store(static_cast<int>(GetNativeIntArg(state, args, n, 0, 0)),
                          std::memory_order_relaxed);
        g_exit_requested.store(true, std::memory_order_relaxed);
        return NativeNil(state);
    });
}

std::vector<std::string> CollectLuaFiles(const std::string &path)
{
    std::error_code ec;
    if (std::filesystem::is_directory(path, ec)) {
        std::vector<std::string> files;
        for (const auto &entry : std::filesystem::directory_iterator(path, ec)) {
            if (entry.is_regular_file() && entry.path().extension() == ".lua") {
                files.push_back(entry.path().string());
            }
        }
        std::sort(files.begin(), files.end());
        if (files.empty()) {
            std::cerr << "paodekuai_server: " << path << " 下没有 .lua 文件\n";
        }
        return files;
    }
    return {path};
}

[[noreturn]] void ReportMissingEntry(const char *name)
{
    std::cerr << "paodekuai_server: 找不到入口 '" << name << "'\n"
              << "        脚本必须提供 package \"main\" 的 init / update / fini\n";
    std::exit(1);
}

// init -> update* -> fini。init 失败(返回非 0 或抛错)时不跑 fini。
int RunLifecycle(State *s, const Options &opts)
{
    const JITType jit = static_cast<JITType>(opts.jitType);
    if (!HasEntryFunction(s, jit, kInitEntry)) {
        ReportMissingEntry(kInitEntry);
    }
    if (!opts.once && !HasEntryFunction(s, jit, kUpdateEntry)) {
        ReportMissingEntry(kUpdateEntry);
    }
    if (!HasEntryFunction(s, jit, kFiniEntry)) {
        ReportMissingEntry(kFiniEntry);
    }

    bool initOk = false;
    int exitCode = 0;
    try {
        int code = 0;
        Call(s, jit, kInitEntry, code);
        if (code != 0) {
            exitCode = code;
            std::fprintf(stderr, "paodekuai_server: init 返回 %d\n", code);
        } else {
            initOk = true;
        }
    } catch (const std::exception &e) {
        std::fprintf(stderr, "paodekuai_server: init 失败: %s\n", e.what());
        return 1;
    }

    if (initOk && !opts.once) {
        while (!g_exit_requested.load(std::memory_order_relaxed)) {
            int code = 0;
            try {
                Call(s, jit, kUpdateEntry, code);
            } catch (const std::exception &e) {
                std::fprintf(stderr, "paodekuai_server: update 失败: %s\n", e.what());
                exitCode = 1;
                break;
            }
            if (code != 0) {
                g_exit_code.store(code, std::memory_order_relaxed);
                break;
            }
        }
    }

    if (initOk) {
        try {
            int code = 0;
            Call(s, jit, kFiniEntry, code);
        } catch (const std::exception &e) {
            std::fprintf(stderr, "paodekuai_server: fini 失败: %s\n", e.what());
            exitCode = 1;
        }
    }
    return exitCode != 0 ? exitCode : g_exit_code.load(std::memory_order_relaxed);
}

}  // namespace

int main(int argc, char **argv)
{
    Options opts;
    std::string error;
    if (!opts.Parse(argc, argv, &error)) {
        if (error != "help") {
            std::fprintf(stderr, "paodekuai_server: %s\n\n", error.c_str());
        }
        std::cout << kUsage;
        return error == "help" ? 0 : 1;
    }

    // 权重加载一次, 全部会话共用 (与 Java 的 ActorNetwork 共享方式一致)
    g_policy = std::make_unique<paodekuai::Policy>();
    const std::string dir = opts.modelDir.empty() ? paodekuai::Policy::DefaultModelDir() : opts.modelDir;
    if (!g_policy->Load(dir)) {
        g_policyError = g_policy->Error();
        std::fprintf(stderr, "paodekuai_server: 加载权重失败 (%s): %s\n", dir.c_str(), g_policyError.c_str());
        return 1;
    }

    const FakeluaStateGuard guard;
    State *s = guard.GetState();
    // 3 = Warn: 每次 CompileFile 都会打一串 JIT/INFO 日志, 常驻服务只需要看得见错误
    SetDebugLogLevel(s, 3);
    try {
        RegisterEngineApi(s);

        CompileConfig cfg;
        for (const auto &file : CollectLuaFiles(opts.script)) {
            CompileFile(s, file, cfg);
        }
        return RunLifecycle(s, opts);
    } catch (const std::exception &e) {
        std::fprintf(stderr, "paodekuai_server: %s\n", e.what());
        return 1;
    }
}
