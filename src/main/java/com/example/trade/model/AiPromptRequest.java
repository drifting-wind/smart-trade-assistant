package com.example.trade.model;

import com.example.trade.enums.ScenarioType;

import java.util.List;
import java.util.Map;

/**
 * 内部 Prompt 请求 —— 服务层统一使用的请求结构。
 *
 * 包含完整的消息列表（System + 历史 + 用户输入），
 * joinedContent() 用于拼接所有消息内容，供路由打分和缓存 key 使用。
 */
public record AiPromptRequest(
        String requestId,
        String conversationId,
        //请求场景类型 —— 影响路由打分（QA 侧重推理，FLOW 侧重规划）
        ScenarioType scenario,
        //joinedContent() 用于拼接所有消息内容，供路由打分和缓存 key 使用
        List<AiPromptMessage> messages,
        Map<String, Object> metadata
) {
    public String joinedContent() {
        StringBuilder builder = new StringBuilder();
        for (AiPromptMessage message : messages) {
            builder.append(message.content()).append('\n');
        }
        return builder.toString();
    }
}
