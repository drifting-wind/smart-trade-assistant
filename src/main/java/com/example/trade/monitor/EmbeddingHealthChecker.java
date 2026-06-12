package com.example.trade.monitor;

import com.example.trade.config.AiGatewayProperties;
import com.example.trade.rag.EmbeddingService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Embedding 服务健康检查器 —— 定期检查主模型和降级模型的可用性。
 *
 * 生产环境功能：
 * 1. 定期调用 /v1/embeddings 测试接口
 * 2. 记录响应时间和成功率
 * 3. 连续失败时触发告警
 * 4. Prometheus 指标暴露
 *
 * 监控指标（Prometheus）：
 * - embedding.health.primary.available: 主模型是否可用（1=可用，0=不可用）
 * - embedding.health.fallback.available: 降级模型是否可用
 * - embedding.health.primary.latency: 主模型响应延迟（毫秒）
 * - embedding.health.fallback.latency: 降级模型响应延迟
 * - embedding.health.failures: 连续失败次数
 *
 * 告警策略：
 * - 主模型连续失败 3 次：WARN 告警
 * - 主模型连续失败 5 次：ERROR 告警
 * - 降级模型不可用：CRITICAL 告警
 */
@Component
public class EmbeddingHealthChecker {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingHealthChecker.class);

    private final MeterRegistry meterRegistry;
    private final AiGatewayProperties properties;
    private final EmbeddingService embeddingService;
    private final WebClient webClient;

    // 健康状态（线程安全）
    private final AtomicBoolean primaryAvailable = new AtomicBoolean(false);
    private final AtomicBoolean fallbackAvailable = new AtomicBoolean(false);
    private final AtomicLong primaryLatency = new AtomicLong(0);
    private final AtomicLong fallbackLatency = new AtomicLong(0);
    private final AtomicLong primaryFailureCount = new AtomicLong(0);
    private final AtomicLong fallbackFailureCount = new AtomicLong(0);

    // 测试文本（用于健康检查）
    private static final String HEALTH_CHECK_TEXT = "health check";

    public EmbeddingHealthChecker(
            MeterRegistry meterRegistry,
            AiGatewayProperties properties,
            EmbeddingService embeddingService,
            WebClient.Builder webClientBuilder
    ) {
        this.meterRegistry = meterRegistry;
        this.properties = properties;
        this.embeddingService = embeddingService;
        this.webClient = webClientBuilder.build();

        // 注册 Prometheus 指标
        registerMetrics();

        log.info("✅ Embedding 健康检查器已启用");
    }

    /**
     * 注册 Prometheus 指标
     */
    private void registerMetrics() {
        // 主模型可用性
        meterRegistry.gauge("embedding.health.primary.available",
                primaryAvailable, available -> available.get() ? 1.0 : 0.0);

        // 降级模型可用性
        meterRegistry.gauge("embedding.health.fallback.available",
                fallbackAvailable, available -> available.get() ? 1.0 : 0.0);

        // 主模型延迟
        meterRegistry.gauge("embedding.health.primary.latency",
                primaryLatency, AtomicLong::get);

        // 降级模型延迟
        meterRegistry.gauge("embedding.health.fallback.latency",
                fallbackLatency, AtomicLong::get);

        // 连续失败次数
        meterRegistry.gauge("embedding.health.failures",
                primaryFailureCount, AtomicLong::get);
    }

    /**
     * 定期执行健康检查 —— 默认每 60 秒检查一次。
     */
    @Scheduled(fixedDelayString = "${ai.gateway.rag.healthCheck.intervalSeconds:60}000")
    public void performHealthCheck() {
        if (!properties.getRag().getHealthCheck().isEnabled()) {
            return;
        }

        // 检查主模型
        checkModelHealth("primary", properties.getRag().getEmbedding(),
                primaryAvailable, primaryLatency, primaryFailureCount);

        // 检查降级模型
        checkModelHealth("fallback", properties.getRag().getFallbackEmbedding(),
                fallbackAvailable, fallbackLatency, fallbackFailureCount);

        // 记录健康状态
        logHealthStatus();
    }

    /**
     * 检查单个模型的健康状态
     */
    private void checkModelHealth(
            String modelType,
            AiGatewayProperties.Rag.Embedding config,
            AtomicBoolean available,
            AtomicLong latency,
            AtomicLong failureCount
    ) {
        long startTime = System.currentTimeMillis();

        try {
            // 调用测试接口
            webClient.post()
                    .uri(config.getBaseUrl() + config.getPath())
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .bodyValue(Map.of(
                            "model", config.getModel(),
                            "input", java.util.List.of(HEALTH_CHECK_TEXT),
                            "encoding_format", config.getEncodingFormat()
                    ))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(properties.getRag().getHealthCheck().getTimeoutSeconds()))
                    .block();

            // 健康检查成功
            long responseTime = System.currentTimeMillis() - startTime;
            available.set(true);
            latency.set(responseTime);
            failureCount.set(0);

            log.debug("✅ {} 健康检查通过: model={}, latency={}ms",
                    modelType, config.getModel(), responseTime);

        } catch (Exception e) {
            // 健康检查失败
            available.set(false);
            failureCount.incrementAndGet();

            log.warn("⚠️ {} 健康检查失败: model={}, failures={}, error={}",
                    modelType, config.getModel(), failureCount.get(), e.getMessage());

            // 检查是否达到告警阈值
            checkFailureThreshold(modelType, failureCount.get());
        }
    }

    /**
     * 检查失败次数是否达到告警阈值
     */
    private void checkFailureThreshold(String modelType, long failures) {
        int warnThreshold = properties.getRag().getHealthCheck().getFailureThreshold();
        int criticalThreshold = warnThreshold * 2;

        if (failures >= criticalThreshold) {
            log.error("🚨 {} 模型连续失败 {} 次（严重告警）: model={}",
                    modelType, failures, getModelName(modelType));
        } else if (failures >= warnThreshold) {
            log.warn("⚠️ {} 模型连续失败 {} 次（告警）: model={}",
                    modelType, failures, getModelName(modelType));
        }
    }

    /**
     * 获取模型名称
     */
    private String getModelName(String modelType) {
        if ("primary".equals(modelType)) {
            return properties.getRag().getEmbedding().getModel();
        } else {
            return properties.getRag().getFallbackEmbedding().getModel();
        }
    }

    /**
     * 记录健康状态摘要
     */
    private void logHealthStatus() {
        boolean primary = primaryAvailable.get();
        boolean fallback = fallbackAvailable.get();

        if (primary && fallback) {
            log.info("✅ Embedding 服务健康: 主模型={}, 降级模型={}, 主模型延迟={}ms, 降级模型延迟={}ms",
                    properties.getRag().getEmbedding().getModel(),
                    properties.getRag().getFallbackEmbedding().getModel(),
                    primaryLatency.get(),
                    fallbackLatency.get());
        } else if (primary) {
            log.warn("⚠️ Embedding 服务降级: 主模型正常, 降级模型不可用={}",
                    properties.getRag().getFallbackEmbedding().getModel());
        } else if (fallback) {
            log.warn("⚠️ Embedding 服务降级: 主模型不可用={}, 降级模型正常",
                    properties.getRag().getEmbedding().getModel());
        } else {
            log.error("🚨 Embedding 服务完全不可用: 主模型={}, 降级模型={}",
                    properties.getRag().getEmbedding().getModel(),
                    properties.getRag().getFallbackEmbedding().getModel());
        }
    }

    /**
     * 获取当前健康状态（用于 API 查询）
     */
    public Map<String, Object> getHealthStatus() {
        return Map.of(
                "primary", Map.of(
                        "model", properties.getRag().getEmbedding().getModel(),
                        "available", primaryAvailable.get(),
                        "latency", primaryLatency.get(),
                        "failures", primaryFailureCount.get()
                ),
                "fallback", Map.of(
                        "model", properties.getRag().getFallbackEmbedding().getModel(),
                        "available", fallbackAvailable.get(),
                        "latency", fallbackLatency.get(),
                        "failures", fallbackFailureCount.get()
                ),
                "timestamp", System.currentTimeMillis()
        );
    }
}
