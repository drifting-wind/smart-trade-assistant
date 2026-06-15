package com.trade.rag.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批量文档上传响应 DTO —— 返回批量上传的结果信息。
 *
 * 包含：
 * - totalCount：总文件数
 * - successCount：成功数量
 * - failedCount：失败数量
 * - results：每个文件的详细结果
 */
public record BatchUploadResponse(
        /**
         * 总文件数
         */
        int totalCount,

        /**
         * 成功数量
         */
        int successCount,

        /**
         * 失败数量
         */
        int failedCount,

        /**
         * 每个文件的详细结果
         */
        List<FileUploadResult> results,

        /**
         * 处理时间
         */
        Instant processedAt
) {
    /**
     * 单个文件上传结果
     */
    public record FileUploadResult(
            /**
             * 文件名
             */
            String filename,

            /**
             * 文档 ID（成功时返回）
             */
            String documentId,

            /**
             * 分块数量
             */
            int chunkCount,

            /**
             * 状态：success / failed
             */
            String status,

            /**
             * 错误信息（失败时返回）
             */
            String error
    ) {}
}
