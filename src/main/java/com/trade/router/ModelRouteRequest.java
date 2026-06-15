package com.trade.router;

import com.trade.enums.ModelProvider;
import com.trade.enums.ScenarioType;

/**
 * 路由请求参数 —— 传入 {@link ModelRouter#route(ModelRouteRequest)} 的决策依据。
 *
 * 用于智能路由组件根据场景、内容、用户偏好等因素，选择最合适的大模型。
 *
 * @param scenario       场景类型（如 QA、FLOW），决定路由策略
 * @param content        用户输入内容（问题或文本），用于分析复杂度
 * @param preferredModel 用户指定的优先模型（可为 null，表示由系统自动选择）
 * @param preciseMode    是否开启精确模式（true 优先选择推理能力强的模型）
 */
public record ModelRouteRequest(
        /**
         * 场景类型 —— 决定路由策略。
         * <ul>
         *   <li>{@link ScenarioType#QA}：问答场景，优先选择推理能力强的模型</li>
         *   <li>{@link ScenarioType#FLOW}：流程/规划场景，优先选择长上下文模型</li>
         * </ul>
         */
        ScenarioType scenario,

        /**
         * 用户输入内容 —— 用于分析文本复杂度、长度等因素。
         * 例如：长文本可能优先选择支持长上下文的模型。
         */
        String content,

        /**
         * 用户指定的优先模型 —— 可为 null。
         * <ul>
         *   <li>非 null：优先使用该模型（如 DEEPSEEK、ALIBABA_BAILIAN）</li>
         *   <li>null：由系统根据场景和内容自动选择最合适的模型</li>
         * </ul>
         */
        ModelProvider preferredModel,

        /**
         * 是否开启精确模式。
         * <ul>
         *   <li>true：优先选择推理能力强、输出精确的模型（如 DeepSeek）</li>
         *   <li>false：由系统根据场景自动选择</li>
         * </ul>
         */
        boolean preciseMode
) {
}
