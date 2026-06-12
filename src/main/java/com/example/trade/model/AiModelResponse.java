package com.example.trade.model;

import com.example.trade.dto.TokenUsageDto;
import com.example.trade.enums.ModelProvider;

/** 模型原始响应 —— 供应商、生成的文本内容、Token 用量 */
public record AiModelResponse(
        ModelProvider provider,
        String content,
        TokenUsageDto usage
) {
}
