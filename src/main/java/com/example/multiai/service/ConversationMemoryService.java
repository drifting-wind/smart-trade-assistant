package com.example.multiai.service;

import com.example.multiai.config.AiGatewayProperties;
import com.example.multiai.enums.MessageRole;
import com.example.multiai.model.AiPromptMessage;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话记忆服务 —— 基于 Caffeine 内存缓存维护多轮对话历史。
 *
 * 技术实现：
 * - 使用 Caffeine Cache，key 为 conversationId，value 为 AiPromptMessage 列表
 * - expireAfterAccess：超过 TTL（默认 2h）后自动过期
 * - maximumSize：100,000 个会话上限
 * - appendTurn 时限制历史消息数量（默认 16 条），超限时截掉最旧的
 *
 * 与 Redis/数据库方案的区别：数据存在内存中，应用重启即丢失，但读写速度最快，无外部依赖。
 */
@Service
public class ConversationMemoryService {

    private final Cache<String, List<AiPromptMessage>> cache;
    private final int maxHistoryMessages;

    public ConversationMemoryService(AiGatewayProperties properties) {
        this.cache = Caffeine.newBuilder()
                .expireAfterAccess(properties.getConversationTtl())
                .maximumSize(100_000)
                .build();
        this.maxHistoryMessages = properties.getMaxHistoryMessages();
    }

    public List<AiPromptMessage> history(String conversationId) {
        List<AiPromptMessage> messages = cache.getIfPresent(conversationId);
        return messages == null ? List.of() : List.copyOf(messages);
    }

    public void appendTurn(String conversationId, String user, String assistant) {
        List<AiPromptMessage> messages = new ArrayList<>(history(conversationId));
        messages.add(new AiPromptMessage(MessageRole.USER, user));
        messages.add(new AiPromptMessage(MessageRole.ASSISTANT, assistant));
        if (messages.size() > maxHistoryMessages) {
            messages = new ArrayList<>(messages.subList(messages.size() - maxHistoryMessages, messages.size()));
        }
        cache.put(conversationId, messages);
    }
}
