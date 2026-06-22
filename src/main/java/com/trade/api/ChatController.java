package com.trade.api;

import com.trade.dto.AiStreamEvent;
import com.trade.dto.ChatMessageDto;
import com.trade.dto.ChatRequest;
import com.trade.dto.ChatResponse;
import com.trade.enums.ModelProvider;
import com.trade.rag.RagOrchestrationService;
import com.trade.security.PromptInjectionGuard;
import com.trade.security.SensitiveWordFilter;
import com.trade.security.XssFilter;
import com.trade.service.ChatOrchestrationService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 智能问答 REST 接口 —— /api/v1/chat
 *
 * - POST /completions：同步问答，返回完整答案
 * - POST /stream：流式问答，SSE 推送 token 流
 * （SSE 是 Server-Sent Events（服务器推送事件），是 HTML5 标准中的一种 Web 通信协议。）
 *
 * 技术点：@Validated 开启参数校验，Flux/Mono 是 Reactor 响应式类型。
 *
 * RAG 支持：
 * - 当 ChatRequest.useKnowledgeBase = true 时，走 RAG 流程（检索增强）
 * - 当 useKnowledgeBase = false 或 null 时，走普通对话流程
 *
 * 安全特性：
 * - 敏感词过滤：检测并过滤用户输入和 AI 输出中的敏感词
 * - Prompt 注入防护：检测并防止恶意 Prompt 注入攻击
 * - XSS 防护：防止跨站脚本攻击
 */
