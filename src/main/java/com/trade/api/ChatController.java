package com.trade.api;

import com.trade.dto.AiStreamEvent;
import com.trade.dto.ChatRequest;
import com.trade.dto.ChatResponse;
import com.trade.rag.RagOrchestrationService;
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
 */
@Validated
@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "智能问答", description = "提供同步和流式问答服务，支持 RAG 检索增强")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatOrchestrationService chatService;
    private final RagOrchestrationService ragService;

    public ChatController(
            ChatOrchestrationService chatService,
            RagOrchestrationService ragService
    ) {
        this.chatService = chatService;
        this.ragService = ragService;
    }

    /**
     * 同步问答 —— 根据 useKnowledgeBase 自动选择普通对话或 RAG 对话。
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
        if (Boolean.TRUE.equals(request.useKnowledgeBase())) {
            log.info("🔍 RAG 问答: {}", request.question());
            return ragService.completeWithRetrieval(request);
        }
        return chatService.complete(request);
    }

    /**
     * 流式问答 —— SSE 推送，根据 useKnowledgeBase 自动选择。
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
        if (Boolean.TRUE.equals(request.useKnowledgeBase())) {
            log.info("🔍 RAG 流式问答: {}", request.question());
            return ragService.streamWithRetrieval(request)
                    .map(event -> ServerSentEvent.<AiStreamEvent>builder()
                            .id(event.id())
                            .event(event.type().name().toLowerCase())
                            .data(event)
                            .build());
        }
        return chatService.stream(request)
                .map(event -> ServerSentEvent.<AiStreamEvent>builder()
                        .id(event.id())
                        .event(event.type().name().toLowerCase())
                        .data(event)
                        .build());
    }
}
