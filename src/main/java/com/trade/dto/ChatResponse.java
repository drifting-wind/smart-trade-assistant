package com.trade.dto;

import com.trade.enums.ModelProvider;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * 智能问答响应 DTO —— /api/v1/chat/completions 同步接口返回的结构。
 *
 * 由 ChatOrchestrationService.toChatResponse() 组装，包含：
 * 本次请求标识、AI 回答、路由决策（用了哪个模型及原因）、Token 消耗统计。
 *
 * 新增：引用信息列表（citations），支持溯源定位，确保回答"有据可查"。
 */
@Schema(description = "智能问答响应")
public record ChatResponse(
        /**
         * 请求 ID —— 本次问答请求的唯一标识（UUID）。
         * 由 AiRequestMapper 在构造 AiPromptRequest 时生成，用于链路追踪和日志关联。
         */
        @Schema(description = "请求 ID（UUID）", example = "req-123")
        String id,

        /**
         * 会话 ID —— 所属对话上下文的标识。
         * 同一个 conversationId 的多轮对话共享历史记忆，
         * 前端可在后续请求中携带此 ID 以延续上下文。
         */
        @Schema(description = "会话 ID", example = "conv-456")
        String conversationId,

        /**
         * 实际使用的 AI 模型 —— DEEPSEEK 或 ALIBABA_BAILIAN。
         * 可能与用户请求中的 preferredModel 不同（如果首选模型不可用，路由器会自动降级）。
         */
        @Schema(description = "实际使用的 AI 模型", example = "DEEPSEEK")
        ModelProvider model,

        /**
         * AI 生成的回答内容 —— 模型返回的完整文本答案。
         * 如果是多轮对话，该字段仅包含本轮新增的回答，
         * 完整历史需客户端通过 conversationId 维护或从 /stream 累积。
         *
         * 注意：回答中应包含引用标记（如 [1]、[2]），对应 citations 列表中的引用。
         */
        @Schema(description = "AI 生成的回答内容", example = "LED面板灯的功率是50W [1]")
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
         * 引用信息列表 —— 回答中引用的所有来源，用于溯源定位。
         * 前端可展示引用列表，点击跳转到原文，确保回答"有据可查"。
         *
         * 注意：当 hasRelevantInfo = false 时（即回答"未找到相关信息"），
         * 前端应隐藏此字段，不展示引用信息。
         */
        List<Citation> citations,

        /**
         * 响应创建时间 —— 服务器返回该响应的 UTC 时间戳。
         * 用于前端排序、日志审计和问题排查。
         */
        Instant createdAt,

        /**
         * 是否有相关信息 —— 标识后端是否检索到了与问题相关的文档。
         * - true：后端检索到了相关信息，并基于此生成回答，前端应展示引用来源
         * - false：后端未检索到相关信息（回答"未找到相关信息"），前端应隐藏引用来源
         *
         * 前端展示逻辑：
         * - hasRelevantInfo = true → 展示 citations 列表（去重后）
         * - hasRelevantInfo = false → 隐藏 citations 列表
         */
        @Schema(description = "是否有相关信息", example = "true")
        boolean hasRelevantInfo
) {
    /**
     * 创建"未找到答案"的响应 —— 当检索结果质量低于阈值时，直接返回此响应，不调用 LLM。
     *
     * @param question 用户问题
     * @return ChatResponse 实例
     */
    public static ChatResponse noAnswer(String question) {
        return new ChatResponse(
                java.util.UUID.randomUUID().toString(),
                null,
                null,
                "参考资料中未找到相关信息，请尝试换个问题或上传更多文档。",
                null,
                null,
                java.util.Collections.emptyList(),
                Instant.now(),
                false  // ← 未找到相关信息，前端应隐藏引用
        );
    }
}
