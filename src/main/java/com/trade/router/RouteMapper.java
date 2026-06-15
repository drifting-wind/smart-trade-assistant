package com.trade.router;

import com.trade.dto.RouteDecisionDto;
import org.springframework.stereotype.Component;

/** 路由决策内部对象 → DTO 转换器 —— 将 RouteDecision 转为返回给客户端的 RouteDecisionDto */
@Component
public class RouteMapper {

    public RouteDecisionDto toDto(RouteDecision decision) {
        return new RouteDecisionDto(
                decision.scenario(),
                decision.selectedModel(),
                decision.fallbackModel(),
                decision.score(),
                decision.reason(),
                decision.candidateScores()
        );
    }
}
