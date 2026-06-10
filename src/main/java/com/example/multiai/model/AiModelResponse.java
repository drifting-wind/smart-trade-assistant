package com.example.multiai.model;

import com.example.multiai.dto.TokenUsageDto;
import com.example.multiai.enums.ModelProvider;

/** 模型原始响应 —— 供应商、生成的文本内容、Token 用量 */
public record AiModelResponse(
        ModelProvider provider,
        String content,
        TokenUsageDto usage
) {
}
