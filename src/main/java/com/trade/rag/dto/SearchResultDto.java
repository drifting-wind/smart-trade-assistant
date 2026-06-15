package com.trade.rag.dto;

import java.util.List;
import java.util.Map;

/**
 * 搜索结果 DTO —— 语义搜索返回的结果。
 *
 * 包含：
 * - 查询文本和匹配的文档块列表
 * - 每个匹配项包含：文本内容、相似度分数、来源信息
 * - 搜索耗时（毫秒）
 */
public record SearchResultDto(
        /**
         * 查询文本 —— 用户搜索的原始问题
         */
        String query,

        /**
         * 匹配结果列表 —— 按相似度从高到低排序
         */
        List<SearchMatch> matches,

        /**
         * 搜索耗时（毫秒）
         */
        long searchTimeMs
) {
    /**
     * 单条匹配结果
     */
    public record SearchMatch(
            /**
             * 匹配的文本块内容
             */
            String text,

            /**
             * 相似度分数 —— 范围 0~1，越大越相似
             * 使用余弦相似度（COSINE）计算
             */
            double score,

            /**
             * 来源文档 ID
             */
            String documentId,

            /**
             * 块索引 —— 在文档中的位置
             */
            int chunkIndex,

            /**
             * 元数据 —— 包含文档标题、页码等信息
             */
            Map<String, Object> metadata
    ) {
    }
}
