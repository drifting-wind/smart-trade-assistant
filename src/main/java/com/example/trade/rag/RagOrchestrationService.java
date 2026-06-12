package com.example.trade.rag;

import com.example.trade.dto.AiStreamEvent;
import com.example.trade.dto.ChatRequest;
import com.example.trade.dto.ChatResponse;
import com.example.trade.dto.RouteDecisionDto;
import com.example.trade.enums.ScenarioType;
import com.example.trade.rag.dto.SearchResultDto;
import com.example.trade.rag.dto.SearchResultDto.SearchMatch;
import com.example.trade.service.ChatOrchestrationService;
import com.example.trade.service.PromptFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

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
    private final ChatOrchestrationService chatService;
    private final PromptFactory promptFactory;
    private final int maxResults;

    public RagOrchestrationService(
            EmbeddingService embeddingService,
            MilvusVectorStoreClient vectorStoreClient,
            ChatOrchestrationService chatService,
            PromptFactory promptFactory,
            com.example.trade.config.AiGatewayProperties properties
    ) {
        this.embeddingService = embeddingService;
        this.vectorStoreClient = vectorStoreClient;
        this.chatService = chatService;
        this.promptFactory = promptFactory;
        this.maxResults = properties.getRag().getMaxResults();
    }

    /**
     * 检索增强的同步问答。
     *
     * 流程：
     * 1. 将问题转为向量
     * 2. 在 Milvus 搜索相关文档块
     * 3. 构建增强 Prompt（系统提示 + 检索结果 + 历史对话 + 当前问题）
     * 4. 调用 AI 模型生成回答
     *
     * @param request 用户请求
     * @return AI 回答
     */
    public Mono<ChatResponse> completeWithRetrieval(ChatRequest request) {
        log.info("🔍 RAG 问答开始: {}", request.question());

        // 步骤 1: 问题向量化
        return embeddingService.embed(request.question())
                .flatMap(embedding -> {
                    log.debug("🧮 问题向量化完成，维度: {}", embedding.length);

                    // 步骤 2: 检索相关文档
                    return vectorStoreClient.search(embedding, maxResults)
                            .flatMap(matches -> {
                                log.info("📚 检索到 {} 个相关文档块", matches.size());

                                // 步骤 3: 构建增强 Prompt 并调用 AI
                                return completeWithMatches(request, matches);
                            });
                })
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

        return embeddingService.embed(request.question())
                .flatMapMany(embedding ->
                        vectorStoreClient.search(embedding, maxResults)
                                .flatMapMany(matches ->
                                        streamWithMatches(request, matches)
                                )
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
        return embeddingService.embed(query)
                .flatMap(embedding -> vectorStoreClient.search(embedding, maxResults))
                .map(matches -> new SearchResultDto(query, matches, 0));
    }

    /**
     * 使用检索结果完成同步问答。
     */
    private Mono<ChatResponse> completeWithMatches(ChatRequest request, List<SearchMatch> matches) {
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

        // 调用 ChatOrchestrationService
        return chatService.complete(augmentedRequest);
    }

    /**
     * 使用检索结果完成流式问答。
     */
    private Flux<AiStreamEvent> streamWithMatches(
            ChatRequest request,
            List<SearchMatch> matches
    ) {
        String eventId = UUID.randomUUID().toString();

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

        // 先发送检索结果事件
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
        return Flux.concat(
                Mono.just(searchResultEvent),
                chatService.stream(augmentedRequest)
        );
    }

    /**
     * 构建增强的系统提示 —— 将检索结果作为上下文注入。
     *
     * 格式：
     * ```
     * 你是一个专业的外贸智能助手。请根据以下参考资料回答用户问题。
     * 如果参考资料中没有相关信息，请如实告知，不要编造。
     *
     * 参考资料：
     * 1. [标题] 内容摘要
     * 2. [标题] 内容摘要
     * ...
     *
     * 注意：回答时引用来源（如"根据文档X"），提高可信度。
     * ```
     */
    private String buildAugmentedSystemPrompt(List<SearchMatch> matches) {
        if (matches.isEmpty()) {
            return promptFactory.qaSystemPrompt();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("你是一个专业的外贸智能助手。请根据以下参考资料回答用户问题。\n");
        sb.append("如果参考资料中没有相关信息，请如实告知，不要编造。\n\n");
        sb.append("参考资料：\n");

        for (int i = 0; i < matches.size(); i++) {
            SearchMatch match = matches.get(i);
            sb.append(i + 1).append(". ");

            // 添加标题（如果有）
            String title = match.metadata().getOrDefault("title", "未命名文档").toString();
            sb.append("[").append(title).append("] ");

            // 添加内容（截断过长的文本）
            String text = match.text();
            if (text.length() > 200) {
                text = text.substring(0, 200) + "...";
            }
            sb.append(text);
            sb.append("\n\n");
        }

        sb.append("注意：回答时引用来源（如\"根据文档X\"），提高可信度。");

        return sb.toString();
    }
}
