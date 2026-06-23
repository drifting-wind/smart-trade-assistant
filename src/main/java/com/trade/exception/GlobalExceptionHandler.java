package com.trade.exception;

import com.trade.api.ChatController;
import com.trade.dto.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;

import java.time.Instant;
import java.util.List;

/**
 * 全局异常处理器 —— 统一拦截 WebFlux 层的异常，返回标准化 JSON 错误响应。
 *
 * 技术点：@RestControllerAdvice 是 Spring 的全局异常拦截注解，
 * 配合 @ExceptionHandler 按异常类型分发处理。
 *
 * 异常类型与 HTTP 状态码映射：
 * - WebExchangeBindException → 400 Bad Request（参数校验失败）
 * - ChatController.SecurityException → 400 Bad Request（安全检查失败）
 * - NoAvailableModelException → 503 Service Unavailable（没有可用 AI 模型）
 * - ModelInvocationException → 502 Bad Gateway（AI 模型调用失败）
 * - Exception（兜底） → 500 Internal Server Error
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 参数校验失败 —— 400 Bad Request，返回具体字段错误信息 */
    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ApiErrorResponse> validation(WebExchangeBindException error, ServerWebExchange exchange) {
        List<String> details = error.getFieldErrors().stream()
                .map(this::formatFieldError)
                .toList();
        return ResponseEntity.badRequest().body(body("VALIDATION_ERROR", "请求参数不合法", details, exchange));
    }

    /** 安全检查失败 —— 400 Bad Request，返回具体的安全问题 */
    @ExceptionHandler({
        ChatController.SecurityException.class,
        com.trade.trade.api.TradeSalesController.SecurityException.class
    })
    public ResponseEntity<ApiErrorResponse> security(RuntimeException error, ServerWebExchange exchange) {
        return ResponseEntity.badRequest()
                .body(body("SECURITY_VIOLATION", error.getMessage(), List.of("请求包含不安全内容，已被拦截"), exchange));
    }

    /** 没有可用 AI 模型 —— 503 Service Unavailable，提示检查 API Key 配置 */
    @ExceptionHandler(NoAvailableModelException.class)
    public ResponseEntity<ApiErrorResponse> noModel(NoAvailableModelException error, ServerWebExchange exchange) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(body("NO_AVAILABLE_MODEL", error.getMessage(), List.of(), exchange));
    }

    /** AI 模型调用失败 —— 502 Bad Gateway，返回具体模型名和错误信息 */
    @ExceptionHandler(ModelInvocationException.class)
    public ResponseEntity<ApiErrorResponse> model(ModelInvocationException error, ServerWebExchange exchange) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(body("MODEL_INVOCATION_ERROR", error.getMessage(), List.of(error.getProvider().name()), exchange));
    }

    /** 向量数据库操作失败 —— 502 Bad Gateway，返回具体错误信息 */
    @ExceptionHandler(VectorStoreException.class)
    public ResponseEntity<ApiErrorResponse> vectorStore(VectorStoreException error, ServerWebExchange exchange) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(body("VECTOR_STORE_ERROR", error.getMessage(), List.of(), exchange));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> generic(Exception error, ServerWebExchange exchange) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("INTERNAL_ERROR", error.getMessage(), List.of(), exchange));
    }

    private ApiErrorResponse body(String code, String message, List<String> details, ServerWebExchange exchange) {
        return new ApiErrorResponse(
                code,
                message,
                details,
                exchange.getRequest().getPath().pathWithinApplication().value(),
                Instant.now()
        );
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
