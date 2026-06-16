package com.trade.config;

import com.trade.dto.ApiErrorResponse;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Resilience4j 限流异常处理器 —— 捕获限流异常并返回 429 状态码。
 *
 * 技术点：
 * - WebExceptionHandler：WebFlux 级别的异常处理器
 * - RequestNotPermitted：Resilience4j 限流异常
 * - 返回 429 Too Many Requests + JSON 错误信息
 *
 * 响应示例：
 * HTTP/1.1 429 Too Many Requests
 * Content-Type: application/json
 *
 * {
 *   "code": "RATE_LIMIT_EXCEEDED",
 *   "message": "请求过于频繁，请稍后重试",
 *   "details": [],
 *   "path": "/api/v1/chat/completions",
 *   "timestamp": "2026-06-15T10:30:00Z"
 * }
 */
// 注意：不使用 @Component，而是通过 Spring Boot 自动配置注册
public class RateLimitExceptionHandler implements WebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (ex instanceof RequestNotPermitted) {
            // 限流触发，返回 429
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");

            ApiErrorResponse errorResponse = ApiErrorResponse.rateLimitExceeded(
                    exchange.getRequest().getPath().value()
            );

            // 将错误响应序列化为 JSON 并写入响应
            byte[] bytes = toJsonBytes(errorResponse);
            return response.writeWith(Mono.just(
                    response.bufferFactory().wrap(bytes)
            ));
        }

        // 其他异常不处理，交给下一个处理器
        return Mono.error(ex);
    }

    /**
     * 将 ApiErrorResponse 序列化为 JSON 字节数组。
     * 简单实现，避免引入 Jackson 依赖。
     */
    private byte[] toJsonBytes(ApiErrorResponse response) {
        String json = String.format(
                "{\"code\":\"%s\",\"message\":\"%s\",\"details\":[],\"path\":\"%s\",\"timestamp\":\"%s\"}",
                escapeJson(response.code()),
                escapeJson(response.message()),
                escapeJson(response.path()),
                response.timestamp().toString()
        );
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 简单转义 JSON 特殊字符。
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
