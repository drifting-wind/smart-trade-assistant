package com.trade.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * 统一错误响应 DTO —— GlobalExceptionHandler 拦截所有异常后返回的标准化错误格式。
 * 所有接口共用，保证前端统一的错误处理逻辑。
 */
@Schema(description = "API 错误响应")
public record ApiErrorResponse(
        /**
         * 错误码 —— 机器可读的错误类型标识。
         * 例如：VALIDATION_ERROR（参数校验失败）、NO_AVAILABLE_MODEL（无可用模型）、
         * MODEL_INVOCATION_ERROR（AI 调用失败）、INTERNAL_ERROR（内部异常）。
         */
        @Schema(description = "错误码", example = "RATE_LIMIT_EXCEEDED")
        String code,

        /**
         * 错误描述 —— 人类可读的错误信息，用于前端 Toast/弹窗展示。
         */
        @Schema(description = "错误描述", example = "请求过于频繁，请稍后重试")
        String message,

        /**
         * 错误详情 —— 补充信息列表。
         * 参数校验失败时：["question: 不能为空", "goal: 长度不能超过30000"]
         * 模型调用失败时：["DEEPSEEK"]
         * 内部异常时：为空列表。
         */
        @Schema(description = "错误详情")
        List<String> details,

        /**
         * 请求路径 —— 触发错误的 API 端点，例如 "/api/v1/chat/completions"。
         * 由 exchange.getRequest().getPath() 自动获取，方便日志关联。
         */
        @Schema(description = "请求路径", example = "/api/v1/chat/completions")
        String path,

        /**
         * 错误发生时间 —— UTC 时间戳，用于审计和问题排查。
         */
        @Schema(description = "错误发生时间")
        Instant timestamp
) {
    /**
     * 创建限流错误响应。
     */
    public static ApiErrorResponse rateLimitExceeded(String path) {
        return new ApiErrorResponse(
                "RATE_LIMIT_EXCEEDED",
                "请求过于频繁，请稍后重试",
                List.of(),
                path,
                Instant.now()
        );
    }

    /**
     * 创建未授权错误响应。
     */
    public static ApiErrorResponse unauthorized(String path) {
        return new ApiErrorResponse(
                "UNAUTHORIZED",
                "缺少或无效的 API Token",
                List.of(),
                path,
                Instant.now()
        );
    }

    /**
     * 创建参数校验错误响应。
     */
    public static ApiErrorResponse badRequest(String message, String path) {
        return new ApiErrorResponse(
                "BAD_REQUEST",
                message,
                List.of(),
                path,
                Instant.now()
        );
    }
}
