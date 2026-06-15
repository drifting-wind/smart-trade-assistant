package com.trade.rag;

import com.trade.config.AiGatewayProperties;
import com.trade.dto.ChatRequest;
import com.trade.dto.ChatResponse;
import com.trade.rag.dto.SearchResultDto.SearchMatch;
import com.trade.service.ChatOrchestrationService;
import com.trade.service.PromptFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RagOrchestrationService 单元测试
 *
 * 测试重点：
 * 1. 完整 RAG 流程 - 混合检索 → Rerank → LLM 调用
 * 2. 缓存命中 - 相同问题直接返回缓存
 * 3. 检索结果质量低于阈值 - 直接返回"未找到"
 * 4. 流式 RAG 流程
 * 5. 纯检索模式（不调用 AI）
 * 6. 引用信息构建
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagOrchestrationServiceTest {

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private MilvusVectorStoreClient vectorStoreClient;

    @Mock
    private HybridSearchService hybridSearchService;

    @Mock
    private RerankService rerankService;

    @Mock
    private ChatOrchestrationService chatService;

    @Mock
    private PromptFactory promptFactory;

    @Mock
    private AiGatewayProperties properties;

    @Mock
    private AiGatewayProperties.Rag ragProperties;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private RagOrchestrationService ragOrchestrationService;

    @BeforeEach
    void setUp() {
        // 配置 Mock 对象
        when(properties.getRag()).thenReturn(ragProperties);
        when(ragProperties.getMaxResults()).thenReturn(10);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        ragOrchestrationService = new RagOrchestrationService(
                embeddingService,
                vectorStoreClient,
                hybridSearchService,
                rerankService,
                chatService,
                promptFactory,
                properties,
                redisTemplate
        );
    }

    @Test
    void testCompleteWithRetrieval_CacheHit() {
        // 准备测试数据
        String question = "LED面板灯功率是多少？";
        String cacheKey = "rag:answer:" + md5(question);

        ChatResponse cachedResponse = new ChatResponse(
                "test-id",
                null,
                null,
                "缓存回答",
                null,
                null,
                Collections.emptyList(),
                java.time.Instant.now()
        );

        // Mock 行为：缓存命中
        when(valueOperations.get(cacheKey)).thenReturn(cachedResponse);

        // 执行测试
        ChatRequest request = new ChatRequest(null, question, null, null, null, null, null, null);

        StepVerifier.create(ragOrchestrationService.completeWithRetrieval(request))
                .assertNext(response -> {
                    // 验证返回缓存结果
                    assertThat(response.answer()).isEqualTo("缓存回答");
                    assertThat(response.id()).isEqualTo("test-id");
                })
                .verifyComplete();

        // 验证没有调用混合检索
        verify(hybridSearchService, never()).search(anyString(), anyInt());
    }

    @Test
    void testCompleteWithRetrieval_CacheMiss() {
        // 准备测试数据
        String question = "LED面板灯功率是多少？";
        String cacheKey = "rag:answer:" + md5(question);

        List<SearchMatch> searchResults = List.of(
                createSearchMatch("doc1", 0, "LED面板灯功率为50W", 0.9),
                createSearchMatch("doc1", 1, "电压范围220V", 0.8)
        );

        ChatResponse aiResponse = new ChatResponse(
                "ai-id",
                null,
                null,
                "LED面板灯功率是50W [1]",
                null,
                null,
                Collections.emptyList(),
                java.time.Instant.now()
        );

        // Mock 行为：缓存未命中
        when(valueOperations.get(cacheKey)).thenReturn(null);
        when(hybridSearchService.search(question, 10)).thenReturn(Mono.just(searchResults));
        when(rerankService.rerank(question, searchResults)).thenReturn(Mono.just(searchResults));
        when(promptFactory.qaSystemPrompt()).thenReturn("系统提示");
        when(chatService.complete(any(ChatRequest.class))).thenReturn(Mono.just(aiResponse));

        // 执行测试
        ChatRequest request = new ChatRequest(null, question, null, null, null, null, null, null);

        StepVerifier.create(ragOrchestrationService.completeWithRetrieval(request))
                .assertNext(response -> {
                    // 验证结果
                    assertThat(response).isNotNull();
                    assertThat(response.answer()).isNotBlank();
                })
                .verifyComplete();

        // 验证调用了混合检索
        verify(hybridSearchService).search(question, 10);
        verify(rerankService).rerank(question, searchResults);
    }

    @Test
    void testCompleteWithRetrieval_LowQualityResults() {
        // 准备测试数据：所有结果分数都低于阈值 0.6
        String question = "不相关问题";
        String cacheKey = "rag:answer:" + md5(question);

        List<SearchMatch> lowQualityResults = List.of(
                createSearchMatch("doc1", 0, "无关内容", 0.3),
                createSearchMatch("doc2", 0, "另一个无关内容", 0.4)
        );

        // Mock 行为
        when(valueOperations.get(cacheKey)).thenReturn(null);
        when(hybridSearchService.search(question, 10)).thenReturn(Mono.just(lowQualityResults));

        // 执行测试
        ChatRequest request = new ChatRequest(null, question, null, null, null, null, null, null);

        StepVerifier.create(ragOrchestrationService.completeWithRetrieval(request))
                .assertNext(response -> {
                    // 验证返回"未找到"
                    assertThat(response.answer()).contains("未找到相关信息");
                    assertThat(response.citations()).isEmpty();
                })
                .verifyComplete();

        // 验证没有调用 Rerank 和 AI
        verify(rerankService, never()).rerank(anyString(), anyList());
        verify(chatService, never()).complete(any(ChatRequest.class));
    }

    @Test
    void testCompleteWithRetrieval_EmptyResults() {
        // 准备测试数据：空结果
        String question = "完全不相关的问题";
        String cacheKey = "rag:answer:" + md5(question);

        // Mock 行为
        when(valueOperations.get(cacheKey)).thenReturn(null);
        when(hybridSearchService.search(question, 10)).thenReturn(Mono.just(Collections.emptyList()));

        // 执行测试
        ChatRequest request = new ChatRequest(null, question, null, null, null, null, null, null);

        StepVerifier.create(ragOrchestrationService.completeWithRetrieval(request))
                .assertNext(response -> {
                    // 验证返回"未找到"
                    assertThat(response.answer()).contains("未找到相关信息");
                })
                .verifyComplete();
    }

    @Test
    void testStreamWithRetrieval() {
        // 准备测试数据
        String question = "LED面板灯功率是多少？";

        List<SearchMatch> searchResults = List.of(
                createSearchMatch("doc1", 0, "LED面板灯功率为50W", 0.9)
        );

        // Mock 行为
        when(hybridSearchService.search(question, 10)).thenReturn(Mono.just(searchResults));
        when(rerankService.rerank(question, searchResults)).thenReturn(Mono.just(searchResults));
        when(promptFactory.qaSystemPrompt()).thenReturn("系统提示");

        // 模拟流式响应
        com.trade.dto.AiStreamEvent routeEvent = com.trade.dto.AiStreamEvent.route(
                "event-id",
                new com.trade.dto.RouteDecisionDto(
                        com.trade.enums.ScenarioType.QA,
                        com.trade.enums.ModelProvider.DEEPSEEK,
                        null,
                        1.0,
                        "测试路由",
                        null
                )
        );

        com.trade.dto.AiStreamEvent tokenEvent = com.trade.dto.AiStreamEvent.token(
                "event-id",
                com.trade.enums.ModelProvider.DEEPSEEK,
                "LED"
        );

        com.trade.dto.AiStreamEvent doneEvent = com.trade.dto.AiStreamEvent.done(
                "event-id",
                com.trade.enums.ModelProvider.DEEPSEEK
        );

        when(chatService.stream(any(ChatRequest.class)))
                .thenReturn(reactor.core.publisher.Flux.just(routeEvent, tokenEvent, doneEvent));

        // 执行测试
        ChatRequest request = new ChatRequest(null, question, null, null, null, null, null, null);

        StepVerifier.create(ragOrchestrationService.streamWithRetrieval(request).take(5))
                .assertNext(event -> {
                    // 第一个事件应该是路由事件（来自 streamWithMatches）
                    assertThat(event.type()).isEqualTo(com.trade.enums.StreamEventType.ROUTE);
                })
                .assertNext(event -> {
                    // 第二个事件是 chatService.stream 的第一个事件（route）
                    assertThat(event.type()).isEqualTo(com.trade.enums.StreamEventType.ROUTE);
                })
                .assertNext(event -> {
                    // 第三个事件应该是 token 事件
                    assertThat(event.type()).isEqualTo(com.trade.enums.StreamEventType.TOKEN);
                })
                .assertNext(event -> {
                    // 第四个事件应该是 done 事件
                    assertThat(event.type()).isEqualTo(com.trade.enums.StreamEventType.DONE);
                })
                .assertNext(event -> {
                    // 第五个事件应该是引用事件
                    assertThat(event.type()).isEqualTo(com.trade.enums.StreamEventType.CITATIONS);
                    assertThat(event.citations()).isNotEmpty();
                })
                .verifyComplete();
    }

    @Test
    void testSearch_PureRetrieval() {
        // 准备测试数据
        String query = "LED面板灯";

        List<SearchMatch> searchResults = List.of(
                createSearchMatch("doc1", 0, "LED面板灯产品介绍", 0.9),
                createSearchMatch("doc2", 0, "LED面板灯规格", 0.8)
        );

        // Mock 行为
        when(hybridSearchService.search(query, 10)).thenReturn(Mono.just(searchResults));
        when(rerankService.rerank(query, searchResults)).thenReturn(Mono.just(searchResults));

        // 执行测试
        StepVerifier.create(ragOrchestrationService.search(query))
                .assertNext(result -> {
                    // 验证结果
                    assertThat(result.query()).isEqualTo(query);
                    assertThat(result.matches()).hasSize(2);
                    assertThat(result.searchTimeMs()).isGreaterThanOrEqualTo(0);
                })
                .verifyComplete();

        // 验证没有调用 AI
        verify(chatService, never()).complete(any(ChatRequest.class));
    }

    @Test
    void testBuildCitations() {
        // 准备测试数据
        String question = "测试问题";

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("title", "测试文档");

        List<SearchMatch> searchResults = List.of(
                createSearchMatchWithMetadata("doc1", 0, "内容1", 0.9, metadata),
                createSearchMatchWithMetadata("doc2", 0, "内容2", 0.8, metadata)
        );

        ChatResponse aiResponse = new ChatResponse(
                "ai-id",
                null,
                null,
                "回答内容",
                null,
                null,
                Collections.emptyList(),
                java.time.Instant.now()
        );

        // Mock 行为
        when(valueOperations.get(anyString())).thenReturn(null);
        when(hybridSearchService.search(question, 10)).thenReturn(Mono.just(searchResults));
        when(rerankService.rerank(question, searchResults)).thenReturn(Mono.just(searchResults));
        when(promptFactory.qaSystemPrompt()).thenReturn("系统提示");
        when(chatService.complete(any(ChatRequest.class))).thenReturn(Mono.just(aiResponse));

        // 执行测试
        ChatRequest request = new ChatRequest(null, question, null, null, null, null, null, null);

        StepVerifier.create(ragOrchestrationService.completeWithRetrieval(request))
                .assertNext(response -> {
                    // 验证引用信息
                    assertThat(response.citations()).hasSize(2);
                    assertThat(response.citations().get(0).index()).isEqualTo(1);
                    assertThat(response.citations().get(0).documentId()).isEqualTo("doc1");
                    assertThat(response.citations().get(0).title()).isEqualTo("测试文档");
                    assertThat(response.citations().get(1).index()).isEqualTo(2);
                    assertThat(response.citations().get(1).documentId()).isEqualTo("doc2");
                })
                .verifyComplete();
    }

    @Test
    void testAugmentedSystemPrompt() {
        // 准备测试数据
        String question = "测试问题";

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("title", "产品手册");

        List<SearchMatch> searchResults = List.of(
                createSearchMatchWithMetadata("doc1", 0, "LED面板灯功率为50W，电压范围220V", 0.9, metadata)
        );

        ChatResponse aiResponse = new ChatResponse(
                "ai-id",
                null,
                null,
                "LED面板灯功率是50W [1]",
                null,
                null,
                Collections.emptyList(),
                java.time.Instant.now()
        );

        // Mock 行为
        when(valueOperations.get(anyString())).thenReturn(null);
        when(hybridSearchService.search(question, 10)).thenReturn(Mono.just(searchResults));
        when(rerankService.rerank(question, searchResults)).thenReturn(Mono.just(searchResults));
        when(promptFactory.qaSystemPrompt()).thenReturn("系统提示");
        when(chatService.complete(any(ChatRequest.class))).thenReturn(Mono.just(aiResponse));

        // 执行测试
        ChatRequest request = new ChatRequest(null, question, null, null, null, null, null, null);

        StepVerifier.create(ragOrchestrationService.completeWithRetrieval(request))
                .assertNext(response -> {
                    // 验证结果
                    assertThat(response).isNotNull();
                })
                .verifyComplete();

        // 验证调用 chatService 时传入了增强的 Prompt
        verify(chatService).complete(argThat(req ->
                req.systemPrompt() != null &&
                req.systemPrompt().contains("产品手册") &&
                req.systemPrompt().contains("LED面板灯功率为50W") &&
                req.systemPrompt().contains("引用标注规则")
        ));
    }

    /**
     * 创建测试用的 SearchMatch
     */
    private SearchMatch createSearchMatch(String documentId, int chunkIndex, String text, double score) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("title", "测试文档");
        return new SearchMatch(text, score, documentId, chunkIndex, metadata);
    }

    /**
     * 创建带自定义元数据的 SearchMatch
     */
    private SearchMatch createSearchMatchWithMetadata(String documentId, int chunkIndex, String text,
                                                      double score, Map<String, Object> metadata) {
        return new SearchMatch(text, score, documentId, chunkIndex, metadata);
    }

    /**
     * 计算 MD5（用于生成缓存 key）
     */
    private String md5(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }
}
