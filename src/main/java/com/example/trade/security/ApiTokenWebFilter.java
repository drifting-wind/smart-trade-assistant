package com.example.trade.security;

import com.example.trade.config.AiGatewayProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.Set;

/**
 * API Token 认证过滤器 —— WebFlux 级别的网关安全拦截。
 *
 * 认证流程：
 * 1. 如果安全开关关闭，直接放行
 * 2. 如果是公开路径（非 /api/v1/ 开头，或健康检查/Swagger），放行
 * 3. 从请求头提取 Token：优先 X-API-Token，其次 Authorization: Bearer
 * 4. 白名单匹配：Token 在配置的列表中则放行，否则返回 401
 *
 * 技术点：WebFlux 的 WebFilter 是响应式的，返回 Mono<Void> 控制链路。
 */
@Component
public class ApiTokenWebFilter implements WebFilter {

    private final AiGatewayProperties properties;

    public ApiTokenWebFilter(AiGatewayProperties properties) {
        this.properties = properties;
    }

    /**
     * 过滤逻辑：认证通过则继续链式处理，否则返回 401。
     *
     * 技术点：
     * - chain.filter(exchange)：放行到下一个处理器
     * - exchange.getResponse().setComplete()：直接结束响应（返回 401 状态码）
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!properties.getSecurity().isEnabled() || isPublicPath(exchange)) {
            return chain.filter(exchange);
        }
        Set<String> configuredTokens = new HashSet<>(properties.getSecurity().getTokens());
        String token = tokenFrom(exchange);
        if (StringUtils.hasText(token) && configuredTokens.contains(token)) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    /**
     * 判断是否为公开路径 —— 不需要认证。
     * 规则：非 /api/v1/ 开头的路径（如 /actuator/health、/swagger-ui）不拦截。
     */
    private boolean isPublicPath(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        return !path.startsWith("/api/v1/")
                || path.startsWith("/actuator/health")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")
                || path.equals("/swagger-ui.html");
    }

    /**
     * 从请求头提取 Token —— 支持两种格式：
     * 1. X-API-Token: {token}（自定义 Header，推荐用于 API 客户端）
     * 2. Authorization: Bearer {token}（标准 OAuth2 格式，兼容通用客户端）
     */
    private String tokenFrom(ServerWebExchange exchange) {
        String token = exchange.getRequest().getHeaders().getFirst("X-API-Token");
        if (StringUtils.hasText(token)) {
            return token;
        }
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            return authorization.substring(7);
        }
        return null;
    }
}
