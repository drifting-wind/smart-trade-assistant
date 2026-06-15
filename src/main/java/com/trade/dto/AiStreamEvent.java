package com.trade.dto;

import com.trade.enums.ModelProvider;
import com.trade.enums.StreamEventType;

import java.time.Instant;
import java.util.List;

/**
 * SSE 流式事件 DTO —— 每个事件对应一个 StreamEventType（ROUTE/TOKEN/DONE/ERROR/CITATIONS）。
 * 通过 ServerSentEvent 包装后以 text/event-stream 协议推送到客户端。
 *
 * 工厂方法：route() / token() / done() / error() / citations() 快速构造。
 */
public record AiStreamEvent(
        /**
         * 事件 ID —— 一次流式请求的唯一标识（UUID）。
         * 同一次流式调用的所有事件共享同一个 id，
         * 前端据此关联同一轮 SSE 推送。
         */
        String id,

        /**
         * 事件类型 —— ROUTE（路由决策）、TOKEN（增量文本）、DONE（完成）、ERROR（错误）、CITATIONS（引用信息）。
         * 客户端根据 type 决定展示逻辑：TOKEN 追加文字，ROUTE 显示模型信息，DONE 结束动画。
         * CITATIONS 事件在流式结束后发送，包含所有引用信息。
         */
        StreamEventType type,

        /**
         * 模型供应商 —— 当前事件来自哪个 AI 模型。
         * TOKEN 事件中表示生成该 token 的模型，ERROR 事件中表示失败的模型。
         */
        ModelProvider model,

        /**
         * 事件内容 —— 根据事件类型有不同含义：
         * TOKEN 事件：增量文本片段（可能是一个字、一个词或部分 JSON）
         * ERROR 事件：错误描述信息
         * ROUTE/DONE/CITATIONS 事件：为 null，数据在 route 或 citations 字段中
         */
        String content,

        /**
         * 路由决策 —— 仅在 ROUTE 事件中携带，其他事件为 null。
         * 告知客户端本次流式请求选择了哪个模型及原因。
         */
        RouteDecisionDto route,

        /**
         * 引用信息列表 —— 仅在 CITATIONS 事件中携带，其他事件为 null。
         * 流式结束后发送，包含回答中引用的所有来源，用于溯源定位。
         */
        List<Citation> citations,

        /**
         * 事件创建时间 —— UTC 时间戳，用于前端排序和延迟分析。
         */
        Instant createdAt
) {
    public static AiStreamEvent route(String id, RouteDecisionDto route) {
        return new AiStreamEvent(id, StreamEventType.ROUTE, route.selectedModel(), null, route, null, Instant.now());
    }

    public static AiStreamEvent token(String id, ModelProvider model, String token) {
        return new AiStreamEvent(id, StreamEventType.TOKEN, model, token, null, null, Instant.now());
    }

    public static AiStreamEvent done(String id, ModelProvider model) {
        return new AiStreamEvent(id, StreamEventType.DONE, model, null, null, null, Instant.now());
    }

    public static AiStreamEvent error(String id, ModelProvider model, String message) {
        return new AiStreamEvent(id, StreamEventType.ERROR, model, message, null, null, Instant.now());
    }

    /**
     * 创建引用信息事件 —— 流式结束后发送，包含回答中引用的所有来源。
     *
     * @param id        事件 ID
     * @param citations 引用信息列表
     * @return AiStreamEvent 实例
     */
    public static AiStreamEvent citations(String id, List<Citation> citations) {
        return new AiStreamEvent(id, StreamEventType.CITATIONS, null, null, null, citations, Instant.now());
    }
}
