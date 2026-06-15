package com.trade.model;

import com.trade.config.AiGatewayProperties;
import com.trade.dto.TokenUsageDto;
import com.trade.enums.ModelProvider;
import com.trade.exception.ModelInvocationException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 LangChain4j 的 AI 模型客户端 —— 通过 OpenAI 兼容协议调用 DeepSeek、通义千问等模型。
 *
 * 与旧 OpenAiCompatibleChatModel 的区别：
 * - 底层 HTTP 请求由 LangChain4j 的 OpenAiChatModel 处理（自动 SSE 解析、重试、超时控制）
 * - 流式响应通过 Flux.create 桥接 LangChain4j 的回调式 StreamingChatResponseHandler
 * - 同步调用通过 boundedElastic 调度器包装，避免阻塞 Netty 事件循环
 *
 * 对外接口（AiChatModel）完全兼容，业务层无需任何修改。
 */
public class LangChain4jChatModel implements AiChatModel {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jChatModel.class);

    private final ModelProvider provider;
    private final AiGatewayProperties.ModelConfig config;
    private final ChatModel syncModel;
    private final StreamingChatModel streamingModel;

    public LangChain4jChatModel(ModelProvider provider, AiGatewayProperties.ModelConfig config) {
        this.provider = provider;
        this.config = config;

        // 构建同步模型 —— 用于 complete() 调用
        this.syncModel = OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModel())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .timeout(config.getTimeout())
                .build();

        // 构建流式模型 —— 用于 stream() 调用（独立的流式优化配置）
        this.streamingModel = OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModel())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .timeout(config.getTimeout())
                .build();
    }

    @Override
    public ModelProvider provider() {
        return provider;
    }

    /**
     * 检查模型是否可用 —— 需要同时满足：
     * 1. enabled 开关为 true
     * 2. apiKey 已配置
     * 3. baseUrl 已配置
     * 4. model 名称已配置
     *
     * 注意：与旧实现保持一致，不做实际的 ping 调用（避免产生费用），仅检查配置完整性。
     */
    @Override
    public boolean available() {
        return config.isEnabled()
                && StringUtils.hasText(config.getApiKey())
                && StringUtils.hasText(config.getBaseUrl())
                && StringUtils.hasText(config.getModel());
    }

    /**
     * 同步调用模型 —— 等待完整响应后一次性返回。
     *
     * 实现要点：
     * - 用 boundedElastic 调度器包装同步阻塞调用，避免阻塞 Netty 事件循环
     * - 将 AiPromptRequest 转为 LangChain4j 的 ChatRequest
     * - 将 ChatResponse 转为 AiModelResponse（保持与旧实现一致的 DTO）
     * - 所有异常统一包装为 ModelInvocationException（业务层降级机制依赖此异常类型）
     */
    @Override
    public Mono<AiModelResponse> complete(AiPromptRequest request) {
        return Mono.fromCallable(() -> {
                    ChatRequest chatRequest = toChatRequest(request);
                    ChatResponse response = syncModel.chat(chatRequest);
                    return toAiModelResponse(response);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(error -> new ModelInvocationException(
                        provider,
                        "模型调用失败: " + error.getMessage(),
                        error
                ));
    }

    /**
     * 流式调用模型 —— SSE 逐 token 推送。
     *
     * 实现要点：
     * - 用 Flux.create 桥接 LangChain4j 的回调式 StreamingChatResponseHandler
     * - onPartialResponse：每次收到增量文本，推送 AiToken
     * - onCompleteResponse：流式完成，推送 complete 信号
     * - onError：异常包装为 ModelInvocationException，触发业务层降级
     */
    @Override
    public Flux<AiToken> stream(AiPromptRequest request) {
        return Flux.create(sink -> {
            try {
                ChatRequest chatRequest = toChatRequest(request);
                streamingModel.chat(chatRequest, new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        if (partialResponse != null && !partialResponse.isEmpty()) {
                            sink.next(new AiToken(provider, partialResponse));
                        }
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        sink.complete();
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.error("流式调用异常 [{}]: {}", provider, error.getMessage(), error);
                        sink.error(new ModelInvocationException(
                                provider,
                                "模型流式调用失败: " + error.getMessage(),
                                error
                        ));
                    }
                });
            } catch (Exception e) {
                log.error("启动流式调用失败 [{}]: {}", provider, e.getMessage(), e);
                sink.error(new ModelInvocationException(
                        provider,
                        "启动模型流式调用失败: " + e.getMessage(),
                        e
                ));
            }
        });
    }

    /**
     * 将内部 AiPromptRequest 转为 LangChain4j 的 ChatRequest。
     *
     * 转换规则：
     * - messages: AiPromptMessage → dev.langchain4j.data.message.ChatMessage
     *   - SYSTEM → SystemMessage
     *   - USER → UserMessage
     *   - ASSISTANT → AiMessage
     * - parameters: 使用 config 的 temperature 和 maxTokens（与旧实现行为一致）
     */
    private ChatRequest toChatRequest(AiPromptRequest request) {
        List<dev.langchain4j.data.message.ChatMessage> messages = request.messages().stream()
                .map(this::toLangChainMessage)
                .collect(Collectors.toList());

        ChatRequestParameters parameters = ChatRequestParameters.builder()
                .temperature(config.getTemperature())
                .maxOutputTokens(config.getMaxTokens())
                .build();

        return ChatRequest.builder()
                .messages(messages)
                .parameters(parameters)
                .build();
    }

    /**
     * 单条消息角色转换：MessageRole → LangChain4j ChatMessage 类型
     */
    private dev.langchain4j.data.message.ChatMessage toLangChainMessage(AiPromptMessage message) {
        String content = message.content();
        if (content == null || content.isBlank()) {
            content = " ";  // LangChain4j 不允许空内容
        }
        return switch (message.role()) {
            case SYSTEM -> new SystemMessage(content);
            case USER -> new UserMessage(content);
            case ASSISTANT -> new AiMessage(content);
        };
    }

    /**
     * 将 LangChain4j 的 ChatResponse 转为内部 AiModelResponse。
     *
     * 字段映射：
     * - content ← response.aiMessage().text()
     * - model ← response.modelName()
     * - usage.promptTokens ← response.tokenUsage().inputTokenCount()
     * - usage.completionTokens ← response.tokenUsage().outputTokenCount()
     * - usage.totalTokens ← response.tokenUsage().totalTokenCount()
     */
    private AiModelResponse toAiModelResponse(ChatResponse response) {
        var tokenUsage = response.tokenUsage();
        return new AiModelResponse(
                provider,
                response.aiMessage().text(),
                new TokenUsageDto(
                        tokenUsage.inputTokenCount(),
                        tokenUsage.outputTokenCount(),
                        tokenUsage.totalTokenCount()
                )
        );
    }
}
