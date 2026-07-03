package com.trade.rag;

import com.trade.dto.AiStreamEvent;
import com.trade.dto.ChatRequest;
import com.trade.dto.ChatResponse;
import com.trade.dto.Citation;
import com.trade.dto.RouteDecisionDto;
import com.trade.enums.ScenarioType;
import com.trade.config.AiGatewayProperties;
import com.trade.rag.dto.SearchResultDto;
import com.trade.rag.dto.SearchResultDto.SearchMatch;
import com.trade.service.ChatOrchestrationService;
import com.trade.service.PromptFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;

/**
 * RAG 编排服务 —— 实现检索增强生成（Retrieval-Augmented Generation）。
 *
 * 核心流程：
 * 1. 接收用户问题
 * 2. 调用 Embedding 模型将问题转为向量
 * 3. 在 Milvus 中搜索相似文档块
 * 4. 将检索结果作为上下文注入 Prompt
 * 5. 调用 AI 模型生成回答
 *
 * 与 ChatOrchestrationService 的关系：
 * - ChatOrchestrationService：纯 AI 对话，无外部知识
 * - RagOrchestrationService：AI 对话 + 知识库检索增强
 *
 * 技术点：
 * - 问题改写（Query Rewriting）：可选，将问题改写为更适合检索的形式
 * - 上下文注入：将检索结果作为 SYSTEM 消息注入 Prompt
 * - 来源标注：在回答中标注信息来源（文档标题、页码等）
 */
