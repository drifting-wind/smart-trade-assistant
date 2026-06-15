package com.trade.rag;

import com.trade.config.AiGatewayProperties;
import com.trade.rag.dto.SearchResultDto.SearchMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * HybridSearchService 单元测试
 *
 * 测试重点：
 * 1. 混合检索 - 向量 + BM25 并行执行
 * 2. RRF 融合算法
 * 3. 降级策略 - 混合检索关闭时降级为纯向量检索
 * 4. 错误处理 - 向量检索失败时降级
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HybridSearchServiceTest {

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private MilvusVectorStoreClient vectorStoreClient;

    @Mock
    private Bm25IndexService bm25IndexService;

    @Mock
    private AiGatewayProperties properties;

    @Mock
    private AiGatewayProperties.Rag ragProperties;

    @Mock
    private AiGatewayProperties.Rag.Hybrid hybridProperties;

    private HybridSearchService hybridSearchService;

    @BeforeEach
    void setUp() {
        // 配置 Mock 对象
        when(properties.getRag()).thenReturn(ragProperties);
        when(ragProperties.getHybrid()).thenReturn(hybridProperties);
        when(hybridProperties.isEnabled()).thenReturn(true);
        when(hybridProperties.getVectorWeight()).thenReturn(0.7);
        when(hybridProperties.getBm25Weight()).thenReturn(0.3);

        hybridSearchService = new HybridSearchService(
                embeddingService,
                vectorStoreClient,
                bm25IndexService,
                properties
        );
    }

    @Test
    void testHybridSearch() {
        // 准备测试数据
        List<SearchMatch> vectorResults = List.of(
                createSearchMatch("doc1", 0, "LED面板灯介绍", 0.8),
                createSearchMatch("doc1", 1, "产品规格", 0.7)
        );

        List<Bm25IndexService.Bm25Match> bm25Matches = List.of(
                new Bm25IndexService.Bm25Match("doc1", 0, "LED面板灯介绍", 1.5f),
                new Bm25IndexService.Bm25Match("doc2", 0, "光伏逆变器", 1.2f)
        );

        // Mock 行为
        when(embeddingService.embed(anyString())).thenReturn(Mono.just(new float[]{0.1f, 0.2f}));
        when(vectorStoreClient.search(any(), anyInt())).thenReturn(Mono.just(vectorResults));
        when(bm25IndexService.search(anyString(), anyInt())).thenReturn(bm25Matches);

        // 执行测试
        StepVerifier.create(hybridSearchService.search("LED面板灯", 5))
                .assertNext(results -> {
                    // 验证结果
                    assertThat(results).isNotEmpty();
                    // 验证 RRF 融合后的分数
                    assertThat(results.get(0).score()).isGreaterThan(0);
                })
                .verifyComplete();
    }

    @Test
    void testFallbackToVectorSearch() {
        // 关闭混合检索
        when(hybridProperties.isEnabled()).thenReturn(false);

        // 准备测试数据
        List<SearchMatch> vectorResults = List.of(
                createSearchMatch("doc1", 0, "LED面板灯介绍", 0.8)
        );

        // Mock 行为
        when(embeddingService.embed(anyString())).thenReturn(Mono.just(new float[]{0.1f, 0.2f}));
        when(vectorStoreClient.search(any(), anyInt())).thenReturn(Mono.just(vectorResults));

        // 执行测试
        StepVerifier.create(hybridSearchService.search("LED面板灯", 5))
                .assertNext(results -> {
                    // 验证结果
                    assertThat(results).hasSize(1);
                    assertThat(results.get(0).documentId()).isEqualTo("doc1");
                })
                .verifyComplete();

        // 验证没有调用 BM25
        verify(bm25IndexService, never()).search(anyString(), anyInt());
    }

    @Test
    void testVectorSearchFailure() {
        // 准备测试数据
        List<Bm25IndexService.Bm25Match> bm25Matches = List.of(
                new Bm25IndexService.Bm25Match("doc1", 0, "LED面板灯介绍", 1.5f)
        );

        // Mock 行为：向量检索失败
        when(embeddingService.embed(anyString())).thenReturn(Mono.just(new float[]{0.1f, 0.2f}));
        when(vectorStoreClient.search(any(), anyInt())).thenReturn(Mono.error(new RuntimeException("向量检索失败")));
        when(bm25IndexService.search(anyString(), anyInt())).thenReturn(bm25Matches);

        // 执行测试
        StepVerifier.create(hybridSearchService.search("LED面板灯", 5))
                .assertNext(results -> {
                    // 验证降级到 BM25 结果
                    assertThat(results).isNotEmpty();
                })
                .verifyComplete();
    }

    @Test
    void testBm25SearchFailure() {
        // 准备测试数据
        List<SearchMatch> vectorResults = List.of(
                createSearchMatch("doc1", 0, "LED面板灯介绍", 0.8)
        );

        // Mock 行为：BM25 检索失败
        when(embeddingService.embed(anyString())).thenReturn(Mono.just(new float[]{0.1f, 0.2f}));
        when(vectorStoreClient.search(any(), anyInt())).thenReturn(Mono.just(vectorResults));
        when(bm25IndexService.search(anyString(), anyInt())).thenThrow(new RuntimeException("BM25 检索失败"));

        // 执行测试
        StepVerifier.create(hybridSearchService.search("LED面板灯", 5))
                .assertNext(results -> {
                    // 验证降级到向量结果
                    assertThat(results).hasSize(1);
                    assertThat(results.get(0).documentId()).isEqualTo("doc1");
                })
                .verifyComplete();
    }

    @Test
    void testEmptyQuery() {
        // Mock 行为：即使是空查询，也需要 mock embed 返回
        when(embeddingService.embed(anyString())).thenReturn(Mono.just(new float[]{0.1f, 0.2f}));

        // 执行测试
        StepVerifier.create(hybridSearchService.search("", 5))
                .assertNext(results -> {
                    // 验证结果为空
                    assertThat(results).isEmpty();
                })
                .verifyComplete();
    }

    /**
     * 创建测试用的 SearchMatch
     */
    private SearchMatch createSearchMatch(String documentId, int chunkIndex, String text, double score) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("title", "测试文档");
        return new SearchMatch(text, score, documentId, chunkIndex, metadata);
    }
}
