package com.example.trade.model;

import com.example.trade.enums.MessageRole;

public record AiPromptMessage(
        MessageRole role,
        String content
) {
}
