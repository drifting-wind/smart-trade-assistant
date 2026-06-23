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
import java.util.List;
import java.util.UUID;
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
                                    if (matches.isEmpty() || matches.get(0).score() < 0.6) {
                                        log.info("⚠️ 检索结果质量低于阈值，跳过 LLM 调用");
                                        return Mono.just(ChatResponse.noAnswer(question));
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

        return hybridSearchService.search(request.question(), maxResults)
                .flatMapMany(matches -> {
                    log.info("📚 混合检索完成，共 {} 条结果", matches.size());

                    // Rerank 重排序
                    return rerankService.rerank(request.question(), matches)
                            .flatMapMany(reranked -> {
                                log.info("🔄 Rerank 完成，最终 {} 条结果", reranked.size());
                                return streamWithMatches(request, reranked);
                            });
                })
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
                .flatMap(matches -> rerankService.rerank(query, matches))
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
        if (matches.isEmpty()) {
            return promptFactory.qaSystemPrompt();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("你是外贸智能销售助手，只基于以下参考资料回答用户的具体问题。\n\n");
        sb.append("参考资料：\n");

        for (int i = 0; i < matches.size(); i++) {
            SearchMatch match = matches.get(i);
            sb.append(i + 1).append(". ");

            // 添加标题（如果有）
            String title = match.metadata().getOrDefault("title", "未命名文档").toString();
            sb.append("[").append(title).append("] ");

            // ⭐ 优化：截断长度从200改为100，减少上下文冗余
            String text = match.text();
            if (text.length() > 100) {
                text = text.substring(0, 100) + "...";
            }
            sb.append(text);
            sb.append("\n");
        }

        // ⭐ 优化：添加严格的回答约束
        sb.append("\n回答要求：\n");
        sb.append("- 直接给出答案，不要\"好的\"、\"当然可以\"等客套话\n");
        sb.append("- 只基于参考资料，不编造、不推测、不扩展\n");
        sb.append("- 如果没有相关信息，只说\"参考资料中未找到相关信息\"\n");
        sb.append("- 控制在3句话或50字以内\n");
        sb.append("- 不要用\"根据以上信息\"、\"综上所述\"等总结性开头\n");
        sb.append("- 不要添加建议、提醒、注意事项等无关内容\n");
        sb.append("- 不要输出问候语，不要问\"还有其他问题吗？\"\n");

        // ⭐ 新增：要求标注引用来源
        sb.append("\n引用标注规则：\n");
        sb.append("- 回答中必须标注引用来源，使用 [1]、[2] 等上标格式\n");
        sb.append("- 每个事实或数据后面都要标注对应的引用编号\n");
        sb.append("- 引用编号对应上面参考资料的序号\n");
        sb.append("- 示例：\"LED-PANEL-50W 的功率是 50W [1]，电压范围 220V [1]。\"\n");

        return sb.toString();
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
                log.info("✅ RAG 回答缓存命中: {}", cacheKey);
                if (cached instanceof ChatResponse) {
                    return Mono.just((ChatResponse) cached);
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ 读取 RAG 回答缓存失败: {}", e.getMessage());
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
}
