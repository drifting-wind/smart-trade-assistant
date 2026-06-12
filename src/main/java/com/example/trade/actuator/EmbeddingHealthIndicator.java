package com.example.trade.actuator;

import com.example.trade.config.AiGatewayProperties;
import com.example.trade.rag.EmbeddingService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Embedding 服务健康检查指示器 —— 用于 Spring Boot Actuator 监控。
 *
 * 访问方式：GET /actuator/health/embedding
 *
 * 检查项：
 * 1. 主模型配置是否完整
 * 2. 降级模型配置是否完整
 * 3. 向量维度是否合理
 * 4. 熔断器状态
 */
@Component
public class EmbeddingHealthIndicator implements HealthIndicator {

    private final AiGatewayProperties properties;
    private final EmbeddingService embeddingService;

    public EmbeddingHealthIndicator(AiGatewayProperties properties, EmbeddingService embeddingService) {
        this.properties = properties;
        this.embeddingService = embeddingService;
    }

    @Override
    public Health health() {
        AiGatewayProperties.Rag rag = properties.getRag();
        AiGatewayProperties.Rag.Embedding embedding = rag.getEmbedding();
        AiGatewayProperties.Rag.Embedding fallback = rag.getFallbackEmbedding();

        // 检查主模型配置
        boolean primaryConfigured = embedding.getModel() != null && embedding.getBaseUrl() != null;
        boolean fallbackConfigured = fallback.getModel() != null && fallback.getBaseUrl() != null;
        boolean dimensionValid = rag.getDimension() > 0 && rag.getDimension() <= 8192;

        // 获取 Embedding 服务内部状态
        Map<String, Object> serviceHealth = embeddingService.health();

        Health.Builder builder = primaryConfigured && fallbackConfigured && dimensionValid
                ? Health.up() : Health.down();

        return builder
                .withDetail("primaryModel", embedding.getModel())
                .withDetail("fallbackModel", fallback.getModel())
                .withDetail("dimension", rag.getDimension())
                .withDetail("primaryCircuitOpen", serviceHealth.get("primaryCircuitOpen"))
                .withDetail("primaryFailures", serviceHealth.get("primaryFailures"))
                .withDetail("status", "主模型和降级模型均已配置")
                .build();
    }
}
