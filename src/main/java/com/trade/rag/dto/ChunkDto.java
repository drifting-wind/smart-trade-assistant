package com.trade.rag.dto;

import java.util.Map;

/**
 * 文本块 DTO —— 表示文档被切分后的一个片段。
 *
 * 每个 Chunk 包含：
 * - 原始文本内容
 * - 所属文档 ID 和块索引
 * - 向量（由 Embedding 模型生成）
 * - 元数据（来源、位置等）
 *
 * 分块策略：
 * - 默认 chunkSize=500 字符，chunkOverlap=50 字符
 * - 使用递归分割器，优先在段落、句子边界切分
 * - 重叠部分保证上下文连贯性
 */
public record ChunkDto(
        /**
         * 文档唯一标识 —— 关联到原始文档
         */
        String documentId,

        /**
         * 块索引 —— 该块在文档中的位置（从 0 开始）
         */
        int chunkIndex,

        /**
         * 块文本内容 —— 经过分块后的纯文本，长度约 chunkSize 字符
         */
        String text,

        /**
         * 向量 —— Embedding 模型生成的浮点数组，维度由配置决定（默认 1024）
         * 注意：传输时通常不传递向量，仅在内部使用
         */
        float[] embedding,

        /**
         * 扩展元数据 —— 块级别的附加信息。
         * 常见字段：
         * - pageNumber: 页码（PDF）
         * - section: 章节标题
         * - position: 在文档中的位置（字符偏移量）
         */
        Map<String, Object> metadata
) {
}
