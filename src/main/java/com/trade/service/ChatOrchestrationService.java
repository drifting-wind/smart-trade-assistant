package com.trade.service;

import com.trade.dto.AiStreamEvent;
import com.trade.dto.ChatResponse;
import com.trade.dto.ChatRequest;
import com.trade.enums.ModelProvider;
import com.trade.enums.ScenarioType;
import com.trade.exception.ModelInvocationException;
import com.trade.model.AiChatModel;
import com.trade.model.AiModelResponse;
import com.trade.model.AiPromptRequest;
import com.trade.model.ModelRegistry;
import com.trade.model.AiToken;
import com.trade.monitoring.AiMetrics;
import com.trade.router.ModelRouteRequest;
import com.trade.router.ModelRouter;
import com.trade.router.RouteDecision;
import com.trade.router.RouteMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 智能问答编排服务 —— 处理 /api/v1/chat 请求的核心业务逻辑。
 *
 * 技术栈：Spring WebFlux + Project Reactor（响应式编程），请求链路全异步非阻塞。
 *
 * 一条完整问答请求的生命周期：
 * 1. 请求转换（AiRequestMapper）—— 将 DTO 转为内部 AiPromptRequest（含系统提示 + 历史 + 当前问题）
 * 2. 缓存检查 —— 相同内容 + 模型 + 模式的请求直接返回缓存，避免重复调用 AI 模型
 * 3. 智能路由（ModelRouter）—— 根据场景(QA/FLOW)、内容关键词、首选模型决定用哪个 AI 模型
 * 4. 模型调用 + 降级 —— 首选模型失败时自动切换到 fallback 模型
 * 5. 响应组装 —— 将模型输出封装为 ChatResponse（含路由决策、Token 用量等）
 * 6. 会话记忆 —— 将本轮问答追加到 Caffeine 缓存中的历史对话
 *
 * 流式响应（SSE）与同步响应走同一套路由+降级逻辑，区别在于：
 * - 同步：等待模型完整返回后一次性返回
 * - 流式：边接收模型 token 边推送给客户端，最后补一个 done 事件
 */
@Service
public class ChatOrchestrationService {

    /** DTO → 内部请求的转换器 */
    private final AiRequestMapper mapper;
    /** 会话历史管理（基于 Caffeine 内存缓存） */
    private final ConversationMemoryService memoryService;
    /** System Prompt 工厂 */
    private final PromptFactory promptFactory;
    /** 智能路由器 —— 决定用哪个模型 */
    private final ModelRouter modelRouter;
    /** 路由决策 DTO 转换器 */
    private final RouteMapper routeMapper;
    /** 模型注册中心 —— 管理所有可用模型的调用客户端 */
    private final ModelRegistry modelRegistry;
    /** 响应缓存 —— 相同问题直接返回，不调用 AI */
    private final Cache<String, ChatResponse> responseCache;
    /** 指标埋点 —— 路由次数、失败次数等 */
    private final AiMetrics metrics;

    public ChatOrchestrationService(
            AiRequestMapper mapper,
            ConversationMemoryService memoryService,
            PromptFactory promptFactory,
            ModelRouter modelRouter,
            RouteMapper routeMapper,
            ModelRegistry modelRegistry,
            Cache<String, ChatResponse> responseCache,
            AiMetrics metrics
    ) {
        this.mapper = mapper;
        this.memoryService = memoryService;
        this.promptFactory = promptFactory;
        this.modelRouter = modelRouter;
        this.routeMapper = routeMapper;
        this.modelRegistry = modelRegistry;
        this.responseCache = responseCache;
        this.metrics = metrics;
    }

