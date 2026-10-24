// 跑得快网页端游戏逻辑与交互控制 (强化学习策略网络出牌与多会话支持)
document.addEventListener("DOMContentLoaded", () => {
    let currentState = null;
    let selectedCards = [];
    let isAiStepInProgress = false;
    let gameOverRevealTimer = null;
    let gameOverModalShown = false;
    const GAME_OVER_REVEAL_MS = 3500;

    // 多用户独立 Session ID
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

    const tableCardsLabel = document.getElementById("table-cards-label");
    const tableCardsContainer = document.getElementById("table-cards-container");
    const trickStatusText = document.getElementById("trick-status-text");
    const turnIndicator = document.getElementById("turn-indicator");

    // 控制区
    const playingControls = document.getElementById("playing-controls");
    const humanHand = document.getElementById("human-hand");
    const btnPlay = document.getElementById("btn-play");
    const btnHint = document.getElementById("btn-hint");
    const btnPass = document.getElementById("btn-pass");

    const btnNewGame = document.getElementById("btn-new-game");
    const btnRules = document.getElementById("btn-rules");

    const p0Avatar = document.getElementById("p0-avatar");
    const p1Avatar = document.getElementById("p1-avatar");
    const p2Avatar = document.getElementById("p2-avatar");
    const p1Mood = document.getElementById("p1-mood");
    const p2Mood = document.getElementById("p2-mood");
    const p1Speech = document.getElementById("p1-speech");
    const p2Speech = document.getElementById("p2-speech");

    const p1ThoughtPopover = document.getElementById("p1-thought-popover");
    const p1PopoverTime = document.getElementById("p1-popover-time");
    const p1PopoverBody = document.getElementById("p1-popover-body");

    const p2ThoughtPopover = document.getElementById("p2-thought-popover");
    const p2PopoverTime = document.getElementById("p2-popover-time");
    const p2PopoverBody = document.getElementById("p2-popover-body");

    const toastContainer = document.getElementById("toast-container");
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
            if (gameOverRevealTimer) {
                clearTimeout(gameOverRevealTimer);
                gameOverRevealTimer = null;
            }
            gameOverModalShown = false;
            const res = await sessionFetch("/api/game/new", { method: "POST" });
            const data = await res.json();
            updateUI(data);
        } catch (err) {
            console.error("Failed to start new game:", err);
            showToast("⚠️ 网络请求异常", "warning");
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
                showToast("⚠️ " + data.error, "warning");
            } else {
                selectedCards = [];
                updateUI(data);
            }
        } catch (err) {
            console.error("Play card error:", err);
            showToast("⚠️ 网络请求异常", "warning");
        }
    }

    async function handlePass() {
        try {
            const res = await sessionFetch("/api/game/pass", { method: "POST" });
            const data = await res.json();
            if (data.error) {
                showToast("⚠️ " + data.error, "warning");
            } else {
                selectedCards = [];
                updateUI(data);
            }
        } catch (err) {
            console.error("Pass error:", err);
            showToast("⚠️ 网络请求异常", "warning");
        }
    }

    // 轻量级 Tips 提示 (替代浏览器 alert 弹窗)
    function showToast(message, type = "info") {
        if (!toastContainer) return;
        const toast = document.createElement("div");
        toast.className = `toast-item ${type}`;
        toast.innerHTML = message;
        toastContainer.appendChild(toast);
        setTimeout(() => {
            if (toast.parentNode) {
                toast.parentNode.removeChild(toast);
            }
        }, 2600);
    }

    async function handleHint() {
        try {
            const res = await sessionFetch("/api/game/hint");
            const data = await res.json();
            if (!data.hint || data.hint.length === 0) {
                if (data.isPass) {
                    showToast("💡 AI 建议：当前要不起，建议【过牌 (PASS)】", "warning");
                } else {
                    showToast("💡 暂无可用出牌建议", "info");
                }
                return;
            }

            selectedCards = [...data.hint];
            renderHand(currentState.humanHand);
            btnPlay.disabled = false;
            showToast(`💡 AI 走法推荐: <strong>${data.hint.join(", ")}</strong>`, "info");
        } catch (err) {
            console.error("Hint error:", err);
            showToast("⚠️ 获取提示失败", "warning");
        }
    }

    // 核心 UI 更新与轮次调度
    function updateUI(state) {
        currentState = state;

        // 更新各玩家剩余牌数
        p0CardCount.textContent = state.cardCounts[0];
        p1CardCount.textContent = state.cardCounts[1];
        p2CardCount.textContent = state.cardCounts[2];

        updateAiExpressions(state);

        // 渲染人类手牌
        renderHand(state.humanHand);

        handlePlayingStageUI(state);

        // 渲染侧边栏数据
        renderCardCounter(state.playedStats);
        renderAiThoughts(state.aiThoughts);
        renderLogs(state.logs);
    }

    function handlePlayingStageUI(state) {
        const isHumanTurn = (state.activePlayer === 0) && !state.isGameOver;
        btnPlay.disabled = !isHumanTurn || selectedCards.length === 0;
        btnHint.disabled = !isHumanTurn;
        btnPass.disabled = !isHumanTurn || !state.canPass;

        player1Box.classList.toggle("active-turn", state.activePlayer === 1);
        player2Box.classList.toggle("active-turn", state.activePlayer === 2);

        p1Status.textContent = (state.activePlayer === 1) ? "思考中..." : "等待中";
        p1Status.classList.toggle("thinking", state.activePlayer === 1);
        p2Status.textContent = (state.activePlayer === 2) ? "思考中..." : "等待中";
        p2Status.classList.toggle("thinking", state.activePlayer === 2);

        // 渲染出牌展示与桌面中央状态
        renderPlayerActions(state.playerActions, state.lastMove);

        if (state.isGameOver) {
            turnIndicator.textContent = "对局结束 — AI 摊牌中，请查看剩余手牌...";
            p1Status.textContent = "摊牌";
            p1Status.classList.remove("thinking");
            p2Status.textContent = "摊牌";
            p2Status.classList.remove("thinking");
            revealAiHands(state);
            scheduleGameOverModal(state);
        } else if (isHumanTurn) {
            turnIndicator.textContent = "👉 轮到你出牌！请选择手牌打出" +
                (state.canPass ? "或选择不出" : "（能管必管，不可过牌）");
        } else {
            turnIndicator.textContent = `⏳ 玩家 P${state.activePlayer} (AI) 正在推演最佳出牌...`;
            if (!isAiStepInProgress) {
                triggerAiTurn();
            }
        }
    }

    function revealAiHands(state) {
        const hands = state.revealedHands;
        if (!hands) return;

        const areaMap = { 1: p1ActionArea, 2: p2ActionArea };
        const winnerId = state.winner;
        [1, 2].forEach(id => {
            const area = areaMap[id];
            if (!area) return;

            const cards = hands[id] || [];
            // 出完手牌获胜的一方：保留刚打出的最后一手，不要盖成「已出完」
            if (cards.length === 0 || id === winnerId) {
                return;
            }

            area.innerHTML = "";
            area.classList.add("revealed-hand-area");

            const label = document.createElement("span");
            label.className = "reveal-hand-label";
            label.textContent = "摊牌";
            area.appendChild(label);

            cards.forEach(cardSymbol => {
                area.appendChild(createCardElement(cardSymbol, true));
            });
        });
    }

    function scheduleGameOverModal(state) {
        if (gameOverModalShown || gameOverRevealTimer) return;
        gameOverRevealTimer = setTimeout(() => {
            gameOverRevealTimer = null;
            gameOverModalShown = true;
            showGameOverModal(state);
        }, GAME_OVER_REVEAL_MS);
    }

    let speechTimeouts = {};
    let lastPlayerActionSnapshots = ["", "", ""];

    function triggerSpeechBubble(playerId, text, level) {
        const speechEl = (playerId === 1) ? p1Speech : p2Speech;
        if (!speechEl) return;
        speechEl.textContent = text;
        speechEl.className = `mood-speech-bubble ${level}`;
        speechEl.classList.remove("hidden");

        if (speechTimeouts[playerId]) {
            clearTimeout(speechTimeouts[playerId]);
        }
        speechTimeouts[playerId] = setTimeout(() => {
            speechEl.classList.add("hidden");
        }, 3000);
    }

    function updateAiExpressions(state) {
        if (!state) return;
        const aiList = [
            { id: 1, avatarEl: p1Avatar, moodEl: p1Mood, thoughtKey: "p1" },
            { id: 2, avatarEl: p2Avatar, moodEl: p2Mood, thoughtKey: "p2" }
        ];

        aiList.forEach(({ id, avatarEl, moodEl, thoughtKey }) => {
            if (!avatarEl || !moodEl) return;

            const thought = (state.aiThoughts && state.aiThoughts[thoughtKey]) ? state.aiThoughts[thoughtKey] : null;
            if (!thought) {
                moodEl.classList.add("hidden");
                avatarEl.className = "player-avatar";
                return;
            }

            // 策略概率: 神经网络选中这手牌的置信度, 不是搜索胜率
            let probability = null;
            if (thought.type === "play" && thought.evals && thought.evals.length > 0) {
                probability = thought.evals[0].probability;
            }

            if (probability === null || probability === undefined) {
                moodEl.classList.add("hidden");
                avatarEl.className = "player-avatar";
                return;
            }

            let moodBadge = "";
            let level = "normal";
            let speechLine = "";

            if (probability >= 70.0) {
                level = "high";
                moodBadge = "😎";
                speechLine = `🔥 就它了！(置信 ${probability}%)`;
                avatarEl.className = "player-avatar avatar-cheer";
            } else if (probability <= 35.0) {
                level = "low";
                moodBadge = "😰";
                speechLine = `😵 没太好的选择 (置信 ${probability}%)`;
                avatarEl.className = "player-avatar avatar-panic";
            } else {
                level = "normal";
                moodBadge = "🧐";
                speechLine = `🤔 还要权衡 (${probability}%)`;
                avatarEl.className = "player-avatar";
            }

            moodEl.textContent = moodBadge;
            moodEl.className = `avatar-mood-badge ${level}`;
            moodEl.classList.remove("hidden");

            // 检测该玩家是否有最新动作变化 (出牌或过牌)，若有则触发表情冒泡动画
            const currentPlayAct = (state.playerActions && state.playerActions[id])
                ? JSON.stringify(state.playerActions[id]) : "";

            const isNewPlay = (currentPlayAct !== "" && currentPlayAct !== lastPlayerActionSnapshots[id]);

            if (isNewPlay) {
                triggerSpeechBubble(id, speechLine, level);
            }

            lastPlayerActionSnapshots[id] = currentPlayAct;
        });
    }

    async function triggerAiTurn() {
        isAiStepInProgress = true;
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
        }, 500);
    }

    function renderPlayerActions(actions, lastMove) {
        const areaMap = [p0ActionArea, p1ActionArea, p2ActionArea];
        areaMap.forEach(area => {
            area.innerHTML = "";
            area.classList.remove("revealed-hand-area");
        });

        // 桌面中央: 当前要压制的牌
        tableCardsContainer.innerHTML = "";
        if (!lastMove || !lastMove.cards || lastMove.cards.length === 0) {
            tableCardsLabel.textContent = "桌面牌 (自由出牌)";
            trickStatusText.textContent = "自由出牌";
        } else {
            tableCardsLabel.textContent = "桌面牌 (需压制)";
            trickStatusText.textContent = `需压制: ${lastMove.type} (${lastMove.cards.length}张)`;
            lastMove.cards.forEach(cardSymbol => {
                tableCardsContainer.appendChild(createCardElement(cardSymbol, true));
            });
        }

        if (!actions) return;

        actions.forEach((act, idx) => {
            if (!act) return;
            const container = areaMap[idx];

            // 按策略概率决定是否加特效
            let probAnimClass = "";
            if (currentState && currentState.aiThoughts && idx > 0) {
                const thoughtKey = (idx === 1) ? "p1" : "p2";
                const thought = currentState.aiThoughts[thoughtKey];
                if (thought && thought.type === "play" && thought.evals && thought.evals.length > 0) {
                    const topProbability = thought.evals[0].probability;
                    if (topProbability >= 75.0) {
                        probAnimClass = "anim-win-high";
                    } else if (topProbability <= 30.0) {
                        probAnimClass = "anim-win-low";
                    }
                }
            }

            if (act.isPass) {
                const badge = document.createElement("div");
                badge.className = `pass-badge ${probAnimClass}`;
                badge.textContent = "不出 / PASS";
                container.appendChild(badge);
            } else if (act.cards && act.cards.length > 0) {
                act.cards.forEach(cardSymbol => {
                    const el = createCardElement(cardSymbol, true);
                    if (probAnimClass) {
                        el.classList.add(probAnimClass);
                    }
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

        cards.forEach((cardSymbol, idx) => {
            const el = createCardElement(cardSymbol, false);
            el.style.zIndex = idx + 1;

            const needed = selectedMap[cardSymbol] || 0;
            const currentCount = activeSelectedMap[cardSymbol] || 0;
            if (currentCount < needed) {
                el.classList.add("selected");
                activeSelectedMap[cardSymbol] = currentCount + 1;
            }

            el.addEventListener("click", () => {
                if (currentState.activePlayer !== 0 || currentState.isGameOver) return;
                toggleCardSelection(cardSymbol, el);
            });

            humanHand.appendChild(el);
        });
    }

    function createCardElement(cardSymbol, isSmall = false) {
        const el = document.createElement("div");
        el.className = "card" + (isSmall ? " small" : "");

        const displayRank = cardSymbol;
        let suit = "♠️";

        if (cardSymbol === "2" || cardSymbol === "A") {
            el.classList.add("red");
            suit = "♥️";
        }

        el.innerHTML = `
            <div class="card-corner">${displayRank}</div>
            <div class="card-center">${suit}</div>
            <div class="card-corner bottom">${displayRank}</div>
        `;
        return el;
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
            if (p1ThoughtPopover) p1ThoughtPopover.classList.add("hidden");
            if (p2ThoughtPopover) p2ThoughtPopover.classList.add("hidden");
            return;
        }

        // 分别为 P1 和 P2 渲染桌面外侧的专属思考面板
        [ { popover: p1ThoughtPopover, timeEl: p1PopoverTime, bodyEl: p1PopoverBody, key: "p1" },
          { popover: p2ThoughtPopover, timeEl: p2PopoverTime, bodyEl: p2PopoverBody, key: "p2" }
        ].forEach(({ popover, timeEl, bodyEl, key }) => {
            if (!popover || !bodyEl) return;
            const thought = aiThoughts[key];
            if (!thought) {
                popover.classList.add("hidden");
                return;
            }

            popover.classList.remove("hidden");
            if (timeEl) timeEl.textContent = `${thought.timeMs || 0} ms`;

            let html = "";

            let probIcon = "";
            if (thought.evals && thought.evals.length > 0) {
                const topProbability = thought.evals[0].probability;
                if (topProbability >= 75.0) probIcon = "🔥";
                else if (topProbability <= 30.0) probIcon = "💧";
            }

            html = `
                <div style="font-weight: 600; color: #f59e0b; margin-bottom: 4px;">
                    ${probIcon} 决策出牌: <strong>${thought.move}</strong>
                </div>
                <div style="font-size: 11px; color: #94a3b8; margin-bottom: 4px;">神经网络策略概率</div>
            `;

            if (thought.evals && thought.evals.length > 0) {
                thought.evals.forEach(ev => {
                    const widthPercent = Math.min(100, Math.max(5, ev.probability));
                    html += `
                        <div style="font-size: 11px; margin-top: 4px; display: flex; justify-content: space-between;">
                            <span>${ev.cards}</span>
                            <span>${ev.probability}%</span>
                        </div>
                        <div class="progress-bar-bg">
                            <div class="progress-bar-fill" style="width: ${widthPercent}%;"></div>
                        </div>
                    `;
                });
            }

            bodyEl.innerHTML = html;
        });
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

    function showGameOverModal(state) {
        const isHumanWinner = state.isHumanWinner;
        const winnerId = state.winner;

        if (isHumanWinner) {
            modalTitle.textContent = "🎉 恭喜你获得胜利！";
            modalMessage.textContent = "你率先出完全部手牌，击败了两个策略网络 AI！";
        } else {
            modalTitle.textContent = "💔 遗憾惜败";
            modalMessage.textContent = `AI 玩家 P${winnerId} 率先出完全部手牌赢得了比赛。再接再厉！`;
        }
        modalOverlay.classList.remove("hidden");
    }
});
