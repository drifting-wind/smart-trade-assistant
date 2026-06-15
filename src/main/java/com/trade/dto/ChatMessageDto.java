package com.trade.dto;

import com.trade.enums.MessageRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 单条对话消息 DTO —— 用于 ChatRequest.history 中传递补充历史消息。
 *
 * 与 OpenAI Chat Completions 协议的 {role, content} 格式一一对应，
 * 在 AiRequestMapper.fromDto() 中转为内部 AiPromptMessage。
 */
public record ChatMessageDto(
        /**
         * 消息角色 —— 必须是 SYSTEM、USER、ASSISTANT 之一。
         * 必填（@NotNull），对应 OpenAI 协议的 role 字段，
         * 决定了 AI 模型将此消息视为"系统指令"、"用户输入"还是"助手回复"。
         */
        @NotNull MessageRole role,

        /**
         * 消息内容 —— 该角色发送的具体文本。
         * 必填（@NotBlank），对应 OpenAI 协议的 content 字段。
         * 例如：USER 角色的内容是用户的历史问题，ASSISTANT 角色的内容是 AI 的历史回答。
         */
        @NotBlank String content
) {
}