@Validated
@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "智能问答", description = "提供同步和流式问答服务，支持 RAG 检索增强")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatOrchestrationService chatService;
    private final RagOrchestrationService ragService;
    private final SensitiveWordFilter sensitiveWordFilter;
    private final PromptInjectionGuard promptInjectionGuard;
    private final XssFilter xssFilter;

    public ChatController(
            ChatOrchestrationService chatService,
            RagOrchestrationService ragService,
            SensitiveWordFilter sensitiveWordFilter,
            PromptInjectionGuard promptInjectionGuard,
            XssFilter xssFilter
    ) {
        this.chatService = chatService;
        this.ragService = ragService;
        this.sensitiveWordFilter = sensitiveWordFilter;
        this.promptInjectionGuard = promptInjectionGuard;
        this.xssFilter = xssFilter;
    }

    /**
     * 同步问答 —— 根据 useKnowledgeBase 自动选择普通对话或 RAG 对话。
     * 包含安全检查：敏感词过滤、Prompt 注入防护、XSS 防护。
     */
    @PostMapping("/completions")
    @RateLimiter(name = "chat") // 限流：每秒 20 次请求
    @Operation(
            summary = "同步问答",
            description = "提交问题，等待 AI 完整回答后一次性返回。根据 useKnowledgeBase 自动选择普通对话或 RAG 对话。"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "成功返回 AI 回答",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ChatResponse.class),
                            examples = @ExampleObject(
                                    name = "成功响应",
                                    value = """
                                            {
                                              "id": "req-123",
                                              "answer": "LED面板灯的功率是50W。",
                                              "model": "DEEPSEEK",
                                              "citations": []
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "请求参数校验失败（如 question 为空）",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = com.trade.dto.ApiErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "未授权（缺少或无效的 Token）"
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "服务器内部错误"
            )
    })
    public Mono<ChatResponse> complete(@Valid @RequestBody ChatRequest request) {
        // 安全检查
        SecurityCheckResult checkResult = performSecurityChecks(request.question());
        if (checkResult.isBlocked()) {
            log.warn("Request blocked by security check: {}", checkResult.getReason());
            return Mono.error(new SecurityException(checkResult.getReason()));
        }

        // 使用清洗后的输入
        String sanitizedQuestion = checkResult.getSanitizedInput();

        // 创建新的请求对象，使用清洗后的输入
        ChatRequest sanitizedRequest = new ChatRequest(
                request.conversationId(),
                sanitizedQuestion,
                request.history(),
                request.preferredModel(),
                request.preciseMode(),
                request.metadata(),
                request.useKnowledgeBase(),
                request.systemPrompt()
        );

        if (Boolean.TRUE.equals(request.useKnowledgeBase())) {
            log.info("🔍 RAG 问答: {}", sanitizedQuestion);
            return ragService.completeWithRetrieval(sanitizedRequest)
                    .map(response -> filterSensitiveInResponse(response));
        }
        return chatService.complete(sanitizedRequest)
                .map(response -> filterSensitiveInResponse(response));
    }

    /**
     * 流式问答 —— SSE 推送，根据 useKnowledgeBase 自动选择。
     * 包含安全检查：敏感词过滤、Prompt 注入防护、XSS 防护。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimiter(name = "chat") // 限流：每秒 20 次请求
    @Operation(
            summary = "流式问答（SSE）",
            description = "提交问题，通过 SSE (Server-Sent Events) 实时推送 AI 生成的每个 token。适合前端实时展示。"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "SSE 事件流",
                    content = @Content(
                            mediaType = "text/event-stream",
                            examples = @ExampleObject(
                                    name = "SSE 事件流",
                                    value = """
                                            id: event-1
                                            event: route
                                            data: {"scenario":"QA","selectedModel":"DEEPSEEK","score":95.0}

                                            id: event-1
                                            event: token
                                            data: {"content":"LED面板灯的功率"}

                                            id: event-1
                                            event: token
                                            data: {"content":"是50W。"}

                                            id: event-1
                                            event: done
                                            data: {"model":"DEEPSEEK"}
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "请求参数校验失败"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "未授权"
            )
    })
    public Flux<ServerSentEvent<AiStreamEvent>> stream(@Valid @RequestBody ChatRequest request) {
        // 安全检查
        SecurityCheckResult checkResult = performSecurityChecks(request.question());
        if (checkResult.isBlocked()) {
            log.warn("Request blocked by security check: {}", checkResult.getReason());
            return Flux.error(new SecurityException(checkResult.getReason()));
        }

        // 使用清洗后的输入
        String sanitizedQuestion = checkResult.getSanitizedInput();

        // 创建新的请求对象，使用清洗后的输入
        ChatRequest sanitizedRequest = new ChatRequest(
                request.conversationId(),
                sanitizedQuestion,
                request.history(),
                request.preferredModel(),
                request.preciseMode(),
                request.metadata(),
                request.useKnowledgeBase(),
                request.systemPrompt()
        );

        if (Boolean.TRUE.equals(request.useKnowledgeBase())) {
            log.info("🔍 RAG 流式问答: {}", sanitizedQuestion);
            return ragService.streamWithRetrieval(sanitizedRequest)
                    .map(event -> ServerSentEvent.<AiStreamEvent>builder()
                            .id(event.id())
                            .event(event.type().name().toLowerCase())
                            .data(event)
                            .build());
        }
        return chatService.stream(sanitizedRequest)
                .map(event -> ServerSentEvent.<AiStreamEvent>builder()
                        .id(event.id())
                        .event(event.type().name().toLowerCase())
                        .data(event)
                        .build());
    }

    /**
     * 执行安全检查：XSS、Prompt 注入、敏感词。
     *
     * @param input 用户输入
     * @return 检查结果
     */
    private SecurityCheckResult performSecurityChecks(String input) {
        SecurityCheckResult result = new SecurityCheckResult();

        // 1. XSS 检查
        if (xssFilter.containsXss(input)) {
            if (xssFilter.isBlockMode()) {
                result.setBlocked(true);
                result.setReason("XSS attack detected");
                return result;
            }
            // 清洗模式
            input = xssFilter.sanitize(input);
        }

        // 2. Prompt 注入检查
        if (promptInjectionGuard.detectInjection(input)) {
            result.setBlocked(true);
            result.setReason("Prompt injection detected: " + 
                    promptInjectionGuard.getDetectedPatterns(input));
            return result;
        }

        // 3. 敏感词检查
        if (sensitiveWordFilter.containsSensitiveWord(input)) {
            // 清洗敏感词
            input = sensitiveWordFilter.replaceSensitiveWords(input);
        }

        result.setSanitizedInput(input);
        return result;
    }

    /**
     * 过滤 AI 响应中的敏感词。
     *
     * @param response AI 响应
     * @return 过滤后的响应
     */
    private ChatResponse filterSensitiveInResponse(ChatResponse response) {
        if (response.answer() != null) {
            String filteredAnswer = sensitiveWordFilter.replaceSensitiveWords(response.answer());
            return new ChatResponse(
                    response.id(),
                    response.conversationId(),
                    response.model(),
                    filteredAnswer,
                    response.route(),
                    response.usage(),
                    response.citations(),
                    response.createdAt(),
                    response.hasRelevantInfo()
            );
        }
        return response;
    }

    /**
     * 安全检查结果内部类。
     */
    private static class SecurityCheckResult {
        private boolean blocked = false;
        private String reason;
        private String sanitizedInput;

        public boolean isBlocked() {
            return blocked;
        }

        public void setBlocked(boolean blocked) {
            this.blocked = blocked;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public String getSanitizedInput() {
            return sanitizedInput;
        }

        public void setSanitizedInput(String sanitizedInput) {
            this.sanitizedInput = sanitizedInput;
        }
    }

    /**
     * 安全异常。
     */
    public static class SecurityException extends RuntimeException {
        public SecurityException(String message) {
            super(message);
        }
    }
}
