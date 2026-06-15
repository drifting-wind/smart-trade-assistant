package com.trade.rag.dto;

import java.time.Instant;
import java.util.Map;

/**
 * 文档上传响应 DTO —— 返回文档摄入的结果信息。
 *
 * 包含：
 * - documentId：文档唯一标识，后续用于删除/更新
 * - chunkCount：分块数量，反映文档长度
 * - status：摄入状态（success/partial/failed）
 * - message：状态说明（如部分分块失败的原因）
 */
public record DocumentUploadResponse(
        /**
         * 文档唯一标识 —— UUID，用于后续删除或查询文档状态
         */
        String documentId,

        /**
         * 文档标题
         */
        String title,

        /**
         * 分块数量 —— 文档被切分为多少个文本块
         */
        int chunkCount,

        /**
         * 摄入状态 —— success（全部成功）/ partial（部分成功）/ failed（全部失败）
         */
        String status,

        /**
         * 状态说明 —— 如"部分分块因长度过长被截断"等
         */
        String message,

        /**
         * 扩展元数据 —— 原样返回请求中的 metadata
         */
        Map<String, Object> metadata,

        /**
         * 创建时间
         */
        Instant createdAt
) {
    /**
     * 创建成功响应的便捷方法
     */
    public static DocumentUploadResponse success(String documentId, String title, int chunkCount, Map<String, Object> metadata) {
        return new DocumentUploadResponse(documentId, title, chunkCount, "success", null, metadata, Instant.now());
    }

    /**
     * 创建失败响应的便捷方法
     */
    public static DocumentUploadResponse failed(String title, String errorMessage) {
        return new DocumentUploadResponse(null, title, 0, "failed", errorMessage, null, Instant.now());
    }
}
