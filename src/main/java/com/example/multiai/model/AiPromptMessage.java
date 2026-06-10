package com.example.multiai.model;

import com.example.multiai.enums.MessageRole;

public record AiPromptMessage(
        MessageRole role,
        String content
) {
}
