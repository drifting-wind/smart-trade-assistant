package com.example.multiai.model;

import com.example.multiai.enums.ModelProvider;

/** 流式调用的单个 Token 片段 —— 供应商 + 增量文本内容 */
public record AiToken(
        ModelProvider provider,
        String content
) {
}
