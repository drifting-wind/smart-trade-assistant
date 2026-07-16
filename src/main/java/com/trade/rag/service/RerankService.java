package com.trade.rag.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.trade.gateway.AiGatewayProperties;
import com.trade.rag.dto.SearchResultDto.SearchMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Rerank 重排序服务 —— 调用 DashScope qwen3-rerank API 对候选文档二次精准排序。
 *
 * 官方文档：https://help.aliyun.com/zh/model-studio/rerank-api
 * 接口：POST https://dashscope.aliyuncs.com/compatible-api/v1/reranks
 *
 * qwen3-rerank 使用 OpenAI 兼容格式：
 *   - 请求体扁平化：query、documents、top_n、instruct 与 model 同级
 *   - 响应体扁平化：results 直接在根级别，不含 output 包裹
 *
 * 降级策略：
 * - Rerank 关闭时，直接返回候选结果
 * - 候选数量 <= 1 时，无需重排序
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
     * @param candidates 候选文档（来自混合检索的 Top-K）
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

        // ========================================
        // qwen3-rerank 使用 OpenAI 兼容格式（扁平化结构）
        // 官方文档：https://help.aliyun.com/zh/model-studio/rerank-api
        // ========================================

        // 清洗文档文本：去掉头尾空白/换行
        List<String> documents = candidates.stream()
                .map(m -> m.text().trim().replaceAll("^\\n+", "").replaceAll("\\n+$", ""))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());

        if (documents.size() <= 1) {
            log.debug("清洗后文档数量不足 {} 条，跳过 Rerank", documents.size());
            return Mono.just(candidates);
        }

        // 构建请求体（扁平化，无 input 包裹）
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", rerankConfig.getModel());
        requestBody.put("query", query);
        requestBody.put("documents", documents);
        requestBody.put("top_n", Math.min(rerankConfig.getTopN() > 0 ? rerankConfig.getTopN() : 5, documents.size()));
        requestBody.put("instruct", "Given a web search query, retrieve relevant passages that answer the query.");

        // 使用 Jackson ObjectMapper 序列化为 JSON 字符串（确保中文正确编码）
        String jsonBody;
        try {
            jsonBody = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(requestBody);
        } catch (Exception e) {
            log.error("序列化 Rerank 请求失败: {}", e.getMessage());
            return Mono.just(candidates);
        }

        // 端点：compatible-api/v1/reranks（注意：base-url 是 https://dashscope.aliyuncs.com/api/v1）
        // 需要去掉 /api/v1 后缀，因为 compatible-api 是独立路径
        String baseUrl = rerankConfig.getBaseUrl();
        if (baseUrl.endsWith("/api/v1")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - "/api/v1".length());
        }
        String uri = baseUrl + "/compatible-api/v1/reranks";

        log.info("📤 Rerank 请求: uri={}, model={}, top_n={}, docs_count={}",
                uri, rerankConfig.getModel(),
                rerankConfig.getTopN() > 0 ? rerankConfig.getTopN() : 5,
                documents.size());

        return webClient.post()
                .uri(uri)
                .header("Authorization", "Bearer " + rerankConfig.getApiKey())
                .header("Content-Type", "application/json")
                .bodyValue(jsonBody)
                .retrieve()
                .bodyToMono(RerankApiResponse.class)
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
            RerankApiResponse response,
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

        // 按 Rerank 分数降序排列，取 Top-K
        List<SearchMatch> reranked = response.results().stream()
                .filter(Objects::nonNull)
                .filter(r -> r.index() != null && indexMap.containsKey(r.index()))
                .sorted(Comparator.comparingDouble(RerankResult::relevanceScore).reversed())
                .limit(topK)
                .map(r -> {
                    SearchMatch original = indexMap.get(r.index());
                    return new SearchMatch(
                            original.text(),
                            r.relevanceScore(),  // 使用 Rerank 分数作为新分数
                            original.documentId(),
                            original.chunkIndex(),
                            original.metadata()
                    );
                })
                .collect(Collectors.toList());

        log.info("✅ Rerank 完成: 输入 {} 条, 输出 {} 条", candidates.size(), reranked.size());
        return reranked;
    }

    // ==================== qwen3-rerank API 数据模型 ====================
    // 参考：https://help.aliyun.com/zh/model-studio/rerank-api

    /**
     * qwen3-rerank 请求体（扁平化结构）。
     * 格式：
     * {
     *   "model": "qwen3-rerank",
     *   "query": "...",
     *   "documents": ["...", "..."],
     *   "top_n": 5,
     *   "instruct": "Given a web search query, retrieve relevant passages that answer the query."
     * }
     */

    /**
     * qwen3-rerank 响应体（扁平化结构）。
     * 格式：
     * {
     *   "object": "list",
     *   "results": [
     *     {"index": 0, "relevance_score": 0.93},
     *     {"index": 2, "relevance_score": 0.34}
     *   ],
     *   "model": "qwen3-rerank",
     *   "id": "...",
     *   "usage": {"total_tokens": 79}
     * }
     */
    private record RerankApiResponse(
            List<RerankResult> results
    ) {}

    /**
     * qwen3-rerank 单条结果。
     */
    private record RerankResult(
            Integer index,
            @JsonProperty("relevance_score") Double relevanceScore
    ) {}
}
