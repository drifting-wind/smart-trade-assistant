package com.example.multiai.model;

import com.example.multiai.config.AiGatewayProperties;
import com.example.multiai.dto.TokenUsageDto;
import com.example.multiai.enums.MessageRole;
import com.example.multiai.enums.ModelProvider;
import com.example.multiai.exception.ModelInvocationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 兼容 OpenAI 协议的 AI 模型客户端 —— 通过 HTTP 调用任意兼容 OpenAI Chat Completions 接口的模型。
 *
 * 技术栈：
 * - Spring WebFlux WebClient：异步非阻塞 HTTP 客户端
 * - Reactor Mono/Flux：响应式流处理
 * - Jackson：JSON 序列化/反序列化
 *
 * 支持两种调用模式：
 * 1. complete()：同步模式，等待模型完整返回后响应
 * 2. stream()：流式模式（SSE），逐 token 接收并推送
 *
 * 协议说明：
 * - 请求体：OpenAI Chat Completions 格式（model、messages、temperature、max_tokens、stream）
 * - 同步响应：choices[0].message.content + usage 统计
 * - 流式响应：SSE 格式，每行 data: {...}，choices[0].delta.content 为增量 token
 *
 * 请求 ─▶ ModelRouter（关键词打分、场景匹配） ─▶ 选模型 ─▶ 调用 ─▶ 失败降级
 */
public class OpenAiCompatibleChatModel implements AiChatModel {

    private final ModelProvider provider;
    private final AiGatewayProperties.ModelConfig config;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleChatModel(
            ModelProvider provider,
            AiGatewayProperties.ModelConfig config,
            WebClient.Builder builder,
            ObjectMapper objectMapper
    ) {
        this.provider = provider;
        this.config = config;
        this.webClient = builder.baseUrl(config.getBaseUrl()).build();
        this.objectMapper = objectMapper;
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
     * 任意一项缺失，该模型不会被注册到可用模型列表中。
     */
    @Override
    public boolean available() {
        return config.isEnabled()
                && StringUtils.hasText(config.getApiKey())
                && StringUtils.hasText(config.getBaseUrl())
                && StringUtils.hasText(config.getModel());
    }

    /**
     * 同步调用模型 —— POST 请求到 OpenAI 兼容接口，等待完整响应。
     *
     * 调用链：
     * 1. 构造 HTTP POST 请求，目标地址 = baseUrl + path
     * 2. 设置请求头：Content-Type=JSON, Authorization=Bearer {apiKey}
     * 3. 请求体：model 名称 + messages 列表 + temperature + max_tokens + stream=false
     * 4. 超时控制：config.getTimeout()（默认 60s）
     * 5. 重试策略：失败时自动重试 1 次（250ms 后退避），但 401 鉴权失败不重试
     * 6. 解析响应：提取 choices[0].message.content 和 usage 统计
     * 7. 异常包装：所有异常统一包装为 ModelInvocationException
     */
    @Override
    public Mono<AiModelResponse> complete(AiPromptRequest request) {
        return webClient.post()
                .uri(config.getPath())
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
                .bodyValue(payload(request, false))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(config.getTimeout())
                .retryWhen(Retry.backoff(1, Duration.ofMillis(250)).filter(this::retryable))
                .map(this::toResponse)
                .onErrorMap(error -> new ModelInvocationException(provider, "模型调用失败: " + error.getMessage(), error));
    }

    /**
     * 流式调用模型 —— SSE 协议接收逐 token 输出。
     *
     * 与 complete() 的区别：
     * - Accept: text/event-stream 声明接收 SSE 流
     * - stream=true 开启流式输出
     * - bodyToFlux(String.class) 接收字符串流（SSE 原始文本）
     * - extractDataFrames 解析 SSE 帧（提取 data: 后面的 JSON）
     * - toToken 从 JSON 中提取 delta.content 增量文本
     * - filter 过滤空 token，避免推送无意义事件
     */
    @Override
    public Flux<AiToken> stream(AiPromptRequest request) {
        return webClient.post()
                .uri(config.getPath())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
                .bodyValue(payload(request, true))
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(config.getTimeout())
                .flatMapIterable(this::extractDataFrames)
                .filter(frame -> !"[DONE]".equals(frame))
                .map(this::toToken)
                .filter(token -> StringUtils.hasText(token.content()))
                .onErrorMap(error -> new ModelInvocationException(provider, "模型流式调用失败: " + error.getMessage(), error));
    }

    /**
     * 构造 OpenAI 协议请求体 —— 兼容所有模型的统一格式。
     * 使用 LinkedHashMap 保持字段顺序，方便调试日志阅读。
     */
    private Map<String, Object> payload(AiPromptRequest request, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        body.put("messages", request.messages().stream().map(this::message).toList());
        body.put("temperature", config.getTemperature());
        body.put("max_tokens", config.getMaxTokens());
        body.put("stream", stream);
        return body;
    }

    /** 将内部 AiPromptMessage 转为 OpenAI 协议的 {role, content} 格式 */
    private Map<String, String> message(AiPromptMessage message) {
        return Map.of(
                "role", message.role().name().toLowerCase(Locale.ROOT),
                "content", message.content()
        );
    }

    /** 解析 OpenAI 同步响应 JSON —— 提取文本内容和 Token 用量 */
    private AiModelResponse toResponse(JsonNode root) {
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        JsonNode usage = root.path("usage");
        return new AiModelResponse(
                provider,
                content,
                new TokenUsageDto(
                        nullableInt(usage, "prompt_tokens"),
                        nullableInt(usage, "completion_tokens"),
                        nullableInt(usage, "total_tokens")
                )
        );
    }

    /** 安全读取整数字段 —— 字段不存在时返回 null */
    private Integer nullableInt(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asInt() : null;
    }

    /**
     * 解析 SSE 流式响应的单个帧 —— 从 data: 行中提取 JSON 并读取 delta.content。
     * 流式响应格式示例：
     *   data: {"choices":[{"delta":{"content":"你"},"index":0}]}
     */
    private AiToken toToken(String data) {
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode delta = root.path("choices").path(0).path("delta");
            String content = delta.has("content") ? delta.path("content").asText("") : "";
            return new AiToken(provider, content);
        } catch (Exception error) {
            throw new ModelInvocationException(provider, "无法解析模型流式响应", error);
        }
    }

    /**
     * 从 SSE 原始文本块中提取 JSON 帧。
     *
     * SSE 协议格式：每行以 "data: " 开头，后面跟 JSON。
     * 此方法处理 WebClient 返回的原始文本块，可能包含多行。
     * 同时兼容不带 "data:" 前缀的裸 JSON 行（部分模型实现不规范）。
     */
    private List<String> extractDataFrames(String chunk) {
        List<String> frames = new ArrayList<>();
        String[] lines = chunk.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("data:")) {
                frames.add(trimmed.substring(5).trim());
            } else if (trimmed.startsWith("{")) {
                frames.add(trimmed);
            }
        }
        return frames;
    }

    /**
     * 判断异常是否可重试 —— 401 鉴权失败不可重试（重试也没用），其他异常（如超时、5xx）可重试。
     */
    private boolean retryable(Throwable error) {
        String message = error.getMessage();
        return message == null || !message.contains("401");
    }
}
