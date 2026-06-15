package com.trade.dto;

/**
 * Token 用量统计 DTO —— 记录单次 AI 调用的 Token 消耗。
 * 由 LangChain4jChatModel 从模型响应的 usage 字段提取。
 */
public record TokenUsageDto(
        /**
         * 输入 Token 数 —— 发送给模型的 Token 总量（System Prompt + 历史消息 + 用户问题）。
         * 影响计费，输入越长费用越高。
         */
        Integer promptTokens,

        /**
         * 输出 Token 数 —— 模型生成的 Token 总量（回答内容）。
         * 通常比输入 Token 少，但推理类任务可能生成较多输出。
         */
        Integer completionTokens,

        /**
         * 总 Token 数 —— promptTokens + completionTokens。
         * 用于账单统计、用量分析和告警监控。
         */
        Integer totalTokens
) {
    /** 创建空统计 —— 缓存命中或未调用模型时使用。 */
    public static TokenUsageDto empty() {
        return new TokenUsageDto(null, null, null);
    }
}
