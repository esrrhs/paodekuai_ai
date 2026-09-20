package com.paodekuai;

import com.paodekuai.ai.PimcAiPlayer;
import com.paodekuai.game.GameState;
import com.paodekuai.game.PublicView;
import com.paodekuai.model.Hand;
import com.paodekuai.model.Move;
import com.paodekuai.rules.Deck;
import com.paodekuai.web.GameHttpServer;

import java.util.List;
import java.util.Random;

/**
 * 跑得快现代 AI 运行入口
 * 默认启动本地 Web 网页对战客户端 (http://localhost:8080)
 * 也可通过参数 --cli 运行纯命令行测试演示
 */
public class Main {

    public static void main(String[] args) {
        boolean runCli = false;
        int port = 8080;

        for (String arg : args) {
            if ("--cli".equalsIgnoreCase(arg)) {
                runCli = true;
            } else if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring("--port=".length()));
            }
        }

        if (runCli) {
            runCliSimulation();
        } else {
            startWebServer(port);
        }
    }

    private static void startWebServer(int port) {
        try {
            GameHttpServer server = new GameHttpServer(port);
            server.start();

            System.out.println("👉 服务已就绪。如需在命令行查看纯 AI 对局，可使用参数: mvn exec:java -Dexec.args=\"--cli\"");
            System.out.println("按 Ctrl+C 可停止网页端服务。");

            // 保持主线程存活
            Thread.currentThread().join();
        } catch (Exception e) {
            System.err.println("启动 Web 服务失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void runCliSimulation() {
        System.out.println("===============================================================");
        System.out.println("   跑得快 AI 引擎 - PIMC (Perfect Information Monte Carlo)");
        System.out.println("   采用 Java 17 + PIMC 确定化采样 + Max^N MCTS 搜索");
        System.out.println("===============================================================\n");

        boolean useFixedScenario = true;
        List<Hand> hands;

        if (useFixedScenario) {
            String handA = "3,4,4,5,5,7,9,9,10,10,J,J,Q,Q,K,A";
            String handB = "4,4,6,6,6,7,8,8,9,10,10,J,Q,Q,K,K";
            String handC = "3,3,3,5,5,6,7,7,8,8,9,J,K,A,A,2";

            hands = List.of(
                    Deck.fromCardString(handA),
                    Deck.fromCardString(handB),
                    Deck.fromCardString(handC)
            );
            System.out.println("[模式] 加载测试用例手牌 (48张经典残局)");
        } else {
            Random random = new Random();
            hands = Deck.deal(random);
            System.out.println("[模式] 随机洗牌发牌 (48张标准牌库)");
        }

        for (int i = 0; i < 3; i++) {
            System.out.printf("  玩家 P%d 初始手牌 (%2d张): %s\n", i, hands.get(i).getTotalCards(), hands.get(i).toCardString());
        }
        System.out.println();

        PimcAiPlayer[] aiPlayers = new PimcAiPlayer[]{
                new PimcAiPlayer(25, 150),
                new PimcAiPlayer(25, 150),
                new PimcAiPlayer(25, 150)
        };

        GameState state = new GameState(hands, 0, true);
        int round = 1;
        long totalStartTime = System.currentTimeMillis();

        while (!state.isGameOver()) {
            int currentId = state.getActivePlayerIndex();
            Hand currentHand = state.getPlayer(currentId).getHand();
            PublicView view = state.getPublicView(currentId);

            System.out.printf("----------- [第 %2d 步] 轮到玩家 P%d 出牌 (手牌剩 %d 张) -----------\n",
                    round++, currentId, currentHand.getTotalCards());
            System.out.printf("当前手牌: %s\n", currentHand.toCardString());
            if (state.getLastMove() != null && !state.getLastMove().isPass()) {
                System.out.printf("上家出牌: %s (出牌者: P%d)\n", state.getLastMove().toCardString(), state.getLastMovePlayerId());
            } else {
                System.out.println("桌面状态: [自由主动出牌]");
            }

            PimcAiPlayer.DecisionResult result = aiPlayers[currentId].decide(view);
            Move chosen = result.getSelectedMove();

            List<PimcAiPlayer.MoveEvaluation> evals = result.getEvaluations();
            if (evals.size() > 1) {
                System.out.printf("  AI 评估候选动作数: %d，耗时: %d ms\n", evals.size(), result.getDurationMillis());
                int showTop = Math.min(5, evals.size());
                for (int i = 0; i < showTop; i++) {
                    System.out.println("    " + (i == 0 ? "★ " : "  ") + evals.get(i));
                }
            }

            System.out.printf(">>> 玩家 P%d 决定出牌: %s\n\n", currentId, chosen.toCardString());
            state.applyMove(chosen);
        }

        long totalDuration = System.currentTimeMillis() - totalStartTime;
        System.out.println("===============================================================");
        System.out.printf("                    对局结束！胜利者: 玩家 P%d\n", state.getWinnerId());
        System.out.printf("                    总步数: %d 步，总耗时: %d ms\n", round - 1, totalDuration);
        System.out.println("===============================================================");
        for (int i = 0; i < 3; i++) {
            System.out.printf("  玩家 P%d 最终剩余牌数: %2d 张  %s\n",
                    i, state.getPlayer(i).getCardCount(),
                    i == state.getWinnerId() ? "【WINNER 👑】" : "");
        }
        System.out.println("===============================================================");
    }
}
