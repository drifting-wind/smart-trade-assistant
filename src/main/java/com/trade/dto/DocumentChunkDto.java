package com.trade.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * 文档块 DTO —— 用于文档溯源定位，返回文档的特定 chunk 内容。
 *
 * 应用场景：
 * - 用户点击引用链接时，查询文档的特定 chunk
 * - 验证 AI 回答的引用来源
 * - 展示文档原文内容
 */
@Schema(description = "文档块（用于溯源定位）")
public record DocumentChunkDto(
        /**
         * 文档 ID —— 文档的唯一标识
         */
        @Schema(description = "文档 ID", example = "doc-abc-123")
        String documentId,

        /**
         * 块索引 —— 文档中的第几个 chunk（从 0 开始）
         */
        @Schema(description = "块索引（从 0 开始）", example = "0")
        int chunkIndex,

        /**
         * 块文本内容 —— 该 chunk 的原文内容
         */
        @Schema(description = "块文本内容", example = "LED面板灯功率为50W，电压范围220V...")
        String text,

        /**
         * 元数据 —— 包含文档标题、页码等信息
         */
        @Schema(description = "元数据", example = "{\"title\":\"产品手册.pdf\",\"page\":1}")
        Map<String, Object> metadata
) {
}