    /**
     * 同步问答 —— 等待 AI 模型完整返回后一次性响应。
     *
     * 业务流程：
     * 1. 获取会话历史，确保 conversationId 有效
     * 2. 将 ChatRequest 转为 AiPromptRequest（含系统提示词、历史消息、当前问题）
     * 3. 检查缓存：命中则直接返回，跳过 AI 调用（省钱 + 加速）
     * 4. 调用 ModelRouter 智能选模：根据场景类型(QA)、内容、首选模型决定目标模型
     * 5. invokeWithFallback：先调用首选模型，失败后自动降级到 fallback 模型
     * 6. 响应组装 + 缓存写入 + 会话记忆更新
     *
     * 技术点：Mono.doOnNext 用于副作用（缓存/记忆），不改变主数据流
     */
    public Mono<ChatResponse> complete(ChatRequest request) {
        // 步骤 1-2：DTO 转为内部 prompt，附带历史会话和系统提示
        // 优先使用请求中的自定义系统提示（RAG 场景），否则使用默认提示
        String systemPrompt = (request.systemPrompt() != null && !request.systemPrompt().isBlank())
                ? request.systemPrompt()
                : promptFactory.qaSystemPrompt();

        AiPromptRequest prompt = mapper.chatToPrompt(
                request,
                memoryService.history(ConversationIds.ensure(request.conversationId())),
                systemPrompt
        );
        // 步骤 3：缓存命中检查 —— key 基于"用户内容 + 首选模型 + 精确模式"
        String cacheKey = cacheKey(prompt, request);
        ChatResponse cached = responseCache.getIfPresent(cacheKey);
        if (cached != null) {
            return Mono.just(cached);
        }
        // 步骤 4：智能路由，决定用哪个模型
        //preferredModel:首选模型 —— 用户指定希望用哪个 AI 模型回答。  preciseMode: 精确模式 —— 开启后路由器会给 DeepSeek 模型加分（+22）
        RouteDecision route = route(prompt, request.preferredModel(), Boolean.TRUE.equals(request.preciseMode()));
        // 步骤 5-6：调用模型（含降级），组装响应，写入缓存和记忆
        return invokeWithFallback(prompt, route)
                .map(modelResponse -> toChatResponse(prompt, route, modelResponse))
                .doOnNext(response -> {
                    responseCache.put(cacheKey, response); // 缓存本轮响应
                    memoryService.appendTurn(response.conversationId(), request.question(), response.answer()); // 追加对话轮次
                });
    }

    /**
     * 流式问答 —— SSE (Server-Sent Events) 实时推送模型输出。
     *
     * 与普通同步调用的区别：模型逐字返回 token 时立刻推送给客户端，提升用户感知速度。
     *
     * SSE 事件流结构：
     * 1. route 事件 —— 告知客户端路由决策（用了哪个模型、为什么）
     * 2. token 事件（N 次）—— 每个 token 推送一次
     * 3. done 事件 —— 流式输出完成
     * 4. error 事件（如有异常）—— 错误信息
     *
     * 技术点：
     * - Flux.concat：按顺序拼接多个 Flux/Mono 流
     * - doOnNext：累积 token 到 StringBuilder，最后拼接为完整回答存入记忆
     * - onErrorResume：流式降级，主模型失败切换 fallback，仍无法恢复则发送 error 事件
     */
    public Flux<AiStreamEvent> stream(ChatRequest request) {
        // 步骤 1-2：与同步调用相同，构造 prompt（但流式不需要缓存检查）
        // 优先使用请求中的自定义系统提示（RAG 场景），否则使用默认提示
        String systemPrompt = (request.systemPrompt() != null && !request.systemPrompt().isBlank())
                ? request.systemPrompt()
                : promptFactory.qaSystemPrompt();

        AiPromptRequest prompt = mapper.chatToPrompt(
                request,
                memoryService.history(ConversationIds.ensure(request.conversationId())),
                systemPrompt
        );
        // 步骤 3：智能路由
        RouteDecision route = route(prompt, request.preferredModel(), Boolean.TRUE.equals(request.preciseMode()));
        String eventId = UUID.randomUUID().toString();
        // StringBuilder 用于累积流式 token，最后在 done 事件中存入会话记忆
        StringBuilder answer = new StringBuilder();
        Flux<AiStreamEvent> tokenStream = streamWithFallback(prompt, route)
                .doOnNext(token -> answer.append(token.content())) // 累积每个 token
                .map(token -> AiStreamEvent.token(eventId, token.provider(), token.content())) // 转为 SSE 事件
                .concatWith(Mono.fromSupplier(() -> {
                    // 流式完成后：保存完整回答到会话记忆
                    memoryService.appendTurn(prompt.conversationId(), request.question(), answer.toString());
                    return AiStreamEvent.done(eventId, route.selectedModel());
                }))
                .onErrorResume(error -> Mono.just(AiStreamEvent.error(eventId, route.selectedModel(), error.getMessage())));
        // 路由决策事件排在最前面，后面紧跟 token 流
        return Flux.concat(Mono.just(AiStreamEvent.route(eventId, routeMapper.toDto(route))), tokenStream);
    }

