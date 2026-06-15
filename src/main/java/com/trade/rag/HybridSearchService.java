package com.trade.rag;

import com.trade.config.AiGatewayProperties;
import com.trade.rag.dto.SearchResultDto.SearchMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合检索编排服务 —— 并行执行向量检索 + BM25 关键词检索，RRF 加权融合。
 *
 * 核心流程：
 * 1. 并行执行向量检索（Milvus）和 BM25 关键词检索（内存 Lucene）
 * 2. 使用 RRF（Reciprocal Rank Fusion）算法加权融合两种结果
 * 3. 返回融合后的 Top-K 结果
 *
 * RRF 公式：
 * score = Σ(weight_i × 1 / (k + rank_i))
 * 向量检索权重默认 0.7，BM25 权重默认 0.3
 *
 * 降级策略：
 * - 混合检索关闭时，降级为纯向量检索
 * - BM25 检索失败时，仅返回向量检索结果
 * - 向量检索失败时，仅返回 BM25 检索结果
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    /** RRF 公式中的 k 值，防止排名第一的结果权重过大 */
    private static final double RRF_K = 60.0;

    private final EmbeddingService embeddingService;
    private final MilvusVectorStoreClient vectorStoreClient;
    private final Bm25IndexService bm25IndexService;
    private final AiGatewayProperties properties;

    public HybridSearchService(
            EmbeddingService embeddingService,
            MilvusVectorStoreClient vectorStoreClient,
            Bm25IndexService bm25IndexService,
            AiGatewayProperties properties
    ) {
        this.embeddingService = embeddingService;
        this.vectorStoreClient = vectorStoreClient;
        this.bm25IndexService = bm25IndexService;
        this.properties = properties;
    }

    /**
     * 混合检索入口 —— 并行执行向量检索 + BM25 检索，RRF 融合。
     *
     * @param query 用户查询
     * @param topK  返回的最大结果数
     * @return 融合后的搜索结果
     */
    public Mono<List<SearchMatch>> search(String query, int topK) {
        AiGatewayProperties.Rag.Hybrid hybridConfig = properties.getRag().getHybrid();

        // 降级：混合检索关闭时，使用纯向量检索
        if (!hybridConfig.isEnabled()) {
            log.debug("混合检索已关闭，降级为纯向量检索");
            return vectorSearch(query, topK);
        }

        int expandedTopK = topK * 2; // 扩大检索范围，留出融合空间
        double vectorWeight = hybridConfig.getVectorWeight();
        double bm25Weight = hybridConfig.getBm25Weight();

        log.debug("🔀 开始混合检索: query='{}', topK={}, vectorWeight={}, bm25Weight={}",
                query, topK, vectorWeight, bm25Weight);

        // 并行执行向量检索和 BM25 检索
        Mono<List<SearchMatch>> vectorMono = vectorSearch(query, expandedTopK)
                .onErrorResume(e -> {
                    log.warn("⚠️ 向量检索失败，降级为空结果: {}", e.getMessage());
                    return Mono.just(List.of());
                });

        Mono<List<SearchMatch>> bm25Mono = bm25Search(query, expandedTopK)
                .onErrorResume(e -> {
                    log.warn("⚠️ BM25 检索失败，降级为空结果: {}", e.getMessage());
                    return Mono.just(List.of());
                });

        return Mono.zip(vectorMono, bm25Mono)
                .map(tuple -> {
                    List<SearchMatch> vectorResults = tuple.getT1();
                    List<SearchMatch> bm25Results = tuple.getT2();

                    log.debug("📊 混合检索中间结果: 向量 {} 条, BM25 {} 条",
                            vectorResults.size(), bm25Results.size());

                    // RRF 融合
                    List<SearchMatch> fused = rrfFuse(vectorResults, bm25Results, topK, vectorWeight, bm25Weight);

                    log.info("✅ 混合检索完成: 融合后 {} 条结果", fused.size());
                    return fused;
                });
    }

    /**
     * 向量检索 —— 将查询转为向量后在 Milvus 中搜索。
     */
    private Mono<List<SearchMatch>> vectorSearch(String query, int topK) {
        return embeddingService.embed(query)
                .flatMap(embedding -> vectorStoreClient.search(embedding, topK));
    }

    /**
     * BM25 关键词检索 —— 在内存 Lucene 索引中搜索。
     */
    private Mono<List<SearchMatch>> bm25Search(String query, int topK) {
        return Mono.fromCallable(() -> {
            List<Bm25IndexService.Bm25Match> bm25Matches = bm25IndexService.search(query, topK);

            // 将 BM25Match 转换为 SearchMatch（分数归一化到 0~1）
            return bm25Matches.stream()
                    .map(bm -> new SearchMatch(
                            bm.content(),
                            bm.score(), // BM25 分数
                            bm.documentId(),
                            bm.chunkIndex(),
                            Collections.emptyMap() // BM25 索引不存储元数据
                    ))
                    .collect(Collectors.toList());
        });
    }

    /**
     * RRF（Reciprocal Rank Fusion）加权融合。
     *
     * 算法：
     * 1. 对向量检索结果，按排名计算得分：vectorWeight × 1 / (k + rank)
     * 2. 对 BM25 检索结果，按排名计算得分：bm25Weight × 1 / (k + rank)
     * 3. 合并相同 (documentId, chunkIndex) 的结果，得分相加
     * 4. 按最终得分降序排列，取 Top-K
     *
     * @param vectorResults  向量检索结果
     * @param bm25Results    BM25 检索结果
     * @param topK           最终返回数量
     * @param vectorWeight   向量权重
     * @param bm25Weight     BM25 权重
     * @return 融合后的结果
     */
    private List<SearchMatch> rrfFuse(
            List<SearchMatch> vectorResults,
            List<SearchMatch> bm25Results,
            int topK,
            double vectorWeight,
            double bm25Weight
    ) {
        // 使用 (documentId + chunkIndex) 作为唯一键，累计得分
        Map<String, FusedMatch> fusedMap = new HashMap<>();

        // 处理向量检索结果
        for (int i = 0; i < vectorResults.size(); i++) {
            SearchMatch match = vectorResults.get(i);
            String key = match.documentId() + ":" + match.chunkIndex();
            double score = vectorWeight * (1.0 / (RRF_K + i + 1));

            fusedMap.computeIfAbsent(key, k -> new FusedMatch(match))
                    .addScore(score);
        }

        // 处理 BM25 检索结果
        for (int i = 0; i < bm25Results.size(); i++) {
            SearchMatch match = bm25Results.get(i);
            String key = match.documentId() + ":" + match.chunkIndex();
            double score = bm25Weight * (1.0 / (RRF_K + i + 1));

            fusedMap.computeIfAbsent(key, k -> new FusedMatch(match))
                    .addScore(score);
        }

        // 按融合得分降序排列，取 Top-K
        return fusedMap.values().stream()
                .sorted(Comparator.comparingDouble(FusedMatch::getScore).reversed())
                .limit(topK)
                .map(fused -> new SearchMatch(
                        fused.getMatch().text(),
                        fused.getScore(),
                        fused.getMatch().documentId(),
                        fused.getMatch().chunkIndex(),
                        fused.getMatch().metadata()
                ))
                .collect(Collectors.toList());
    }

    /**
     * 内部类：用于 RRF 融合过程中的中间结果。
     */
    private static class FusedMatch {
        private final SearchMatch match;
        private double score;

        FusedMatch(SearchMatch match) {
            this.match = match;
            this.score = match.score(); // 保留原始分数作为初始值
        }

        void addScore(double delta) {
            this.score += delta;
        }

        double getScore() {
            return score;
        }

        SearchMatch getMatch() {
            return match;
        }
    }
}
