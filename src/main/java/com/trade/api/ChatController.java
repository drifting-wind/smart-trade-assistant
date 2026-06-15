package com.trade.api;

import com.trade.dto.AiStreamEvent;
import com.trade.dto.ChatRequest;
import com.trade.dto.ChatResponse;
import com.trade.rag.RagOrchestrationService;
import com.trade.service.ChatOrchestrationService;
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
