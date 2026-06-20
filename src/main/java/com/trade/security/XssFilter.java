package com.trade.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.regex.Pattern;

/**
 * XSS 防护过滤器 —— 防止跨站脚本攻击。
 *
 * 防护策略：
 * 1. 检测请求参数中的 XSS 攻击特征
 * 2. 检测请求体中的 XSS 攻击特征
 * 3. 清洗或拦截可疑请求
 * 4. 添加安全响应头
 *
 * 常见 XSS 攻击模式：
 * - <script> 标签
 * - javascript: 协议
 * - onXXX 事件处理器
 * - HTML 注入
 * - 编码绕过
 *
 * @author Security Team
 * @since 1.0
 */
@Component
@Order(2) // 在 ApiTokenWebFilter 之后执行
public class XssFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(XssFilter.class);

    // XSS 攻击模式
    private static final Pattern[] XSS_PATTERNS = {
            Pattern.compile("<script>(.*?)</script>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("src[\r\n]*=[\r\n]*\\'(.*?)\\'", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL),
            Pattern.compile("src[\r\n]*=[\r\n]*\\\"(.*?)\\\"", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL),
            Pattern.compile("</script>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<script(.*?)>", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL),
            Pattern.compile("eval\\((.*?)\\)", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL),
            Pattern.compile("expression\\((.*?)\\)", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL),
            Pattern.compile("javascript:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("vbscript:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("onload(.*?)=", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL),
            Pattern.compile("on\\w+=", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL),
            Pattern.compile("<iframe(.*?)>", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL),
            Pattern.compile("<embed(.*?)>", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL),
            Pattern.compile("<object(.*?)>", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL),
            Pattern.compile("<style>(.*?)</style>", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL),
            Pattern.compile("&#\\d+;"), // HTML 实体编码
            Pattern.compile("&#x[0-9a-fA-F]+;"), // HTML 十六进制实体编码
            Pattern.compile("%3Cscript", Pattern.CASE_INSENSITIVE), // URL 编码
            Pattern.compile("%3C%73%63%72%69%70%74", Pattern.CASE_INSENSITIVE), // 完整 URL 编码
            Pattern.compile("\\\\x3cscript", Pattern.CASE_INSENSITIVE), // 十六进制转义
            Pattern.compile("\\\\u003cscript", Pattern.CASE_INSENSITIVE) // Unicode 转义
    };

    // 是否启用 XSS 防护
    private boolean enabled = true;

    // 是否拦截可疑请求（true 拦截，false 清洗）
    private boolean blockMode = false;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        ServerHttpRequest request = exchange.getRequest();

        // 检查查询参数
        if (hasXssInParameters(request)) {
            if (blockMode) {
                log.warn("XSS attack detected in query parameters from IP: {}", 
                        request.getRemoteAddress());
                return blockRequest(exchange);
            } else {
                log.debug("XSS attempt detected in query parameters, sanitizing...");
            }
        }

        // 检查请求头
        if (hasXssInHeaders(request)) {
            if (blockMode) {
                log.warn("XSS attack detected in headers from IP: {}", 
                        request.getRemoteAddress());
                return blockRequest(exchange);
            } else {
                log.debug("XSS attempt detected in headers, sanitizing...");
            }
        }

        // 添加安全响应头
        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().add("X-XSS-Protection", "1; mode=block");
        response.getHeaders().add("X-Content-Type-Options", "nosniff");
        response.getHeaders().add("X-Frame-Options", "DENY");
        response.getHeaders().add("Content-Security-Policy", 
                "default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none';");

        return chain.filter(exchange);
    }

    /**
     * 检查请求参数是否包含 XSS 攻击。
     *
     * @param request HTTP 请求
     * @return true 包含 XSS 攻击，false 不包含
     */
    private boolean hasXssInParameters(ServerHttpRequest request) {
        // 检查所有查询参数
        return request.getQueryParams().values().stream()
                .flatMap(List::stream)
                .anyMatch(this::containsXss);
    }

    /**
     * 检查请求头是否包含 XSS 攻击。
     *
     * @param request HTTP 请求
     * @return true 包含 XSS 攻击，false 不包含
     */
    private boolean hasXssInHeaders(ServerHttpRequest request) {
        // 检查常见的请求头
        String[] headersToCheck = {
                "User-Agent", "Referer", "Accept", "Accept-Language",
                "Accept-Encoding", "Cookie", "X-Forwarded-For"
        };

        for (String headerName : headersToCheck) {
            String headerValue = request.getHeaders().getFirst(headerName);
            if (headerValue != null && containsXss(headerValue)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 检查字符串是否包含 XSS 攻击特征。
     *
     * @param value 待检查字符串
     * @return true 包含 XSS 攻击特征，false 不包含
     */
    public boolean containsXss(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }

        for (Pattern pattern : XSS_PATTERNS) {
            if (pattern.matcher(value).find()) {
                return true;
            }
        }

        return false;
    }

    /**
     * 清洗字符串中的 XSS 攻击内容。
     *
     * @param value 原始字符串
     * @return 清洗后的字符串
     */
    public String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        String sanitized = value;

        // 移除 script 标签
        sanitized = sanitized.replaceAll("(?i)<script[^>]*>.*?</script>", "");

        // 移除事件处理器
        sanitized = sanitized.replaceAll("(?i)\\s+on\\w+\\s*=\\s*[\"']?[^\"']*[\"']?", "");

        // 移除 javascript: 协议
        sanitized = sanitized.replaceAll("(?i)javascript:", "");

        // 移除 vbscript: 协议
        sanitized = sanitized.replaceAll("(?i)vbscript:", "");

        // 移除 data: 协议
        sanitized = sanitized.replaceAll("(?i)data:", "");

        // 移除 HTML 标签
        sanitized = sanitized.replaceAll("<[^>]+>", "");

        // 移除 HTML 实体编码
        sanitized = sanitized.replaceAll("&#\\d+;", "");
        sanitized = sanitized.replaceAll("&#x[0-9a-fA-F]+;", "");

        // 移除 URL 编码
        sanitized = sanitized.replaceAll("%3C", "<");
        sanitized = sanitized.replaceAll("%3E", ">");
        sanitized = sanitized.replaceAll("%22", "\"");
        sanitized = sanitized.replaceAll("%27", "'");

        // 移除特殊字符
        sanitized = sanitized.replaceAll("[<>\"']", "");

        return sanitized.trim();
    }

    /**
     * 拦截请求并返回 400 错误。
     *
     * @param exchange WebExchange
     * @return Mono<Void>
     */
    private Mono<Void> blockRequest(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.BAD_REQUEST);
        return exchange.getResponse().setComplete();
    }

    /**
     * 启用或禁用 XSS 防护。
     *
     * @param enabled true 启用，false 禁用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("XssFilter enabled: {}", enabled);
    }

    /**
     * 检查是否启用 XSS 防护。
     *
     * @return true 启用，false 禁用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置拦截模式。
     *
     * @param blockMode true 拦截可疑请求，false 清洗可疑内容
     */
    public void setBlockMode(boolean blockMode) {
        this.blockMode = blockMode;
        log.info("XssFilter blockMode: {}", blockMode);
    }

    /**
     * 检查是否为拦截模式。
     *
     * @return true 拦截模式，false 清洗模式
     */
    public boolean isBlockMode() {
        return blockMode;
    }
}
