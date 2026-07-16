package com.trade.gateway;

import com.trade.conversation.dto.TokenUsageDto;
import com.trade.shared.enums.ModelProvider;

/** 模型原始响应 —— 供应商、生成的文本内容、Token 用量 */
public record AiModelResponse(
        ModelProvider provider,
        String content,
        TokenUsageDto usage
) {
}
