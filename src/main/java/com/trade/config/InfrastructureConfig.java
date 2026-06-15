package com.trade.config;

import com.trade.dto.ChatResponse;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * 基础设施配置 —— HTTP 连接池和响应缓存。
 *
 * 1. aiWebClientBuilder：
 *    - 连接池最大 300 个并发连接，等待队列 1000
 *    - 空闲连接 30s 回收，最长存活 10 分钟（避免连接泄漏）
 *    - 连接超时 5s，响应超时 70s（给模型留出足够时间生成）
 *
 * 2. responseCache：
 *    - Caffeine 内存缓存，最大 20,000 条
 *    - 写入后过期（TTL 由 application.yml 配置，默认 10 分钟）
 *    - 开启统计（recordStats），可通过 /actuator/metrics 查看命中率
 */
@Configuration
public class InfrastructureConfig {

    /**
     * WebClient.Builder Bean —— 所有模型调用共享同一个 HTTP 客户端配置。
     * 连接池复用是关键：避免每次请求都创建新的 TCP 连接，显著提升并发性能。
     */
    @Bean
    WebClient.Builder aiWebClientBuilder() {
        ConnectionProvider provider = ConnectionProvider.builder("ai-model-pool")
                .maxConnections(300)
                .pendingAcquireMaxCount(1000)
                .maxIdleTime(Duration.ofSeconds(30))
                .maxLifeTime(Duration.ofMinutes(10))
                .build();
        HttpClient httpClient = HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .responseTimeout(Duration.ofSeconds(70));
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }

    @Bean
    Cache<String, ChatResponse> responseCache(AiGatewayProperties properties) {
        return Caffeine.newBuilder()
                .expireAfterWrite(properties.getCacheTtl())
                .maximumSize(20_000)
                .recordStats()
                .build();
    }
}
