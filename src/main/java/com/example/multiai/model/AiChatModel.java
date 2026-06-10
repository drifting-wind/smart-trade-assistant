package com.example.multiai.model;

import com.example.multiai.enums.ModelProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * AI 模型统一接口 —— 所有模型客户端实现此接口。
 *
 * 核心方法：
 * - provider()：返回模型供应商标识
 * - available()：检查模型是否可用（apiKey、baseUrl 等是否完整）
 * - complete()：同步调用，返回完整响应
 * - stream()：流式调用，返回 token 流
 *
 * 目前唯一实现：OpenAiCompatibleChatModel（兼容 OpenAI Chat Completions 协议）
 */
public interface AiChatModel {

    ModelProvider provider();

    boolean available();

    Mono<AiModelResponse> complete(AiPromptRequest request);

    Flux<AiToken> stream(AiPromptRequest request);
}
