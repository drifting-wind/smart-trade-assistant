package com.trade.dto;

import com.trade.enums.ModelProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/** 智能问答请求 DTO —— 用户通过 /api/v1/chat/completions 或 /stream 提交的问题 */
public record ChatRequest(
        /**
         * 会话 ID —— 标识一轮完整的对话上下文。
         * 同一个 conversationId 下的多次请求，系统会自动携带历史对话（来自 ConversationMemoryService 缓存），
         * 实现多轮连续对话。为空或空白时系统自动生成 UUID。
         */
        String conversationId,

        /**
         * 用户问题 —— 当前需要回答的内容。
         * 必填，最大 20000 字符。经过 @NotBlank 校验，空字符串或空白字符会被拦截。
         * 系统会将其包装为 USER 角色消息，追加到 prompt 消息列表末尾发给 AI 模型。
         */
        @NotBlank @Size(max = 20000) String question,

        /**
         * 额外历史消息 —— 客户端可选携带的补充对话。
         * 如果前端/调用方需要传入 ConversationMemoryService 缓存之外的历史对话，
         * 可以在此列表中提供。系统会将其追加到缓存历史之后、当前问题之前。
         * 每个元素需指定角色（SYSTEM/USER/ASSISTANT）和内容。
         */
        @Valid List<ChatMessageDto> history,

        /**
         * 首选模型 —— 用户指定希望用哪个 AI 模型回答。
         * 可选值：DEEPSEEK（推理/代码能力强）、ALIBABA_BAILIAN（长上下文/规划能力强）。
         * 如果该模型当前可用（API Key 已配置），路由器会优先使用它；
         * 如果不可用或未配置，路由器会自动切换到打分最高的模型。
         */
        ModelProvider preferredModel,

        /**
         * 精确模式 —— 开启后路由器会给 DeepSeek 模型加分（+22），
         * 适用于代码、推理、SQL 等需要高准确率的场景。
         * 为空时默认关闭（false），走正常路由打分逻辑。
         */
        Boolean preciseMode,

        /**
         * 扩展元数据 —— 键值对形式的附加信息，由调用方自由定义。
         * 目前主要用途：透传到内部 AiPromptRequest 的 metadata 字段，
         * 可用于链路追踪、业务标签、灰度标记等。不参与路由打分和 AI 调用。
         */
        Map<String, Object> metadata,

        /**
         * 是否使用知识库检索增强 —— 开启后，系统会先从 Milvus 向量数据库
         * 检索相关文档块，作为上下文注入 Prompt，再调用 AI 模型生成回答。
         *
         * 适用场景：
         * - true：需要基于公司内部文档、产品手册、政策制度等回答
         * - false（默认）：通用对话，不依赖知识库
         *
         * 注意：需要确保 Milvus 服务已启动且已摄入文档，否则检索结果为空。
         */
        Boolean useKnowledgeBase,

        /**
         * 自定义系统提示词 —— 覆盖默认的 PromptFactory 生成的系统提示。
         *
         * 用途：
         * - RAG 场景：注入检索结果作为上下文
         * - 特殊场景：需要临时改变 AI 行为（如角色扮演、特定格式输出）
         *
         * 如果为 null 或空白，则使用 PromptFactory 的默认提示词。
         */
        String systemPrompt
) {
}
