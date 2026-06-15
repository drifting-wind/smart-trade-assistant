package com.trade.router;

import com.trade.enums.ModelProvider;
import com.trade.enums.ScenarioType;

import java.util.Map;

/**
 * 路由决策结果 —— ModelRouter.route() 的返回值，记录本次请求选择了哪个模型、为什么、各模型打分如何。
 *
 * 两个用途：
 * 1. 服务层用它做 fallback 降级（调用失败时切换到 fallbackModel）
 * 2. 通过 RouteMapper 转为 RouteDecisionDto 返回给客户端，供调试和监控
 */
public record RouteDecision(
        /**
         * 场景类型 —— QA（智能问答）或 FLOW（流程规划）。
         * 不同场景影响路由器的打分策略。
         */
        ScenarioType scenario,

        /**
         * 选中的模型 —— 本次请求实际要调用的 AI 模型。
         * 可能是用户指定的 preferredModel，也可能是路由器打分最高的。
         */
        ModelProvider selectedModel,

        /**
         * 降级模型 —— 如果 selectedModel 调用失败，系统自动切换到此模型。
         * 为 null 表示没有可用的 fallback，此时直接抛异常不再重试。
         */
        ModelProvider fallbackModel,

        /**
         * 选中模型的打分得分 —— 路由器根据场景匹配、关键词、能力标签计算的综合得分。
         * 例如 DeepSeek 在推理场景可能得 74 分，阿里百炼得 50 分。
         */
        double score,

        /**
         * 选择原因 —— 人类可读的路由决策说明。
         * 例如"请求指定了首选模型，且该模型当前可用"或"问答、代码、推理场景优先选择 DeepSeek"。
         */
        String reason,

        /**
         * 候选模型完整打分 —— 所有可用模型及其得分的映射。
         * 用于调试和监控，可以分析为什么某个模型没被选中。
         * 例如 {DEEPSEEK=74.0, ALIBABA_BAILIAN=50.0}。
         */
        Map<ModelProvider, Double> candidateScores
) {
}
