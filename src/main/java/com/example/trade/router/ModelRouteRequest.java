package com.example.trade.router;

import com.example.trade.enums.ModelProvider;
import com.example.trade.enums.ScenarioType;

/** 路由请求参数 —— 传入 ModelRouter.route() 的决策依据 */
public record ModelRouteRequest(
        ScenarioType scenario,
        String content,
        ModelProvider preferredModel,
        boolean preciseMode
) {
}