    /**
     * 调用 ModelRouter 进行智能路由，返回路由决策。
     * 同时埋点路由指标（用于 Grafana/Prometheus 监控面板）。
     */
    private RouteDecision route(AiPromptRequest prompt, ModelProvider preferredModel, boolean preciseMode) {
        RouteDecision route = modelRouter.route(new ModelRouteRequest(
                ScenarioType.QA, // 固定为问答场景
                prompt.joinedContent(),
                preferredModel,
                preciseMode
        ));
        metrics.routed(ScenarioType.QA, route.selectedModel());
        return route;
    }

    /**
     * 模型调用 + 自动降级 —— 首选模型失败时切换到 fallback 模型。
     *
     * 技术点：Mono.onErrorResume 是 Reactor 的"try-catch"，
     * 只捕获 ModelInvocationException（调用层异常，如网络超时、401 鉴权失败等），
     * 401 异常不会重试（在 OpenAiCompatibleChatModel 中已排除）。
     */
    private Mono<AiModelResponse> invokeWithFallback(AiPromptRequest prompt, RouteDecision route) {
        return modelRegistry.require(route.selectedModel()).complete(prompt)
                .onErrorResume(ModelInvocationException.class, error -> {
                    metrics.failed(ScenarioType.QA, route.selectedModel());
                    if (route.fallbackModel() == null) {
                        return Mono.error(error); // 无 fallback 模型，直接抛异常
                    }
                    // 切换到 fallback 模型重试
                    return modelRegistry.require(route.fallbackModel()).complete(prompt);
                });
    }

    /**
     * 流式模型调用 + 自动降级 —— 与 invokeWithFallback 逻辑相同，但返回 Flux<AiToken>。
     */
    private Flux<AiToken> streamWithFallback(AiPromptRequest prompt, RouteDecision route) {
        AiChatModel selected = modelRegistry.require(route.selectedModel());
        return selected.stream(prompt)
                .onErrorResume(ModelInvocationException.class, error -> {
                    metrics.failed(ScenarioType.QA, route.selectedModel());
                    if (route.fallbackModel() == null) {
                        return Flux.error(error);
                    }
                    return modelRegistry.require(route.fallbackModel()).stream(prompt);
                });
    }

    /** 将模型原始响应组装为业务层 ChatResponse */
    private ChatResponse toChatResponse(AiPromptRequest prompt, RouteDecision route, AiModelResponse modelResponse) {
        return new ChatResponse(
                prompt.requestId(),
                prompt.conversationId(),
                modelResponse.provider(),
                modelResponse.content(),
                routeMapper.toDto(route), // 路由决策信息返回给客户端，方便调试
                modelResponse.usage(),    // Token 用量统计
                java.util.Collections.emptyList(), // 引用信息（非 RAG 场景为空）
                Instant.now(),
                false  // 非 RAG 场景，没有引用信息
        );
    }

    /** 缓存 key 生成：基于问题内容 + 模型 + 精确模式的哈希值 */
    private String cacheKey(AiPromptRequest prompt, ChatRequest request) {
        return Objects.hash(prompt.joinedContent(), request.preferredModel(), request.preciseMode()) + "";
    }
}
