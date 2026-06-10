package com.example.multiai.service;

import com.example.multiai.dto.ChatMessageDto;
import com.example.multiai.dto.ChatRequest;
import com.example.multiai.dto.ProcessRequest;
import com.example.multiai.enums.MessageRole;
import com.example.multiai.enums.ScenarioType;
import com.example.multiai.model.AiPromptMessage;
import com.example.multiai.model.AiPromptRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 请求转换器 —— 将外部 DTO（ChatRequest / ProcessRequest）转为内部 AiPromptRequest。
 *
 * 职责：
 * 1. 组装消息列表：System Prompt + 历史记忆 + 用户当前输入
 * 2. 分配唯一 requestId（UUID）
 * 3. 确保 conversationId 有效（空则自动生成）
 * 4. 标记场景类型（QA 或 FLOW）
 *
 * 消息组装顺序（很重要）：
 * - SYSTEM：系统提示词（定义 AI 的行为和输出格式）
 * - 历史消息（来自 ConversationMemoryService 的缓存）
 * - 额外历史（ChatRequest 中携带的 DTO 格式历史，可选）
 * - USER：当前用户输入
 *
 * 这个顺序符合 OpenAI Chat Completions API 的协议要求。
 */
@Component
public class AiRequestMapper {

    /**
     * 将聊天请求转为 prompt —— 用于普通问答场景。
     *
     * 消息组装：
     * 1. System Prompt（qaSystemPrompt：要求直接、专业地回答问题）
     * 2. 历史记忆（从 Caffeine 缓存读取的会话历史）
     * 3. 额外历史（request.history()，前端可携带补充对话）
     * 4. 当前问题（request.question()）
     */
    public AiPromptRequest chatToPrompt(ChatRequest request, List<AiPromptMessage> memory, String systemPrompt) {
        List<AiPromptMessage> messages = new ArrayList<>();
        messages.add(new AiPromptMessage(MessageRole.SYSTEM, systemPrompt));
        messages.addAll(memory);
        if (request.history() != null) {
            messages.addAll(request.history().stream().map(this::fromDto).toList());
        }
        messages.add(new AiPromptMessage(MessageRole.USER, request.question()));
        return new AiPromptRequest(
                UUID.randomUUID().toString(),
                ConversationIds.ensure(request.conversationId()),
                ScenarioType.QA,
                messages,
                request.metadata()
        );
    }

    /**
     * 将流程请求转为 prompt —— 用于业务流程规划场景。
     *
     * 与 chatToPrompt 的区别：
     * - 用户输入被格式化为结构化文本（流程名称、当前状态、目标、变量）
     * - 场景类型为 FLOW（影响路由打分）
     * - System Prompt 要求 AI 输出 JSON 格式的流程计划
     */
    public AiPromptRequest flowToPrompt(ProcessRequest request, List<AiPromptMessage> memory, String systemPrompt) {
        List<AiPromptMessage> messages = new ArrayList<>();
        messages.add(new AiPromptMessage(MessageRole.SYSTEM, systemPrompt));
        messages.addAll(memory);
        messages.add(new AiPromptMessage(MessageRole.USER, """
                流程名称：%s
                当前状态：%s
                目标：%s
                变量：%s
                """.formatted(
                blankToDefault(request.processName(), "未命名流程"),
                blankToDefault(request.currentState(), "暂无"),
                request.goal(),
                request.variables() == null ? "{}" : request.variables()
        )));
        return new AiPromptRequest(
                UUID.randomUUID().toString(),
                ConversationIds.ensure(request.conversationId()),
                ScenarioType.FLOW,
                messages,
                request.variables()
        );
    }

    private AiPromptMessage fromDto(ChatMessageDto dto) {
        return new AiPromptMessage(dto.role(), dto.content());
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
