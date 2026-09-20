// 跑得快网页端游戏逻辑与交互控制 (多 Session 支持与独立出牌区)
document.addEventListener("DOMContentLoaded", () => {
    let currentState = null;
    let selectedCards = [];
    let isAiStepInProgress = false;

    // 获取或初始化多用户独立 Session ID
    let sessionId = localStorage.getItem("paodekuai_session_id");
    if (!sessionId) {
        sessionId = "sess_" + Date.now() + "_" + Math.random().toString(36).substring(2, 9);
        localStorage.setItem("paodekuai_session_id", sessionId);
    }

    // 封装带 Session 头的 fetch 请求
    async function sessionFetch(url, options = {}) {
        options.headers = options.headers || {};
        options.headers["X-Session-Id"] = sessionId;
        return fetch(url, options);
    }

    // DOM 元素引用
    const p1CardCount = document.getElementById("p1-card-count");
    const p2CardCount = document.getElementById("p2-card-count");
    const p0CardCount = document.getElementById("p0-card-count");
    const p1Status = document.getElementById("p1-status");
    const p2Status = document.getElementById("p2-status");

    const player1Box = document.getElementById("player-1");
    const player2Box = document.getElementById("player-2");

    // 各玩家专属出牌/过牌展示区
    const p0ActionArea = document.getElementById("p0-action-area");
    const p1ActionArea = document.getElementById("p1-action-area");
    const p2ActionArea = document.getElementById("p2-action-area");

    const trickStatusText = document.getElementById("trick-status-text");
    const turnIndicator = document.getElementById("turn-indicator");

    const humanHand = document.getElementById("human-hand");
    const btnPlay = document.getElementById("btn-play");
    const btnHint = document.getElementById("btn-hint");
    const btnPass = document.getElementById("btn-pass");
    const btnNewGame = document.getElementById("btn-new-game");
    const btnRules = document.getElementById("btn-rules");

    const aiThoughtContent = document.getElementById("ai-thought-content");
    const cardCounter = document.getElementById("card-counter");
    const eventLogs = document.getElementById("event-logs");

    const modalOverlay = document.getElementById("modal-overlay");
    const modalTitle = document.getElementById("modal-title");
    const modalMessage = document.getElementById("modal-message");
    const modalBtnRestart = document.getElementById("modal-btn-restart");

    // 规则弹窗
    const rulesModal = document.getElementById("rules-modal");
    const rulesCloseIcon = document.getElementById("rules-close-icon");
    const btnRulesConfirm = document.getElementById("btn-rules-confirm");

    // 初始化加载
    fetchState();

    // 绑定交互事件
    btnPlay.addEventListener("click", handlePlay);
    btnPass.addEventListener("click", handlePass);
    btnHint.addEventListener("click", handleHint);
    btnNewGame.addEventListener("click", handleNewGame);
    modalBtnRestart.addEventListener("click", handleNewGame);

    // 规则弹窗事件
    btnRules.addEventListener("click", () => rulesModal.classList.remove("hidden"));
    rulesCloseIcon.addEventListener("click", () => rulesModal.classList.add("hidden"));
    btnRulesConfirm.addEventListener("click", () => rulesModal.classList.add("hidden"));

    async function fetchState() {
        try {
            const res = await sessionFetch("/api/game/state");
            const data = await res.json();
            updateUI(data);
        } catch (err) {
            console.error("Failed to fetch game state:", err);
        }
    }

    async function handleNewGame() {
        try {
            modalOverlay.classList.add("hidden");
            selectedCards = [];
            const res = await sessionFetch("/api/game/new", { method: "POST" });
            const data = await res.json();
            updateUI(data);
        } catch (err) {
            console.error("Failed to start new game:", err);
        }
    }

    async function handlePlay() {
        if (selectedCards.length === 0) return;
        try {
            const res = await sessionFetch("/api/game/play", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ cards: selectedCards })
            });
            const data = await res.json();
            if (data.error) {
                alert("⚠️ " + data.error);
            } else {
                selectedCards = [];
                updateUI(data);
            }
        } catch (err) {
            console.error("Play card error:", err);
        }
    }

    async function handlePass() {
        try {
            const res = await sessionFetch("/api/game/pass", { method: "POST" });
            const data = await res.json();
            if (data.error) {
                alert("⚠️ " + data.error);
            } else {
                selectedCards = [];
                updateUI(data);
            }
        } catch (err) {
            console.error("Pass error:", err);
        }
    }

    async function handleHint() {
        try {
            const res = await sessionFetch("/api/game/hint");
            const data = await res.json();
            if (!data.hint || data.hint.length === 0) {
                if (data.isPass) {
                    alert("💡 AI 建议：当前无可压制手牌，建议【过牌 (PASS)】");
                } else {
                    alert("💡 暂无可用出牌建议");
                }
                return;
            }

            // 自动将手牌中匹配的牌弹起选中
            selectedCards = [...data.hint];
            renderHand(currentState.humanHand);
            btnPlay.disabled = false;
        } catch (err) {
            console.error("Hint error:", err);
        }
    }

    // 核心 UI 更新与轮次调度
    function updateUI(state) {
        currentState = state;

        // 更新各玩家剩余牌数
        p0CardCount.textContent = state.cardCounts[0];
        p1CardCount.textContent = state.cardCounts[1];
        p2CardCount.textContent = state.cardCounts[2];

        // 高亮当前行动方
        player1Box.classList.toggle("active-turn", state.activePlayer === 1);
        player2Box.classList.toggle("active-turn", state.activePlayer === 2);

        // 更新状态标签
        p1Status.textContent = (state.activePlayer === 1) ? "思考中..." : "等待中";
        p1Status.classList.toggle("thinking", state.activePlayer === 1);
        p2Status.textContent = (state.activePlayer === 2) ? "思考中..." : "等待中";
        p2Status.classList.toggle("thinking", state.activePlayer === 2);

        // 更新每位玩家面前的出牌/过牌区域
        renderPlayerActions(state.playerActions, state.lastMove);

        // 更新人类手牌
        renderHand(state.humanHand);

        // 控制按钮状态
        const isHumanTurn = (state.activePlayer === 0) && !state.isGameOver;
        btnPlay.disabled = !isHumanTurn || selectedCards.length === 0;
        btnHint.disabled = !isHumanTurn;
        btnPass.disabled = !isHumanTurn || !state.canPass;

        if (state.isGameOver) {
            turnIndicator.textContent = "对局结束";
            showGameOverModal(state.winner);
        } else if (isHumanTurn) {
            turnIndicator.textContent = "👉 轮到你出牌！请选择手牌后出牌";
        } else {
            turnIndicator.textContent = `⏳ 玩家 P${state.activePlayer} (AI) 正在运用 PIMC 算力推演最佳走法...`;
        }

        // 渲染侧边栏数据
        renderCardCounter(state.playedStats);
        renderAiThoughts(state.aiThoughts);
        renderLogs(state.logs);

        // 如果轮到 AI 行动，自动发起步进请求
        if (!state.isGameOver && state.activePlayer !== 0 && !isAiStepInProgress) {
            triggerAiTurn();
        }
    }

    async function triggerAiTurn() {
        isAiStepInProgress = true;
        // 人性化稍作延时，便于玩家看清出牌过程 (450ms)
        setTimeout(async () => {
            try {
                const res = await sessionFetch("/api/game/ai-step", { method: "POST" });
                const data = await res.json();
                isAiStepInProgress = false;
                updateUI(data);
            } catch (err) {
                console.error("AI step error:", err);
                isAiStepInProgress = false;
            }
        }, 450);
    }

    function renderPlayerActions(actions, lastMove) {
        const areaMap = [p0ActionArea, p1ActionArea, p2ActionArea];

        // 清空所有区域
        areaMap.forEach(area => area.innerHTML = "");

        if (!lastMove || !lastMove.cards || lastMove.cards.length === 0) {
            trickStatusText.textContent = "桌面清空 / 自由出牌";
        } else {
            const leadName = (lastMove.playerId === 0) ? "你 (P0)" : `AI (P${lastMove.playerId})`;
            trickStatusText.textContent = `当前需压制: ${leadName} 的 [${lastMove.type}] (${lastMove.cards.length}张)`;
        }

        if (!actions) return;

        actions.forEach((act, idx) => {
            if (!act) return;
            const container = areaMap[idx];

            if (act.isPass) {
                const badge = document.createElement("div");
                badge.className = "pass-badge";
                badge.textContent = "不出 / PASS";
                container.appendChild(badge);
            } else if (act.cards && act.cards.length > 0) {
                act.cards.forEach(cardSymbol => {
                    const el = document.createElement("div");
                    el.className = "card small";
                    if (cardSymbol === "2" || cardSymbol === "A") {
                        el.classList.add("red");
                    }
                    el.innerHTML = `
                        <div class="card-corner">${cardSymbol}</div>
                        <div class="card-center">${getCardSuit(cardSymbol)}</div>
                        <div class="card-corner bottom">${cardSymbol}</div>
                    `;
                    container.appendChild(el);
                });

                if (act.type) {
                    const tag = document.createElement("span");
                    tag.className = "action-type-tag";
                    tag.textContent = act.type;
                    container.appendChild(tag);
                }
            }
        });
    }

    function renderHand(cards) {
        humanHand.innerHTML = "";
        const selectedMap = {};
        selectedCards.forEach(c => {
            selectedMap[c] = (selectedMap[c] || 0) + 1;
        });

        const activeSelectedMap = {};

        cards.forEach((cardSymbol, index) => {
            const el = document.createElement("div");
            el.className = "card";

            if (cardSymbol === "2" || cardSymbol === "A") {
                el.classList.add("red");
            }

            const needed = selectedMap[cardSymbol] || 0;
            const currentCount = activeSelectedMap[cardSymbol] || 0;
            if (currentCount < needed) {
                el.classList.add("selected");
                activeSelectedMap[cardSymbol] = currentCount + 1;
            }

            el.innerHTML = `
                <div class="card-corner">${cardSymbol}</div>
                <div class="card-center">${getCardSuit(cardSymbol)}</div>
                <div class="card-corner bottom">${cardSymbol}</div>
            `;

            el.addEventListener("click", () => {
                if (currentState.activePlayer !== 0 || currentState.isGameOver) return;
                toggleCardSelection(cardSymbol, el);
            });

            humanHand.appendChild(el);
        });
    }

    function toggleCardSelection(cardSymbol, el) {
        const idx = selectedCards.indexOf(cardSymbol);
        if (idx >= 0 && el.classList.contains("selected")) {
            selectedCards.splice(idx, 1);
            el.classList.remove("selected");
        } else {
            selectedCards.push(cardSymbol);
            el.classList.add("selected");
        }
        btnPlay.disabled = selectedCards.length === 0;
    }

    function renderCardCounter(stats) {
        cardCounter.innerHTML = "";
        if (!stats) return;

        const totalStock = {
            "3": 4, "4": 4, "5": 4, "6": 4, "7": 4, "8": 4, "9": 4, "10": 4,
            "J": 4, "Q": 4, "K": 4, "A": 3, "2": 1
        };

        for (const [rank, playedCount] of Object.entries(stats)) {
            const max = totalStock[rank] || 4;
            const remaining = Math.max(0, max - playedCount);

            const el = document.createElement("div");
            el.className = "counter-item";
            el.innerHTML = `
                <span class="counter-rank">${rank}</span>
                <span class="counter-value">${remaining}</span>
            `;
            cardCounter.appendChild(el);
        }
    }

    function renderAiThoughts(aiThoughts) {
        if (!aiThoughts || Object.keys(aiThoughts).length === 0) {
            aiThoughtContent.innerHTML = '<p class="placeholder-text">等待 AI 做出决策，将在此展示可能世界采样统计与各候选出牌的胜率评估...</p>';
            return;
        }

        let html = "";
        for (const [key, thought] of Object.entries(aiThoughts)) {
            const pName = key === "p1" ? "AI 玩家 1 (P1)" : "AI 玩家 2 (P2)";
            html += `<div class="thought-item">
                <div class="thought-header">
                    <strong>${pName}</strong>
                    <span>耗时: ${thought.timeMs} ms</span>
                </div>
                <div style="margin-bottom: 6px;">选定着法: <strong style="color: #f59e0b;">${thought.move}</strong></div>`;

            if (thought.evals && thought.evals.length > 0) {
                thought.evals.forEach(ev => {
                    const widthPercent = Math.min(100, Math.max(5, ev.winRate));
                    html += `
                        <div style="font-size: 11px; margin-top: 4px; display: flex; justify-content: space-between;">
                            <span>${ev.cards}</span>
                            <span>胜率: ${ev.winRate}% (访问: ${ev.visits})</span>
                        </div>
                        <div class="progress-bar-bg">
                            <div class="progress-bar-fill" style="width: ${widthPercent}%;"></div>
                        </div>
                    `;
                });
            }
            html += `</div>`;
        }
        aiThoughtContent.innerHTML = html;
    }

    function renderLogs(logs) {
        if (!logs) return;
        eventLogs.innerHTML = "";
        logs.forEach(log => {
            const el = document.createElement("div");
            el.className = "log-entry";
            el.textContent = log;
            eventLogs.appendChild(el);
        });
        eventLogs.scrollTop = eventLogs.scrollHeight;
    }

    function showGameOverModal(winnerId) {
        modalTitle.textContent = (winnerId === 0) ? "🎉 恭喜你获胜！" : `🏆 玩家 P${winnerId} (AI) 获胜！`;
        modalMessage.textContent = (winnerId === 0)
            ? "你凭借高超的牌技击败了两个 PIMC 强化学习 AI 机器人！"
            : `AI 玩家 P${winnerId} 率先出完手牌赢得了比赛。再接再厉！`;
        modalOverlay.classList.remove("hidden");
    }

    function getCardSuit(rank) {
        const suits = ["♠️", "♥️", "♣️", "♦️"];
        if (rank === "2" || rank === "A") return "♥️";
        return "♠️";
    }
});
