package com.example.multiai.dto;

import com.example.multiai.enums.ModelProvider;
import com.example.multiai.enums.ScenarioType;

import java.util.Map;

/**
 * 路由决策 DTO —— 返回给客户端的路由信息，多个响应 DTO 共用。
 * 由 RouteMapper 将内部 RouteDecision 转换而来。
 */
public record RouteDecisionDto(
        /**
         * 场景类型 —— QA（智能问答）或 FLOW（流程规划）。
         * 不同场景影响路由器的打分策略。
         */
        ScenarioType scenario,

        /**
         * 选中的模型 —— 本次请求实际调用的 AI 模型。
         * 可能是用户指定的 preferredModel，也可能是路由器打分最高的。
         */
        ModelProvider selectedModel,

        /**
         * 降级模型 —— 如果 selectedModel 调用失败，系统会自动切换到此模型。
         * 为 null 表示没有可用的 fallback。
         */
        ModelProvider fallbackModel,

        /**
         * 选中模型的打分得分 —— 路由器根据场景匹配、关键词、能力标签计算的综合得分。
         * 得分越高表示该模型越适合当前请求。
         */
        double score,

        /**
         * 选择原因 —— 人类可读的路由决策说明，方便前端展示和调试。
         * 例如"问答、代码、推理或精确回答场景优先选择 DeepSeek"。
         */
        String reason,

        /**
         * 候选模型打分 —— 所有可用模型及其得分的完整映射。
         * 用于调试和监控，可以分析为什么某个模型没被选中。
         */
        Map<ModelProvider, Double> candidateScores
) {
}
