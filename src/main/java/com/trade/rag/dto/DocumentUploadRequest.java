package com.trade.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 文档上传请求 DTO —— 用于 /api/v1/knowledge/documents 接口的 JSON 请求体。
 *
 * 支持两种上传方式：
 * 1. fileUrl：提供文件 URL，服务端下载后解析
 * 2. textContent：直接上传文本内容（适用于网页内容、手动粘贴等场景）
 *
 * 元数据（metadata）用于存储文档附加信息，如：
 * - title: 文档标题
 * - author: 作者
 * - category: 分类（policy/product/contract 等）
 * - tags: 标签列表
 * - source: 来源（upload/import/scrape 等）
 */
public record DocumentUploadRequest(
        /**
         * 文档标题 —— 用于展示和检索，必填。
         * 如果为空，系统会尝试从文件名或内容中提取。
         */
        @NotBlank @Size(max = 200) String title,

        /**
         * 文件 URL —— 提供可下载的文件地址。
         * 支持 HTTP/HTTPS 协议，服务端会下载并解析。
         * 与 textContent 二选一，不能同时为空。
         */
        String fileUrl,

        /**
         * 文本内容 —— 直接上传的文本内容。
         * 与 fileUrl 二选一，不能同时为空。
         * 适用于网页内容、手动粘贴等场景。
         */
        String textContent,

        /**
         * 文档类型 —— 标识内容格式，用于选择解析策略。
         * 可选值：pdf, doc, docx, txt, markdown, html
         * 如果为空，系统会根据 fileUrl 后缀或内容自动推断。
         */
        String contentType,

        /**
         * 扩展元数据 —— 键值对形式的附加信息。
         * 会原样存入 Milvus 的 metadata 字段，可用于后续过滤检索。
         * 示例：{"category": "policy", "department": "sales", "language": "zh"}
         */
        Map<String, Object> metadata
) {
}
