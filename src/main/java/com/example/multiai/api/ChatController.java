package com.example.multiai.api;

import com.example.multiai.dto.AiStreamEvent;
import com.example.multiai.dto.ChatRequest;
import com.example.multiai.dto.ChatResponse;
import com.example.multiai.service.ChatOrchestrationService;
import jakarta.validation.Valid;
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
 */
@Validated
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatOrchestrationService chatService;

    public ChatController(ChatOrchestrationService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/completions")
    public Mono<ChatResponse> complete(@Valid @RequestBody ChatRequest request) {
        return chatService.complete(request);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AiStreamEvent>> stream(@Valid @RequestBody ChatRequest request) {
        return chatService.stream(request)
                .map(event -> ServerSentEvent.<AiStreamEvent>builder()
                        .id(event.id())
                        .event(event.type().name().toLowerCase())
                        .data(event)
                        .build());
    }
}
