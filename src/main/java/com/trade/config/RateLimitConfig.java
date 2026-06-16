package com.trade.config;

import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Resilience4j 限流配置 —— 提供 RateLimiterRegistry Bean。
 *
 * 配置说明：
 * - RateLimiterRegistry：限流器注册表，管理所有限流器实例
 * - 限流器实例定义在 application.yml 中（resilience4j.ratelimiter.instances）
 *
 * 使用方式：
 * 1. 注解方式：@RateLimiter(name = "api")
 * 2. 编程方式：rateLimiterRegistry.rateLimiter("api")
 */
@Configuration
public class RateLimitConfig {

    /**
     * 创建限流器注册表 Bean。
     * Resilience4j 会自动读取 application.yml 中的配置并初始化限流器。
     */
    @Bean
    public RateLimiterRegistry rateLimiterRegistry() {
        return RateLimiterRegistry.ofDefaults();
    }

    /**
     * 创建限流异常处理器 Bean。
     * 捕获 RequestNotPermitted 异常并返回 429 状态码。
     */
    @Bean
    public RateLimitExceptionHandler rateLimitExceptionHandler() {
        return new RateLimitExceptionHandler();
    }
}
