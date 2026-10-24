package com.paodekuai.ai;

import com.paodekuai.game.PublicView;

/** AI 决策接口:给定观察视角,返回决策 */
public interface AiPlayer {

    AiDecision decide(PublicView publicView);
}