@Service
public class RagOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(RagOrchestrationService.class);

    private final EmbeddingService embeddingService;
    private final MilvusVectorStoreClient vectorStoreClient;
    private final HybridSearchService hybridSearchService;
    private final RerankService rerankService;
    private final ChatOrchestrationService chatService;
    private final PromptFactory promptFactory;
    private final int maxResults;
    private final RedisTemplate<String, Object> redisTemplate;

    public RagOrchestrationService(
            EmbeddingService embeddingService,
            MilvusVectorStoreClient vectorStoreClient,
            HybridSearchService hybridSearchService,
            RerankService rerankService,
            ChatOrchestrationService chatService,
            PromptFactory promptFactory,
            AiGatewayProperties properties,
            RedisTemplate<String, Object> redisTemplate
    ) {
        this.embeddingService = embeddingService;
        this.vectorStoreClient = vectorStoreClient;
        this.hybridSearchService = hybridSearchService;
        this.rerankService = rerankService;
        this.chatService = chatService;
        this.promptFactory = promptFactory;
        this.maxResults = properties.getRag().getMaxResults();
        this.redisTemplate = redisTemplate;
    }

    /**
     * 检索增强的同步问答。
     *
     * 流程：
     * 0. 先查 Redis 缓存（key: rag:answer:md5(question)），命中则直接返回
     * 1. 将问题转为向量
     * 2. 在 Milvus 搜索相关文档块
     * 3. 构建增强 Prompt（系统提示 + 检索结果 + 历史对话 + 当前问题）
     * 4. 调用 AI 模型生成回答
     * 5. 将回答写入 Redis 缓存（TTL: 24 小时）
     *
     * 优化：
     * - 如果检索结果最高相似度低于阈值，直接返回"未找到"，不调用 LLM
     *
     * @param request 用户请求
     * @return AI 回答
     */
    public Mono<ChatResponse> completeWithRetrieval(ChatRequest request) {
        log.info("🔍 RAG 问答开始: {}", request.question());

        String question = request.question();
        String cacheKey = generateRagCacheKey(question);

        // 先查缓存
        return getRagAnswerFromCache(cacheKey)
                .switchIfEmpty(
                    // 未命中，执行完整 RAG 流程
                    Mono.defer(() -> {
                        // 步骤 1 + 2: 混合检索（向量 + BM25）
                        return hybridSearchService.search(question, maxResults)
                                .flatMap(matches -> {
                                    log.info("📚 混合检索完成，共 {} 条结果", matches.size());

                                    // ⭐ 优化：如果检索结果质量太差，直接返回"未找到"
                                    if (matches.isEmpty()||matches.get(0).score() < 0.55) {
//                                        if (matches.isEmpty()||matches.get(0).score() < 0.55) {
                                        log.info("⚠️ 检索结果质量低于阈值，跳过 LLM 调用");
                                        return Mono.just(ChatResponse.noAnswer(question));
                                    }

                                    // ⭐ 优化：按产品名过滤，确保回答的是用户问的产品
                                    List<SearchMatch> filtered = filterByProductName(question, matches);
                                    if (!filtered.isEmpty()) {
                                        matches = filtered;
                                        log.info("📦 按产品名过滤后保留 {} 条结果", matches.size());
                                    } else if (matches.size() > 1) {
                                        // ⭐ 兜底：产品名过滤太严格时，尝试按内容关键词匹配
                                        List<SearchMatch> contentFiltered = filterByContentRelevance(question, matches);
                                        if (!contentFiltered.isEmpty()) {
                                            matches = contentFiltered;
                                            log.info("📦 按内容相关性过滤后保留 {} 条结果", matches.size());
                                        }
                                    }

                                    // 步骤 3: Rerank 重排序
                                    return rerankService.rerank(question, matches)
                                            .flatMap(reranked -> {
                                                log.info("🔄 Rerank 完成，最终 {} 条结果", reranked.size());

                                                // 步骤 4: 构建增强 Prompt 并调用 AI
                                                return completeWithMatches(request, reranked);
                                            });
                                });
                    })
                    // 成功后写入缓存
                    .doOnSuccess(response -> saveRagAnswerToCache(cacheKey, response))
                )
                .onErrorMap(e -> new RuntimeException("RAG 问答失败: " + e.getMessage(), e));
    }

    /**
     * 检索增强的流式问答。
     *
     * 与同步版本的区别：先返回路由事件和检索结果事件，再流式返回 token。
     *
     * @param request 用户请求
     * @return SSE 事件流
     */
    public Flux<AiStreamEvent> streamWithRetrieval(ChatRequest request) {
        log.info("🔍 RAG 流式问答开始: {}", request.question());

        String question = request.question();
        String cacheKey = generateRagCacheKey(question);

        // 先查缓存，命中则分块流式返回缓存结果（打字机效果，速度比真实 LLM 快）
        return getRagAnswerFromCache(cacheKey)
                .filter(cached -> cached.answer() != null && !cached.answer().isBlank())
                .flatMapMany(cached -> {
                    log.info("✅ RAG 回答缓存命中，分块流式输出（打字机效果）");
                    String eventId = UUID.randomUUID().toString();
                    // 将答案按每 2 个字符切分，模拟流式输出（比真实 LLM 快，仍有打字机感）
                    List<String> chunks = splitIntoChunks(cached.answer(), 2);
                    Flux<AiStreamEvent> tokenFlux = Flux.fromIterable(chunks)
                            .delayElements(java.time.Duration.ofMillis(30))
                            .map(chunk -> AiStreamEvent.token(eventId, null, chunk));
                    return Flux.concat(
                            tokenFlux,
                            Mono.just(AiStreamEvent.citations(eventId, cached.citations())),
                            Mono.just(AiStreamEvent.done(eventId, null, cached.hasRelevantInfo(), cached.citations()))
                    );
                })
                .switchIfEmpty(
                        Flux.defer(() -> {
                            // 用于累积流式回答的容器（跨 lambda 需要数组包装）
                            StringBuilder[] answerBuf = {new StringBuilder()};
                            final boolean[] hasRelevant = {false};
                            final List<Citation>[] citationList = new List[1];

                            return hybridSearchService.search(question, maxResults)
                                    .flatMapMany(matches -> {
                                        log.info("📚 混合检索完成，共 {} 条结果", matches.size());

                                        // 产品名过滤
                                        if (matches.size() > 1) {
                                            List<SearchMatch> filtered = filterByProductName(question, matches);
                                            if (!filtered.isEmpty()) {
                                                matches = filtered;
                                            } else {
                                                List<SearchMatch> cf = filterByContentRelevance(question, matches);
                                                if (!cf.isEmpty()) matches = cf;
                                            }
                                        }

                                        // Rerank 重排序
                                        return rerankService.rerank(question, matches)
                                                .flatMapMany(reranked -> {
                                                    log.info("🔄 Rerank 完成，最终 {} 条结果", reranked.size());
                                                    // 如果检索结果质量太差，返回"未找到相关信息"
                                                    if (reranked.isEmpty() || reranked.get(0).score() < 0.55) {
                                                        log.info("⚠️ 检索结果质量低于阈值，跳过 AI 调用");
                                                        String eventId = UUID.randomUUID().toString();
                                                        return Flux.concat(
                                                                Mono.just(AiStreamEvent.token(eventId, null,
                                                                        "参考资料中未找到相关信息，请尝试换个问题或上传更多文档。")),
                                                                Mono.just(AiStreamEvent.done(eventId, null, false, List.of()))
                                                        );
                                                    }
                                                    return streamWithMatches(request, reranked);
                                                });
                                    })
                                    // 累积 token 和 done 事件信息
                                    .doOnNext(event -> {
                                        if (event.type() == com.trade.enums.StreamEventType.TOKEN && event.content() != null) {
                                            answerBuf[0].append(event.content());
                                        }
                                        if (event.type() == com.trade.enums.StreamEventType.DONE) {
                                            hasRelevant[0] = Boolean.TRUE.equals(event.hasRelevantInfo());
                                            if (event.citations() != null) citationList[0] = event.citations();
                                        }
                                    })
                                    // 流完整结束后（成功/取消/异常），写入 Redis 缓存
                                    // 注意：未找到相关信息的回答不缓存
                                    .doFinally(signal -> {
                                        if (signal == reactor.core.publisher.SignalType.ON_COMPLETE) {
                                            String fullAnswer = answerBuf[0].toString();
                                            // 不缓存"未找到相关信息"的回答
                                            if (!fullAnswer.isBlank()
                                                    && !fullAnswer.contains("未找到相关信息")
                                                    && hasRelevant[0]) {
                                                ChatResponse toCache = new ChatResponse(
                                                        UUID.randomUUID().toString(),
                                                        request.conversationId(),
                                                        null, fullAnswer, null, null,
                                                        citationList[0] != null ? citationList[0] : List.of(),
                                                        java.time.Instant.now(),
                                                        hasRelevant[0]
                                                );
                                                saveRagAnswerToCache(cacheKey, toCache);
                                                log.info("✅ RAG 回答已写入缓存, TTL: 24h");
                                            } else if (!fullAnswer.isBlank()) {
                                                log.info("⚠️ 未找到相关信息，不写入缓存");
                                            }
                                        }
                                    });
                        })
                )
                .onErrorMap(e -> new RuntimeException("RAG 流式问答失败: " + e.getMessage(), e));
    }

    /**
     * 纯检索（不调用 AI） —— 返回与问题相关的文档块。
     *
     * 用于：
     * - 知识库搜索页面
     * - 验证 Embedding 和 Milvus 是否正常工作
     * - 调试检索质量
     *
     * @param query 搜索问题
     * @return 搜索结果
     */
    public Mono<SearchResultDto> search(String query) {
        return hybridSearchService.search(query, maxResults)
                .flatMap(matches -> {
                    // ⭐ 产品名过滤
                    if (matches.size() > 1) {
                        List<SearchMatch> filtered = filterByProductName(query, matches);
                        if (!filtered.isEmpty()) {
                            matches = filtered;
                        } else {
                            List<SearchMatch> cf = filterByContentRelevance(query, matches);
                            if (!cf.isEmpty()) matches = cf;
                        }
                    }
                    return rerankService.rerank(query, matches);
                })
                .map(matches -> new SearchResultDto(query, matches, 0));
    }

    /**
     * 使用检索结果完成同步问答。
     *
     * 优化：
     * 1. 构建引用信息列表（去重），确保回答"有据可查"
     * 2. 添加 hasRelevantInfo 标识，前端根据此字段决定是否展示引用
     */
    private Mono<ChatResponse> completeWithMatches(ChatRequest request, List<SearchMatch> matches) {
        // 构建引用信息列表（去重）
        List<Citation> citations = buildCitationsDeduplicated(matches);

        // 构建增强的系统提示（注入检索结果作为上下文）
        String augmentedSystemPrompt = buildAugmentedSystemPrompt(matches);

        // 构建增强的 ChatRequest（替换系统提示）
        ChatRequest augmentedRequest = new ChatRequest(
                request.conversationId(),
                request.question(),
                request.history(),
                request.preferredModel(),
                request.preciseMode(),
                request.metadata(),
                request.useKnowledgeBase(),
                augmentedSystemPrompt
        );

        // 调用 ChatOrchestrationService，并附加引用信息
        // ⭐ 只有当 citations 非空时，hasRelevantInfo 才为 true
        boolean hasRelevant = !citations.isEmpty();
        return chatService.complete(augmentedRequest)
                .map(response -> new ChatResponse(
                        response.id(),
                        response.conversationId(),
                        response.model(),
                        response.answer(),
                        response.route(),
                        response.usage(),
                        citations,  // ⭐ 附加引用信息（已去重）
                        response.createdAt(),
                        hasRelevant  // ⭐ 只有检索到结果时才展示引用
                ));
    }

    /**
     * 使用检索结果完成流式问答。
     *
     * 优化：构建引用信息列表，确保回答"有据可查"。
     */
    private Flux<AiStreamEvent> streamWithMatches(
            ChatRequest request,
            List<SearchMatch> matches
    ) {
        String eventId = UUID.randomUUID().toString();

        // 构建引用信息列表（去重）
        List<Citation> citations = buildCitationsDeduplicated(matches);

        // 构建增强的系统提示
        String augmentedSystemPrompt = buildAugmentedSystemPrompt(matches);

        // 构建增强的 ChatRequest
        ChatRequest augmentedRequest = new ChatRequest(
                request.conversationId(),
                request.question(),
                request.history(),
                request.preferredModel(),
                request.preciseMode(),
                request.metadata(),
                request.useKnowledgeBase(),
                augmentedSystemPrompt
        );

        // 先发送检索结果事件（包含引用信息）
        AiStreamEvent searchResultEvent =
                AiStreamEvent.route(
                        eventId,
                        new RouteDecisionDto(
                                ScenarioType.QA,
                                null,
                                null,
                                0.0,
                                matches.size() + " 个相关文档块",
                                null
                        )
                );

        // 再调用 ChatOrchestrationService 流式返回
        // 注意：流式模式下引用信息会在最后通过单独的 SSE 事件发送
        return Flux.concat(
                Mono.just(searchResultEvent),
                chatService.stream(augmentedRequest),
                // ⭐ 流式结束后发送引用信息事件
                Mono.just(AiStreamEvent.citations(eventId, citations)),
                // ⭐ 发送包含 hasRelevantInfo 的 DONE 事件
                Mono.just(AiStreamEvent.done(eventId, null, !citations.isEmpty(), citations))
        );
    }

    /**
     * 构建增强的系统提示 —— 将检索结果作为上下文注入。
     *
     * 优化：
     * 1. 严格约束 AI 回答风格（简洁、直接、不啰嗦）
     * 2. 缩短上下文长度（每个匹配结果最多100字符）
     * 3. 明确禁止事项（客套话、扩展、建议等）
     * 4. 要求标注引用来源（[1]、[2] 等），支持溯源定位
     */
    private String buildAugmentedSystemPrompt(List<SearchMatch> matches) {
        return promptFactory.ragSystemPrompt(matches);
    }

    /**
     * 构建引用信息列表 —— 从检索结果中提取引用信息，用于溯源定位。
     *
     * @param matches 检索结果列表
     * @return 引用信息列表
     */
    private List<Citation> buildCitations(List<SearchMatch> matches) {
        List<Citation> citations = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            SearchMatch match = matches.get(i);
            // 调试日志：打印 metadata 内容
            log.info("📄 引用 {}: metadata={}, title={}", i + 1, match.metadata(),
                    match.metadata().getOrDefault("title", "未命名文档"));
            citations.add(new Citation(
                    i + 1,  // 引用序号从 1 开始
                    match.documentId(),
                    match.chunkIndex(),
                    match.metadata().getOrDefault("title", "未命名文档").toString(),
                    match.text().length() > 100 ? match.text().substring(0, 100) + "..." : match.text(),
                    match.score(),
                    match.metadata()
            ));
        }
        return citations;
    }

    /**
     * 构建引用信息列表（去重） —— 按文档 ID + 标题去重，避免同一文档多个 chunk 重复展示。
     *
     * 去重规则：
     * - 同一个 documentId + title 只保留第一条
     * - 保留 score 最高的 chunk（因为 matches 已按 score 排序，第一个就是最高的）
     *
     * @param matches 检索结果列表
     * @return 去重后的引用信息列表
     */
    private List<Citation> buildCitationsDeduplicated(List<SearchMatch> matches) {
        List<Citation> citations = new ArrayList<>();
        // 用于去重：documentId + title
        java.util.Set<String> seen = new java.util.HashSet<>();
        int index = 1;

        for (SearchMatch match : matches) {
            String title = match.metadata().getOrDefault("title", "未命名文档").toString();
            String dedupKey = match.documentId() + "|" + title;

            // 如果已经添加过相同文档+标题的引用，跳过
            if (seen.contains(dedupKey)) {
                continue;
            }
            seen.add(dedupKey);

            log.info("📄 引用 {}: title={}, documentId={}", index, title, match.documentId());
            citations.add(new Citation(
                    index++,  // 引用序号从 1 开始
                    match.documentId(),
                    match.chunkIndex(),
                    title,
                    match.text().length() > 100 ? match.text().substring(0, 100) + "..." : match.text(),
                    match.score(),
                    match.metadata()
            ));
        }
        return citations;
    }

    /**
     * 将文本按指定大小分块 —— 用于缓存命中时的流式输出。
     *
     * @param text      原始文本
     * @param chunkSize 每块字符数
     * @return 分块后的列表
     */
    private static List<String> splitIntoChunks(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += chunkSize) {
            chunks.add(text.substring(i, Math.min(i + chunkSize, text.length())));
        }
        return chunks;
    }

    /**
     * 生成 RAG 回答缓存 key。
     *
     * 格式：rag:answer:md5(question)
     * 使用 MD5 保证 key 长度固定且唯一。
     *
     * @param question 问题
     * @return 缓存 key
     */
    private String generateRagCacheKey(String question) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(question.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("rag:answer:");
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 一定存在，不会走到这里
            return "rag:answer:" + question.hashCode();
        }
    }

    /**
     * 从 Redis 缓存获取 RAG 回答。
     *
     * @param cacheKey 缓存 key
     * @return 缓存的回答，未命中返回空 Mono
     */
    private Mono<ChatResponse> getRagAnswerFromCache(String cacheKey) {
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                log.info("✅ RAG 回答缓存命中: {}, type={}", cacheKey, cached.getClass().getName());
                if (cached instanceof ChatResponse) {
                    ChatResponse response = (ChatResponse) cached;
                    if (response.answer() != null && !response.answer().isBlank()) {
                        return Mono.just(response);
                    } else {
                        log.info("缓存回答为空，跳过");
                    }
                } else if (cached instanceof java.util.Map) {
                    // 处理 Redis 反序列化为 Map 的情况
                    log.info("⚠️ 缓存类型为 Map，尝试手动转换");
                    try {
                        java.util.Map<String, Object> map = (java.util.Map<String, Object>) cached;
                        String answer = (String) map.get("answer");
                        if (answer != null && !answer.isBlank()) {
                            Object hasRelevant = map.get("hasRelevantInfo");
                            boolean hasRel = hasRelevant instanceof Boolean ? (Boolean) hasRelevant : false;
                            // 将citations从List<Map>转换为List<Citation>
                            Object citationsObj = map.get("citations");
                            java.util.List<Citation> citationList = java.util.List.of();
                            if (citationsObj instanceof java.util.List) {
                                java.util.List<?> rawList = (java.util.List<?>) citationsObj;
                                citationList = rawList.stream()
                                        .filter(Objects::nonNull)
                                        .map(item -> {
                                            if (item instanceof Citation) {
                                                return (Citation) item;
                                            } else if (item instanceof java.util.Map) {
                                                // 使用Jackson转换Map为Citation
                                                try {
                                                    return new com.fasterxml.jackson.databind.ObjectMapper()
                                                            .convertValue(item, Citation.class);
                                                } catch (Exception e2) {
                                                    log.warn("⚠️ Citation转换失败: {}", e2.getMessage());
                                                    return null;
                                                }
                                            }
                                            return null;
                                        })
                                        .filter(Objects::nonNull)
                                        .collect(java.util.stream.Collectors.toList());
                            }
                            return Mono.just(new ChatResponse(
                                    java.util.UUID.randomUUID().toString(),
                                    null, null, answer, null, null,
                                    citationList, java.time.Instant.now(), hasRel
                            ));
                        }
                    } catch (Exception e) {
                        log.warn("⚠️ Map 转换失败: {}", e.getMessage(), e);
                    }
                } else {
                    log.warn("⚠️ 未知缓存类型: {}", cached.getClass().getName());
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ 读取 RAG 回答缓存失败: {}", e.getMessage(), e);
        }
        return Mono.empty();
    }

    /**
     * 将 RAG 回答写入 Redis 缓存。
     *
     * 缓存策略：TTL 24 小时（避免缓存过期后返回过时信息）
     *
     * @param cacheKey 缓存 key
     * @param response 回答
     */
    private void saveRagAnswerToCache(String cacheKey, ChatResponse response) {
        try {
            redisTemplate.opsForValue().set(cacheKey, response, 24, TimeUnit.HOURS);
            log.info("✅ RAG 回答已缓存: {}, TTL: 24h", cacheKey);
        } catch (Exception e) {
            log.warn("⚠️ 写入 RAG 回答缓存失败: {}", e.getMessage());
        }
    }

    /**
     * 按产品名过滤检索结果 —— 如果问题中提到了具体产品名，只保留该产品的文档。
     *
     * 场景：用户问"咖啡杯产品说明书"，检索结果中可能混入"不锈钢水瓶"等相似但不相关的产品。
     * 此方法提取问题中的产品名，与文档 metadata 中的 title 进行匹配，过滤掉不相关的文档。
     *
     * @param question 用户问题
     * @param matches  检索结果
     * @return 过滤后的结果，如果没有匹配则返回空列表（调用方应使用原始结果）
     */
    private List<SearchMatch> filterByProductName(String question, List<SearchMatch> matches) {
        log.debug("[FILTER-DEBUG] filterByProductName called, question={}, matches={}", question, matches.size());
        if (question == null || question.isEmpty() || matches.size() <= 1) {
            return List.of();
        }

        // ⭐ 中文无分词边界，用滑动窗口生成所有 2-4 字子串
        List<String> keywords = new ArrayList<>();
        for (int len = 4; len >= 2; len--) {
            for (int i = 0; i <= question.length() - len; i++) {
                String sub = question.substring(i, i + len);
                // 只保留纯中文子串
                if (sub.matches("[\u4e00-\u9fff]+")) {
                    keywords.add(sub);
                }
            }
        }
        log.debug("[FILTER-DEBUG] extracted keywords: {}", keywords);

        // ⭐ 对每个文档，计算标题中的关键词匹配数
        // 只保留匹配数最高的文档（去重不同 chunk 之间的文档）
        Map<String, Integer> docScores = new LinkedHashMap<>(); // documentId -> score
        Map<String, String> docTitles = new LinkedHashMap<>(); // documentId -> title
        
        for (SearchMatch match : matches) {
            String title = match.metadata().getOrDefault("title", "").toString();
            String docId = match.documentId();
            int score = 0;
            for (String kw : keywords) {
                if (title.contains(kw)) score++;
            }
            if (score > 0) {
                docScores.merge(docId, score, Integer::sum); // 累加同文档不同chunk的分数
                docTitles.putIfAbsent(docId, title);
            }
        }
        
        log.debug("[FILTER-DEBUG] docScores: {}", docScores);
        
        if (docScores.isEmpty()) {
            log.debug("[FILTER-DEBUG] no documents matched any keywords");
            return List.of();
        }
        
        // ⭐ 找到最高分的文档
        int maxScore = docScores.values().stream().max(Integer::compareTo).orElse(0);
        log.debug("[FILTER-DEBUG] maxScore={}, keeping only docs with max score", maxScore);
        
        // ⭐ 只保留最高分文档的 chunks
        Set<String> topDocIds = docScores.entrySet().stream()
                .filter(e -> e.getValue() == maxScore)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
        
        List<SearchMatch> filtered = matches.stream()
                .filter(match -> topDocIds.contains(match.documentId()))
                .collect(java.util.stream.Collectors.toList());

        log.info("📦 文档级过滤: {} → {} 条 (关键词={}, 最高分={}, 胜出文档={})",
                matches.size(), filtered.size(), keywords, maxScore, 
                topDocIds.stream().map(docTitles::get).collect(java.util.stream.Collectors.toList()));
        return filtered;
    }

    /**
     * 从问题中提取产品名 —— 取"产品"或"说明书"前面的名词短语。
     *
     * 示例：
     * - "咖啡杯产品说明书" → "咖啡杯"
     * - "不锈钢水瓶的规格" → "不锈钢水瓶"
     * - "LED Panel 60x60" → null（无法提取中文产品名）
     */
    /**
     * 从问题中提取产品名 —— 支持多种问题格式。
     *
     * 策略分两级：
     * 1. 关键词模式匹配："XX产品"、"XX说明书"、"XX的规格"等
     * 2. 提取所有中文词组作为候选关键词
     */
    private String extractProductName(String question) {
        if (question == null || question.isEmpty()) {
            return null;
        }

        // 策略1：关键词模式匹配
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(.+?)(?:的产品|的说明书|的规格|的参数|产品说明书|产品|说明书|规格|参数)");
        java.util.regex.Matcher matcher = pattern.matcher(question);
        if (matcher.find()) {
            String name = matcher.group(1).trim();
            name = name.replaceAll("[的是呢吗了有关于]$", "").trim();
            if (name.length() >= 2 && name.length() <= 20) {
                return name;
            }
        }

        // 策略2：提取所有中文词组（2-8字），返回最长的作为候选产品名
        java.util.regex.Pattern cnPattern = java.util.regex.Pattern.compile("[\u4e00-\u9fff]{2,8}");
        java.util.regex.Matcher cnMatcher = cnPattern.matcher(question);
        List<String> candidates = new ArrayList<>();
        while (cnMatcher.find()) {
            candidates.add(cnMatcher.group());
        }
        if (!candidates.isEmpty()) {
            // 返回最长的词组（通常就是产品名）
            candidates.sort((a, b) -> b.length() - a.length());
            return candidates.get(0);
        }
        return null;
    }

    /**
     * 按内容相关性过滤 —— 当产品名过滤太严格时，作为兜底方案。
     *
     * 策略：
     * 1. 从问题中提取关键词（长度>=2的中文词、英文单词）
     * 2. 对每个文档计算相关性得分（关键词出现次数）
     * 3. 只保留得分>=2的文档（至少匹配2个关键词）
     *
     * 示例：问题"咖啡杯产品说明书"
     * - 关键词：["咖啡杯", "产品", "说明书"]
     * - 咖啡杯文档：包含"咖啡杯" → 得分3 → 保留
     * - 水瓶文档：不包含任何关键词 → 得分0 → 过滤掉
     */
    private List<SearchMatch> filterByContentRelevance(String question, List<SearchMatch> matches) {
        // ⭐ 用 extractProductName 作为必须匹配的关键词（区分产品名和通用词）
        String productName = extractProductName(question);
        if (productName == null) {
            return List.of();
        }

        // 提取所有中文关键词
        List<String> keywords = new ArrayList<>();
        java.util.regex.Pattern cnPattern = java.util.regex.Pattern.compile("[\\u4e00-\\u9fff]{2,8}");
        java.util.regex.Matcher cnMatcher = cnPattern.matcher(question);
        while (cnMatcher.find()) {
            keywords.add(cnMatcher.group());
        }

        log.info("🔍 产品名={}, 关键词={}", productName, keywords);

        // ⭐ 核心规则：产品名必须命中，且总关键词命中>=2
        List<SearchMatch> filtered = matches.stream()
                .filter(match -> {
                    String text = match.text().toLowerCase();
                    if (!text.contains(productName)) {
                        return false; // 产品名不匹配直接排除
                    }
                    int score = 0;
                    for (String kw : keywords) {
                        if (text.contains(kw)) score++;
                    }
                    log.debug("  检查: score={}, text前50字='{}'", score, text.substring(0, Math.min(50, text.length())));
                    return score >= 2;
                })
                .collect(java.util.stream.Collectors.toList());

        log.info("📦 内容相关性过滤: {} → {} 条", matches.size(), filtered.size());
        return filtered;
    }

}
