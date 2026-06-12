package com.example.trade.model;

import com.example.trade.enums.ModelProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * AI 模型统一接口 —— 所有模型客户端实现此接口。
 *
 *
 * 目前唯一实现：LangChain4jChatModel（基于 LangChain4j，兼容 OpenAI Chat Completions 协议）
 */
public interface AiChatModel {
    /**
     * 返回模型供应商标识
     * @return
     */
    ModelProvider provider();

    /**
     * 检查模型是否可用（apiKey、baseUrl 等是否完整）
     * @return
     */
    boolean available();

    /**
     * 同步调用，返回完整响应
     * @param request
     * @return
     */
    Mono<AiModelResponse> complete(AiPromptRequest request);

    /**
     * 流式调用，返回 token 流
     * @param request
     * @return
     */
    Flux<AiToken> stream(AiPromptRequest request);
}
