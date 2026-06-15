package com.trade.model;

import com.trade.enums.MessageRole;

public record AiPromptMessage(
        MessageRole role,
        String content
) {
}
