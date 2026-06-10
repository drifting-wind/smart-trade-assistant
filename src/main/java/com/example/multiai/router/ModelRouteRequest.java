package com.example.multiai.router;

import com.example.multiai.enums.ModelProvider;
import com.example.multiai.enums.ScenarioType;

/** 路由请求参数 —— 传入 ModelRouter.route() 的决策依据 */
public record ModelRouteRequest(
        ScenarioType scenario,
        String content,
        ModelProvider preferredModel,
        boolean preciseMode
) {
}
