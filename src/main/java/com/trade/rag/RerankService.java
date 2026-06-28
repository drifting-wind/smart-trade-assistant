package com.trade.rag;

import com.trade.config.AiGatewayProperties;
import com.trade.rag.dto.SearchResultDto.SearchMatch;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Rerank 重排序服务 —— 调用 DashScope text-rerank-v2 API 对候选文档重排序。
 *
 * 职责：
 * 1. 接收初步检索结果（向量/BM25 混合检索的 Top-K）
 * 2. 调用 DashScope Rerank API 计算 query 与每个文档的相关性
 * 3. 按 Rerank 分数降序返回重排序后的结果
 *
 * API 文档：https://dashscope.aliyun.com/
 * 接口：POST /api/v1/services/rerank/text-rerank
 *
 * 降级策略：
 * - Rerank 关闭时，直接返回候选结果
 * - 候选数量 ≤ 1 时，无需重排序
 * - API 调用失败时，降级返回原始候选结果
 */
@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    private final WebClient webClient;
    private final AiGatewayProperties properties;

    public RerankService(WebClient.Builder webClientBuilder, AiGatewayProperties properties) {
        this.webClient = webClientBuilder.build();
        this.properties = properties;
    }

    /**
     * 对候选文档进行 Rerank 重排序。
     *
     * @param query      用户查询
     * @param candidates 候选文档（来自混合检索）
     * @return 重排序后的文档列表
     */
    public Mono<List<SearchMatch>> rerank(String query, List<SearchMatch> candidates) {
        AiGatewayProperties.Rag.Rerank rerankConfig = properties.getRag().getRerank();

        // 降级：Rerank 关闭或候选数量不足
        if (!rerankConfig.isEnabled() || candidates.size() <= 1) {
            log.debug("Rerank 已关闭或候选数量不足，跳过重排序");
            return Mono.just(candidates);
        }

        log.debug("🔄 开始 Rerank: query='{}', candidates={}", query, candidates.size());

        // 构建 Rerank 请求（DashScope API 格式）
        RerankRequest request = new RerankRequest(
                rerankConfig.getModel(),
                new RerankInput(query, candidates.stream().map(SearchMatch::text).toList()),
                new RerankParameters(rerankConfig.getTopN() > 0 ? rerankConfig.getTopN() : 5)
        );

        String uri = rerankConfig.getBaseUrl() + "/services/rerank/text-rerank";

        return webClient.post()
                .uri(uri)
                .header("Authorization", "Bearer " + rerankConfig.getApiKey())
                .header("Content-Type", "application/json")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(RerankResponse.class)
                .map(response -> mapRerankedResults(response, candidates, rerankConfig.getTopK()))
                .onErrorResume(e -> {
                    log.error("❌ Rerank API 调用失败，降级返回原始结果: {}", e.getMessage());
                    return Mono.just(candidates);
                });
    }

    /**
     * 将 Rerank API 响应映射回 SearchMatch 列表。
     */
    private List<SearchMatch> mapRerankedResults(
            RerankResponse response,
            List<SearchMatch> candidates,
            int topK
    ) {
        if (response == null || response.results() == null || response.results().isEmpty()) {
            log.warn("⚠️ Rerank 返回空结果，降级返回原始候选");
            return candidates;
        }

        // 构建 index → SearchMatch 的映射
        Map<Integer, SearchMatch> indexMap = new HashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            indexMap.put(i, candidates.get(i));
        }

        // 按 Rerank 结果重新排序
        List<SearchMatch> reranked = response.results().stream()
                .filter(Objects::nonNull)
                .filter(r -> r.index() != null && indexMap.containsKey(r.index()))
                .sorted(Comparator.comparingDouble(RerankResult::score).reversed())
                .limit(topK)
                .map(r -> {
                    SearchMatch original = indexMap.get(r.index());
                    return new SearchMatch(
                            original.text(),
                            r.score(), // 使用 Rerank 分数作为新分数
                            original.documentId(),
                            original.chunkIndex(),
                            original.metadata()
                    );
                })
                .collect(Collectors.toList());

        log.info("✅ Rerank 完成: 输入 {} 条, 输出 {} 条", candidates.size(), reranked.size());
        return reranked;
    }

    // ==================== Rerank API 数据模型 ====================

    /**
     * DashScope Rerank 请求体。
     * 格式: {"model": "gte-rerank", "input": {"query": "...", "documents": [...]}, "parameters": {"top_n": 5}}
     */
    private record RerankRequest(
            String model,
            @JsonProperty("input") RerankInput input,
            @JsonProperty("parameters") RerankParameters parameters
    ) {}

    private record RerankInput(
            String query,
            @JsonProperty("documents") List<String> documents
    ) {}

    private record RerankParameters(
            @JsonProperty("top_n") int topN
    ) {}

    /**
     * DashScope Rerank 响应体。
     */
    private record RerankResponse(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("output") RerankOutput output
    ) {
        List<RerankResult> results() {
            return output != null ? output.results() : List.of();
        }
    }

    private record RerankOutput(
            @JsonProperty("results") List<RerankResult> results
    ) {}

    /**
     * Rerank 单条结果。
     */
    private record RerankResult(
            @JsonProperty("index") Integer index,
            @JsonProperty("relevance_score") Double score,
            @JsonProperty("document") RerankDocument document
    ) {}

    private record RerankDocument(
            @JsonProperty("text") String text
    ) {}
}
