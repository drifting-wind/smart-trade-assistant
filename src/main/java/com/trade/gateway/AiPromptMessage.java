package com.trade.gateway;

import com.trade.shared.enums.MessageRole;

public record AiPromptMessage(
        MessageRole role,
        String content
) {
}
