package com.trade.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * 引用信息 DTO —— 记录 AI 回答中引用的文档来源，支持溯源定位。
 *
 * 应用场景：
 * - AI 回答中标记的 [1]、[2] 等引用编号
 * - 点击引用链接跳转到原文
 * - 验证回答的数据来源
 */
@Schema(description = "引用信息（用于溯源定位）")
public record Citation(
        /**
         * 引用序号 —— 对应 AI 回答中的 [1]、[2] 等编号（从 1 开始）
         */
        @Schema(description = "引用序号（从 1 开始）", example = "1")
        int index,

        /**
         * 文档 ID —— 引用的文档唯一标识
         */
        @Schema(description = "文档 ID", example = "doc-abc-123")
        String documentId,

        /**
         * 块索引 —— 文档中的第几个 chunk（从 0 开始）
         */
        @Schema(description = "块索引（从 0 开始）", example = "0")
        int chunkIndex,

        /**
         * 文档标题 —— 引用的文档标题
         */
        @Schema(description = "文档标题", example = "产品手册.pdf")
        String title,

        /**
         * 内容摘要 —— 引用内容的前 100 字符
         */
        @Schema(description = "内容摘要（前 100 字符）", example = "LED面板灯功率为50W，电压范围220V...")
        String snippet,

        /**
         * 相关性分数 —— 该 chunk 与问题的相似度（0~1）
         */
        @Schema(description = "相关性分数（0~1）", example = "0.95")
        double score,

        /**
         * 元数据 —— 包含文档的额外信息（如页码、作者等）
         */
        @Schema(description = "元数据", example = "{\"page\":1,\"author\":\"技术部\"}")
        Map<String, Object> metadata
) {
}
