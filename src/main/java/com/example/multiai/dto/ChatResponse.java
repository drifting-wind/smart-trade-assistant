package com.example.multiai.dto;

import com.example.multiai.enums.ModelProvider;

import java.time.Instant;

/**
 * 智能问答响应 DTO —— /api/v1/chat/completions 同步接口返回的结构。
 *
 * 由 ChatOrchestrationService.toChatResponse() 组装，包含：
 * 本次请求标识、AI 回答、路由决策（用了哪个模型及原因）、Token 消耗统计。
 */
public record ChatResponse(
        /**
         * 请求 ID —— 本次问答请求的唯一标识（UUID）。
         * 由 AiRequestMapper 在构造 AiPromptRequest 时生成，用于链路追踪和日志关联。
         */
        String id,

        /**
         * 会话 ID —— 所属对话上下文的标识。
         * 同一个 conversationId 的多轮对话共享历史记忆，
         * 前端可在后续请求中携带此 ID 以延续上下文。
         */
        String conversationId,

        /**
         * 实际使用的 AI 模型 —— DEEPSEEK 或 ALIBABA_BAILIAN。
         * 可能与用户请求中的 preferredModel 不同（如果首选模型不可用，路由器会自动降级）。
         */
        ModelProvider model,

        /**
         * AI 生成的回答内容 —— 模型返回的完整文本答案。
         * 如果是多轮对话，该字段仅包含本轮新增的回答，
         * 完整历史需客户端通过 conversationId 维护或从 /stream 累积。
         */
        String answer,

        /**
         * 路由决策信息 —— 记录本次请求"为什么选了这个模型"。
         * 包含场景类型、选中模型、降级模型、各模型打分、选择原因，
         * 便于前端展示和运维调试，帮助理解路由器的决策依据。
         */
        RouteDecisionDto route,

        /**
         * Token 用量统计 —— 本次 AI 调用的消耗。
         * promptTokens：发送的输入 token 数（系统提示 + 历史 + 用户问题）
         * completionTokens：模型生成的输出 token 数
         * totalTokens：两者之和，用于计费和用量分析
         * 注意：如果请求命中了缓存（responseCache），不会调用 AI 模型，该字段为 null。
         */
        TokenUsageDto usage,

        /**
         * 响应创建时间 —— 服务器返回该响应的 UTC 时间戳。
         * 用于前端排序、日志审计和问题排查。
         */
        Instant createdAt
) {
}
