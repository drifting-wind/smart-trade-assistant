package com.trade.security;

import com.trade.config.AiGatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.WebFilter;

/**
 * 安全配置类 —— 统一配置和启用安全组件。
 *
 * 配置项：
 * - 敏感词过滤器
 * - Prompt 注入防护
 * - XSS 防护
 * - 安全响应头
 *
 * 通过配置项启用/禁用：
 * - security.sensitive-word.enabled: 敏感词过滤
 * - security.prompt-injection.enabled: Prompt 注入防护
 * - security.xss.enabled: XSS 防护
 *
 * @author Security Team
 * @since 1.0
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final AiGatewayProperties properties;
    private final SensitiveWordFilter sensitiveWordFilter;
    private final PromptInjectionGuard promptInjectionGuard;
    private final XssFilter xssFilter;

    public SecurityConfig(AiGatewayProperties properties,
                         SensitiveWordFilter sensitiveWordFilter,
                         PromptInjectionGuard promptInjectionGuard,
                         XssFilter xssFilter) {
        this.properties = properties;
        this.sensitiveWordFilter = sensitiveWordFilter;
        this.promptInjectionGuard = promptInjectionGuard;
        this.xssFilter = xssFilter;
    }

    /**
     * 初始化安全组件配置。
     * 根据 application.yml 中的配置启用或禁用各个安全组件。
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        // 配置敏感词过滤器
        AiGatewayProperties.SensitiveWordConfig sensitiveWordConfig = properties.getSecurity().getSensitiveWord();
        if (sensitiveWordConfig != null) {
            sensitiveWordFilter.setEnabled(sensitiveWordConfig.isEnabled());
            log.info("SensitiveWordFilter configured: enabled={}", sensitiveWordConfig.isEnabled());
        }

        // 配置 Prompt 注入防护
        AiGatewayProperties.PromptInjectionConfig promptInjectionConfig = properties.getSecurity().getPromptInjection();
        if (promptInjectionConfig != null) {
            promptInjectionGuard.setEnabled(promptInjectionConfig.isEnabled());
            log.info("PromptInjectionGuard configured: enabled={}", promptInjectionConfig.isEnabled());
        }

        // 配置 XSS 防护
        AiGatewayProperties.XssConfig xssConfig = properties.getSecurity().getXss();
        if (xssConfig != null) {
            xssFilter.setEnabled(xssConfig.isEnabled());
            xssFilter.setBlockMode(xssConfig.isBlockMode());
            log.info("XssFilter configured: enabled={}, blockMode={}", xssConfig.isEnabled(), xssConfig.isBlockMode());
        }

        log.info("Security configuration initialized");
    }

    /**
     * 创建安全检查 WebFilter。
     * 该过滤器在请求到达控制器之前进行安全检查。
     *
     * @return WebFilter
     */
    @Bean
    @Order(3) // 在 XssFilter 之后执行
    @ConditionalOnProperty(prefix = "security.check", name = "enabled", havingValue = "true", matchIfMissing = true)
    public WebFilter securityCheckFilter() {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            String path = request.getPath().pathWithinApplication().value();

            // 只对 API 请求进行安全检查
            if (!path.startsWith("/api/v1/")) {
                return chain.filter(exchange);
            }

            // 获取请求体（如果有）
            // 注意：这里简化处理，实际应用中需要更复杂的请求体缓存
            String query = request.getURI().getQuery();
            if (query != null) {
                // 检查查询参数
                if (sensitiveWordFilter.containsSensitiveWord(query)) {
                    log.warn("Sensitive word detected in query from IP: {}", request.getRemoteAddress());
                    // 可以选择拦截或记录
                }

                if (promptInjectionGuard.detectInjection(query)) {
                    log.warn("Prompt injection detected in query from IP: {}", request.getRemoteAddress());
                    // 可以选择拦截或记录
                }
            }

            return chain.filter(exchange);
        };
    }
}
