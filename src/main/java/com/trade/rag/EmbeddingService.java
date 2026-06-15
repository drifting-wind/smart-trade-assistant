package com.trade.rag;

import com.trade.config.AiGatewayProperties;
import com.trade.exception.VectorStoreException;
import com.trade.monitor.EmbeddingCostMonitor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedding 服务 —— 调用 OpenAI 兼容的 /v1/embeddings 接口，将文本转换为向量。
 *
 * 技术栈：
 * - Spring WebFlux WebClient：异步 HTTP 客户端
 * - Reactor Mono + Retry：响应式重试
 * - Jackson：JSON 解析
 *
 * 支持的 Embedding API（OpenAI 兼容格式）：
 * - Alibaba DashScope: POST https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings
 *   - 模型: text-embedding-v3（默认 1024 维，中文效果好）
 * - DeepSeek: POST https://api.deepseek.com/v1/embeddings
 *   - 模型: deepseek-chat
 * - OpenAI: POST https://api.openai.com/v1/embeddings
 *   - 模型: text-embedding-3-small（1536 维）
 *
 * 请求格式（OpenAI 兼容）：
 * {
 *   "model": "text-embedding-v3",
 *   "input": ["文本1", "文本2"],
 *   "encoding_format": "float"
 * }
 *
 * 响应格式：
 * {
 *   "data": [
 *     {"embedding": [0.0023, -0.0093, ...], "index": 0}
 *   ]
 * }
 *
 * 注意：Embedding API 是同步调用，不涉及流式处理。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final WebClient primaryWebClient;
    private final WebClient fallbackWebClient;
    private final ObjectMapper objectMapper;
    private final AiGatewayProperties.Rag.Embedding primaryConfig;
    private final AiGatewayProperties.Rag.Embedding fallbackConfig;
    private final int dimension;
    private final MeterRegistry meterRegistry;
    private final EmbeddingCostMonitor costMonitor;
    private final RedisTemplate<String, Object> redisTemplate;

    // 降级熔断器：记录主模型连续失败次数
    private final ConcurrentHashMap<String, Integer> failureCounters = new ConcurrentHashMap<>();
    private static final int FAILURE_THRESHOLD = 3; // 连续失败 3 次触发降级
    private static final long CIRCUIT_RESET_TIME_MS = 60_000; // 熔断 60 秒后尝试恢复

    public EmbeddingService(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            AiGatewayProperties properties,
            MeterRegistry meterRegistry,
            EmbeddingCostMonitor costMonitor,
            RedisTemplate<String, Object> redisTemplate
    ) {
        this.primaryConfig = properties.getRag().getEmbedding();
        this.fallbackConfig = properties.getRag().getFallbackEmbedding();
        this.dimension = properties.getRag().getDimension();
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.costMonitor = costMonitor;
        this.redisTemplate = redisTemplate;

        // 主模型 WebClient
        this.primaryWebClient = webClientBuilder
                .baseUrl(primaryConfig.getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        // 降级模型 WebClient
        this.fallbackWebClient = webClientBuilder
                .baseUrl(fallbackConfig.getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * 将单条文本转换为向量。
     *
     * 生产级流程：
     * 0. 先查 Redis 缓存（key: embedding:md5(text)），命中则直接返回
     * 1. 优先调用主模型（text-embedding-v3）
     * 2. 主模型失败时，自动降级到 Mock 服务
     * 3. 熔断机制：连续失败 3 次后，直接走降级
     * 4. 监控指标：记录每次调用的耗时和成功率
     * 5. 成功后写入 Redis 缓存（永久有效）
     *
     * @param text 要转换的文本
     * @return 浮点数组表示的向量
     */
    public Mono<float[]> embed(String text) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String metricsKey = "embedding.embed";

        // 生成缓存 key
        String cacheKey = generateCacheKey(text);

        // 先查缓存
        return getFromCache(cacheKey)
                .switchIfEmpty(
                    // 未命中，调用 API
                    embedWithPrimary(text)
                            .doOnSuccess(embedding -> {
                                sample.stop(Timer.builder(metricsKey)
                                        .tag("model", primaryConfig.getModel())
                                        .tag("status", "success")
                                        .register(meterRegistry));
                                log.debug("✅ 主模型 Embedding 成功: model={}, dim={}", primaryConfig.getModel(), embedding.length);
                            })
                            .onErrorResume(error -> {
                                // 主模型失败，记录降级
                                meterRegistry.counter(metricsKey + ".fallback",
                                        "model", primaryConfig.getModel(),
                                        "reason", error.getMessage()).increment();

                                log.warn("⚠️ 主模型失败，降级到 Mock: {}", error.getMessage());
                                return embedWithFallback(text);
                            })
                            .doOnError(error -> {
                                sample.stop(Timer.builder(metricsKey)
                                        .tag("model", "fallback")
                                        .tag("status", "error")
                                        .register(meterRegistry));
                                log.error("❌ Embedding 失败: {}", error.getMessage());
                            })
                            // 成功后写入缓存
                            .doOnSuccess(embedding -> saveToCache(cacheKey, embedding))
                );
    }

    /**
     * 调用主模型（text-embedding-v3）
     */
    private Mono<float[]> embedWithPrimary(String text) {
        // 检查熔断器
        if (isCircuitOpen("primary")) {
            return Mono.error(new VectorStoreException("主模型熔断中，请稍后重试"));
        }

        long startTime = System.currentTimeMillis();

        return primaryWebClient.post()
                .uri(primaryConfig.getPath())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + primaryConfig.getApiKey())
                .bodyValue(payload(List.of(text), primaryConfig))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(primaryConfig.getTimeout())
                .retryWhen(Retry.backoff(primaryConfig.getMaxRetries(), Duration.ofSeconds(1))
                        .filter(this::retryable))
                .map(this::extractEmbedding)
                .doOnNext(embedding -> {
                    validateDimension(embedding, primaryConfig);
                    // 记录成本
                    int tokenCount = estimateTokenCount(text);
                    long duration = System.currentTimeMillis() - startTime;
                    costMonitor.recordUsage(primaryConfig.getModel(), tokenCount, duration);
                })
                .doOnSuccess(embedding -> recordSuccess("primary"))
                .doOnError(error -> recordFailure("primary", error))
                .onErrorMap(error -> new VectorStoreException("主模型 Embedding 失败: " + error.getMessage(), error));
    }

    /**
     * 调用降级模型（mock-embedding）
     */
    private Mono<float[]> embedWithFallback(String text) {
        long startTime = System.currentTimeMillis();

        return fallbackWebClient.post()
                .uri(fallbackConfig.getPath())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fallbackConfig.getApiKey())
                .bodyValue(payload(List.of(text), fallbackConfig))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(fallbackConfig.getTimeout())
                .retryWhen(Retry.backoff(fallbackConfig.getMaxRetries(), Duration.ofMillis(500))
                        .filter(this::retryable))
                .map(this::extractEmbedding)
                .doOnNext(embedding -> {
                    validateDimension(embedding, fallbackConfig);
                    // 记录成本
                    int tokenCount = estimateTokenCount(text);
                    long duration = System.currentTimeMillis() - startTime;
                    costMonitor.recordUsage(fallbackConfig.getModel(), tokenCount, duration);
                })
                .onErrorMap(error -> new VectorStoreException("降级模型也失败: " + error.getMessage(), error));
    }

    /**
     * 熔断器：检查是否应该熔断
     */
    private boolean isCircuitOpen(String model) {
        Integer failures = failureCounters.get(model);
        return failures != null && failures >= FAILURE_THRESHOLD;
    }

    /**
     * 记录成功，重置熔断器
     */
    private void recordSuccess(String model) {
        failureCounters.remove(model);
    }

    /**
     * 记录失败，递增熔断器计数
     */
    private void recordFailure(String model, Throwable error) {
        failureCounters.merge(model, 1, Integer::sum);
        log.warn("⚠️ {} 模型连续失败次数: {}", model, failureCounters.get(model));

        // 定时重置熔断器
        if (failureCounters.get(model) >= FAILURE_THRESHOLD) {
            new java.util.Timer().schedule(new java.util.TimerTask() {
                @Override
                public void run() {
                    failureCounters.remove(model);
                    log.info("🔄 {} 熔断器重置，尝试恢复", model);
                }
            }, CIRCUIT_RESET_TIME_MS);
        }
    }

    /**
     * 批量将多条文本转换为向量（生产级实现）
     *
     * 优势：减少 HTTP 请求次数，提升吞吐量。
     * 注意：Embedding API 通常支持批量输入，但单次请求有 token 限制。
     *
     * @param texts 要转换的文本列表
     * @return 向量列表，顺序与输入一致
     */
    public Mono<List<float[]>> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Mono.just(Collections.emptyList());
        }

        Timer.Sample sample = Timer.start(meterRegistry);

        // 分批处理，每批最多 8 条（避免超过 API token 限制）
        int batchSize = 8;
        List<Mono<List<float[]>>> batchMonos = new ArrayList<>();

        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
            batchMonos.add(embedBatchWithFallback(batch));
        }

        return Mono.zip(batchMonos, results -> {
            List<float[]> allEmbeddings = new ArrayList<>();
            for (Object result : results) {
                allEmbeddings.addAll((List<float[]>) result);
            }
            return allEmbeddings;
        }).doOnSuccess(embeddings -> {
            sample.stop(Timer.builder("embedding.embedAll")
                    .tag("batchCount", String.valueOf(batchMonos.size()))
                    .tag("totalTexts", String.valueOf(texts.size()))
                    .register(meterRegistry));
        });
    }

    /**
     * 批量 Embedding（带降级）
     */
    private Mono<List<float[]>> embedBatchWithFallback(List<String> texts) {
        return embedBatchPrimary(texts)
                .doOnSuccess(embeddings ->
                        log.debug("✅ 批量 Embedding 成功: count={}", embeddings.size()))
                .onErrorResume(error -> {
                    meterRegistry.counter("embedding.batch.fallback",
                            "reason", error.getMessage()).increment();
                    log.warn("⚠️ 主模型批量失败，降级: {}", error.getMessage());
                    return embedBatchFallback(texts);
                });
    }

    /**
     * 主模型批量 Embedding
     */
    private Mono<List<float[]>> embedBatchPrimary(List<String> texts) {
        if (isCircuitOpen("primary")) {
            return Mono.error(new VectorStoreException("主模型熔断中"));
        }

        long startTime = System.currentTimeMillis();

        return primaryWebClient.post()
                .uri(primaryConfig.getPath())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + primaryConfig.getApiKey())
                .bodyValue(payload(texts, primaryConfig))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(primaryConfig.getTimeout().multipliedBy(2)) // 批量给更多时间
                .retryWhen(Retry.backoff(primaryConfig.getMaxRetries(), Duration.ofSeconds(1))
                        .filter(this::retryable)
                        .doBeforeRetry(retrySignal ->
                                log.warn("⚠️ 主模型批量重试 {}/{}", retrySignal.totalRetries() + 1, primaryConfig.getMaxRetries())))
                .map(root -> parseBatchResponse(root, primaryConfig))
                .doOnSuccess(embeddings -> {
                    recordSuccess("primary");
                    // 记录成本
                    int totalTokens = texts.stream().mapToInt(this::estimateTokenCount).sum();
                    long duration = System.currentTimeMillis() - startTime;
                    costMonitor.recordUsage(primaryConfig.getModel(), totalTokens, duration);
                })
                .doOnError(error -> recordFailure("primary", error));
    }

    /**
     * 降级模型批量 Embedding
     */
    private Mono<List<float[]>> embedBatchFallback(List<String> texts) {
        long startTime = System.currentTimeMillis();

        return fallbackWebClient.post()
                .uri(fallbackConfig.getPath())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fallbackConfig.getApiKey())
                .bodyValue(payload(texts, fallbackConfig))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(fallbackConfig.getTimeout().multipliedBy(2))
                .retryWhen(Retry.backoff(fallbackConfig.getMaxRetries(), Duration.ofMillis(500))
                        .filter(this::retryable))
                .map(root -> parseBatchResponse(root, fallbackConfig))
                .doOnSuccess(embeddings -> {
                    // 记录成本
                    int totalTokens = texts.stream().mapToInt(this::estimateTokenCount).sum();
                    long duration = System.currentTimeMillis() - startTime;
                    costMonitor.recordUsage(fallbackConfig.getModel(), totalTokens, duration);
                });
    }

    /**
     * 解析批量响应
     */
    private List<float[]> parseBatchResponse(JsonNode root, AiGatewayProperties.Rag.Embedding config) {
        // 检查是否有错误响应
        JsonNode errorNode = root.path("error");
        if (!errorNode.isMissingNode()) {
            String errorMsg = errorNode.path("message").asText("未知错误");
            throw new VectorStoreException("Embedding API 错误: " + errorMsg);
        }

        List<float[]> embeddings = new ArrayList<>();
        JsonNode data = root.path("data");
        if (data.isArray()) {
            for (JsonNode item : data) {
                JsonNode embeddingNode = item.path("embedding");
                float[] embedding = new float[embeddingNode.size()];
                for (int i = 0; i < embeddingNode.size(); i++) {
                    embedding[i] = (float) embeddingNode.get(i).asDouble();
                }
                embeddings.add(embedding);
            }
        }
        return embeddings;
    }

    /**
     * 构造 Embedding API 请求体。
     *
     * 格式：
     * {
     *   "model": "text-embedding-v3",
     *   "input": ["文本1", "文本2"],
     *   "encoding_format": "float"
     * }
     */
    private Map<String, Object> payload(List<String> texts, AiGatewayProperties.Rag.Embedding config) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        body.put("input", texts);
        body.put("encoding_format", config.getEncodingFormat());
        return body;
    }

    /**
     * 验证向量维度
     */
    private void validateDimension(float[] embedding, AiGatewayProperties.Rag.Embedding config) {
        if (embedding.length != dimension) {
            log.warn("⚠️ Embedding 维度不匹配: model={}, expected={}, actual={}",
                    config.getModel(), dimension, embedding.length);
            meterRegistry.counter("embedding.dimension_mismatch",
                    "model", config.getModel()).increment();
        }
    }

    /**
     * 从 API 响应中提取第一条 Embedding 向量。
     */
    private float[] extractEmbedding(JsonNode root) {
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new VectorStoreException("Embedding API 返回空数据");
        }

        JsonNode firstItem = data.get(0);
        JsonNode embeddingNode = firstItem.path("embedding");
        if (embeddingNode.isMissingNode() || !embeddingNode.isArray()) {
            throw new VectorStoreException("Embedding API 返回格式错误");
        }

        float[] embedding = new float[embeddingNode.size()];
        for (int i = 0; i < embeddingNode.size(); i++) {
            embedding[i] = (float) embeddingNode.get(i).asDouble();
        }
        return embedding;
    }

    /**
     * 判断异常是否可重试 —— 401 鉴权失败不重试，其他异常（超时、5xx）可重试。
     */
    private boolean retryable(Throwable error) {
        String message = error.getMessage();
        return message == null || !message.contains("401");
    }

    /**
     * 健康检查 —— 用于 Actuator 监控
     */
    public Map<String, Object> health() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("primaryModel", primaryConfig.getModel());
        health.put("fallbackModel", fallbackConfig.getModel());
        health.put("dimension", dimension);

        // 检查熔断器状态
        Integer primaryFailures = failureCounters.get("primary");
        health.put("primaryCircuitOpen", primaryFailures != null && primaryFailures >= FAILURE_THRESHOLD);
        health.put("primaryFailures", primaryFailures != null ? primaryFailures : 0);

        return health;
    }

    /**
     * 估算文本的 token 数（粗略估计）。
     *
     * 规则：
     * - 英文：1 token ≈ 4 字符
     * - 中文：1 token ≈ 1.5 字符
     *
     * @param text 文本
     * @return 估算的 token 数
     */
    private int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        // 简单估算：中文字符数 * 1.5 + 英文字符数 / 4
        long chineseChars = text.chars().filter(c -> c > 127).count();
        long englishChars = text.chars().filter(c -> c <= 127).count();

        return (int) (chineseChars * 1.5 + englishChars / 4);
    }

    /**
     * 生成 Embedding 缓存 key。
     *
     * 格式：embedding:md5(text)
     * 使用 MD5 保证 key 长度固定且唯一。
     *
     * @param text 文本
     * @return 缓存 key
     */
    private String generateCacheKey(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("embedding:");
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 一定存在，不会走到这里
            return "embedding:" + text.hashCode();
        }
    }

    /**
     * 从 Redis 缓存获取 Embedding 向量。
     *
     * @param cacheKey 缓存 key
     * @return 缓存的向量，未命中返回空 Mono
     */
    @SuppressWarnings("unchecked")
    private Mono<float[]> getFromCache(String cacheKey) {
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                log.debug("✅ Embedding 缓存命中: {}", cacheKey);
                meterRegistry.counter("embedding.cache.hit").increment();
                if (cached instanceof float[]) {
                    return Mono.just((float[]) cached);
                } else if (cached instanceof List) {
                    // 反序列化时可能变成 List<Number>
                    List<Number> list = (List<Number>) cached;
                    float[] embedding = new float[list.size()];
                    for (int i = 0; i < list.size(); i++) {
                        embedding[i] = list.get(i).floatValue();
                    }
                    return Mono.just(embedding);
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ 读取 Embedding 缓存失败: {}", e.getMessage());
        }
        meterRegistry.counter("embedding.cache.miss").increment();
        return Mono.empty();
    }

    /**
     * 将 Embedding 向量写入 Redis 缓存。
     *
     * 缓存策略：永久有效（Embedding 向量不会变化）
     *
     * @param cacheKey 缓存 key
     * @param embedding 向量
     */
    private void saveToCache(String cacheKey, float[] embedding) {
        try {
            redisTemplate.opsForValue().set(cacheKey, embedding);
            log.debug("✅ Embedding 已缓存: {}", cacheKey);
        } catch (Exception e) {
            log.warn("⚠️ 写入 Embedding 缓存失败: {}", e.getMessage());
        }
    }
}
