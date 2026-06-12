package com.example.trade.rag.controller;

import com.example.trade.dto.AiStreamEvent;
import com.example.trade.dto.ChatRequest;
import com.example.trade.dto.ChatResponse;
import com.example.trade.rag.DocumentIngestionService;
import com.example.trade.rag.RagOrchestrationService;
import com.example.trade.rag.dto.DocumentUploadRequest;
import com.example.trade.rag.dto.DocumentUploadResponse;
import com.example.trade.rag.dto.SearchResultDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * RAG 知识库 REST 接口 —— /api/v1/knowledge
 *
 * 提供以下功能：
 * 1. 文档摄入：上传 PDF/DOCX 文件，自动解析、分块、Embedding、写入 Milvus
 * 2. 知识检索：语义搜索，返回相关文档块
 * 3. RAG 问答：检索增强的 AI 对话
 *
 * 技术点：
 * - @Validated 开启参数校验
 * - Flux/Mono 响应式类型
 * - SSE (Server-Sent Events) 流式输出
 * - multipart/form-data 文件上传
 *
 * 接口列表：
 * - POST /api/v1/knowledge/documents/upload  上传文件
 * - POST /api/v1/knowledge/documents         摄入文本
 * - GET  /api/v1/knowledge/search            语义搜索
 * - POST /api/v1/knowledge/chat              RAG 问答（同步）
 * - POST /api/v1/knowledge/chat/stream       RAG 问答（流式）
 * - DELETE /api/v1/knowledge/documents/{id}  删除文档
 */
@Validated
@RestController
@RequestMapping("/api/v1/knowledge")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final DocumentIngestionService ingestionService;
    private final RagOrchestrationService ragService;

    public RagController(
            DocumentIngestionService ingestionService,
            RagOrchestrationService ragService
    ) {
        this.ingestionService = ingestionService;
        this.ragService = ragService;
    }

    /**
     * 上传单个文件并摄入知识库。
     *
     * 支持的文件类型：PDF、DOC、DOCX、TXT、Markdown
     *
     * 请求格式：multipart/form-data
     * - file: 文件内容（必填）
     * - title: 文档标题（可选，默认使用文件名）
     * - contentType: 文件类型（可选，自动推断）
     * - metadata: 元数据（可选，JSON 格式）
     *
     * 响应示例：
     * {
     *   "documentId": "abc-123",
     *   "title": "产品手册.pdf",
     *   "chunkCount": 42,
     *   "status": "success",
     *   "message": null,
     *   "metadata": {"category": "product"},
     *   "createdAt": "2026-06-10T10:30:00Z"
     * }
     */
    @PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<DocumentUploadResponse> uploadDocument(
            @RequestPart("file") FilePart file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "contentType", required = false) String contentType,
            @RequestParam(value = "metadata", required = false) String metadataJson
    ) {
        log.info("📤 接收单文件上传: {}", file.filename());

        // 校验文件类型
        String filename = file.filename();
        if (!isSupportedFileType(filename)) {
            return Mono.error(new IllegalArgumentException(
                    "不支持的文件类型: " + filename + "。支持的类型：PDF、DOC、DOCX、TXT、Markdown"
            ));
        }

        // 构建上传请求
        DocumentUploadRequest request = new DocumentUploadRequest(
                title != null ? title : filename,
                null, // fileUrl
                null, // textContent
                contentType,
                parseMetadata(metadataJson)
        );

        return ingestionService.ingestFromFile(file, request);
    }

    /**
     * 批量上传文件并摄入知识库。
     *
     * 支持的文件类型：PDF、DOC、DOCX、TXT、Markdown
     * 最大文件数量：10 个
     *
     * 请求格式：multipart/form-data
     * - files: 文件列表（必填，支持多文件）
     * - titlePrefix: 标题前缀（可选，会自动附加文件名）
     * - metadata: 元数据（可选，JSON 格式，应用于所有文件）
     *
     * 响应示例：
     * {
     *   "totalCount": 3,
     *   "successCount": 2,
     *   "failedCount": 1,
     *   "results": [
     *     {
     *       "filename": "产品手册.pdf",
     *       "documentId": "abc-123",
     *       "chunkCount": 42,
     *       "status": "success",
     *       "error": null
     *     },
     *     {
     *       "filename": "不支持的文件.exe",
     *       "documentId": null,
     *       "chunkCount": 0,
     *       "status": "failed",
     *       "error": "不支持的文件类型"
     *     }
     *   ],
     *   "processedAt": "2026-06-10T10:30:00Z"
     * }
     */
    @PostMapping(value = "/documents/upload/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<com.example.trade.rag.dto.BatchUploadResponse> uploadDocuments(
            @RequestPart("files") List<FilePart> files,
            @RequestParam(value = "titlePrefix", required = false) String titlePrefix,
            @RequestParam(value = "metadata", required = false) String metadataJson
    ) {
        if (files == null || files.isEmpty()) {
            return Mono.error(new IllegalArgumentException("文件列表不能为空"));
        }
        if (files.size() > 10) {
            return Mono.error(new IllegalArgumentException("单次批量上传最多支持 10 个文件"));
        }

        log.info("📤 接收批量文件上传: {} 个文件", files.size());

        // 校验所有文件类型
        for (FilePart file : files) {
            if (!isSupportedFileType(file.filename())) {
                return Mono.error(new IllegalArgumentException(
                        "不支持的文件类型: " + file.filename() + "。支持的类型：PDF、DOC、DOCX、TXT、Markdown"
                ));
            }
        }

        return ingestionService.ingestFromFiles(files, titlePrefix, metadataJson);
    }

    /**
     * 检查文件类型是否支持。
     * 支持：PDF、DOC、DOCX、TXT、Markdown
     */
    private boolean isSupportedFileType(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        return lower.endsWith(".pdf")
                || lower.endsWith(".doc")
                || lower.endsWith(".docx")
                || lower.endsWith(".txt")
                || lower.endsWith(".md")
                || lower.endsWith(".markdown");
    }

    /**
     * 摄入纯文本内容到知识库。
     *
     * 适用于：
     * - 网页内容抓取后摄入
     * - 手动粘贴文本
     * - 从其他系统同步数据
     *
     * 请求示例：
     * {
     *   "title": "外贸流程指南",
     *   "textContent": "第一步：客户询盘...",
     *   "contentType": "txt",
     *   "metadata": {"category": "process", "department": "sales"}
     * }
     */
    @PostMapping("/documents")
    public Mono<DocumentUploadResponse> ingestText(@Valid @RequestBody DocumentUploadRequest request) {
        log.info("📝 接收文本摄入: {}", request.title());
        return ingestionService.ingestText(request.textContent(), request);
    }

    /**
     * 语义搜索 —— 在知识库中搜索与问题相关的文档块。
     *
     * 不调用 AI 模型，仅返回检索结果。
     *
     * 响应示例：
     * {
     *   "query": "如何报价？",
     *   "matches": [
     *     {
     *       "text": "报价时应考虑...",
     *       "score": 0.92,
     *       "documentId": "abc-123",
     *       "chunkIndex": 5,
     *       "metadata": {"title": "报价指南.pdf"}
     *     }
     *   ],
     *   "searchTimeMs": 45
     * }
     */
    @GetMapping("/search")
    public Mono<SearchResultDto> search(@RequestParam String query) {
        log.info("🔍 语义搜索: {}", query);
        return ragService.search(query);
    }

    /**
     * RAG 问答（同步） —— 检索增强的 AI 对话。
     *
     * 流程：
     * 1. 将问题转为向量
     * 2. 在 Milvus 搜索相关文档
     * 3. 将检索结果注入 Prompt
     * 4. 调用 AI 模型生成回答
     *
     * 请求格式与 /api/v1/chat/completions 相同：
     * {
     *   "question": "如何开发新客户？",
     *   "preferredModel": "DEEPSEEK"
     * }
     *
     * 注意：RAG 流程由 RagOrchestrationService 自动处理，无需设置 useKnowledgeBase。
     */
    @PostMapping("/chat")
    public Mono<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        log.info("💬 RAG 问答: {}", request.question());
        return ragService.completeWithRetrieval(request);
    }

    /**
     * RAG 问答（流式） —— SSE 推送检索结果和 AI 回答。
     *
     * SSE 事件流：
     * 1. route 事件 —— 包含检索结果数量
     * 2. token 事件（N 次）—— AI 生成的每个 token
     * 3. done 事件 —— 完成信号
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AiStreamEvent>> streamChat(@Valid @RequestBody ChatRequest request) {
        log.info("💬 RAG 流式问答: {}", request.question());
        return ragService.streamWithRetrieval(request)
                .map(event -> ServerSentEvent.<AiStreamEvent>builder()
                        .id(event.id())
                        .event(event.type().name().toLowerCase())
                        .data(event)
                        .build());
    }

    /**
     * 删除文档 —— 从知识库中删除指定文档的所有向量。
     *
     * 注意：仅删除向量数据，不删除原始文件。
     */
    @DeleteMapping("/documents/{documentId}")
    public Mono<Void> deleteDocument(@PathVariable String documentId) {
        log.info("🗑️ 删除文档: {}", documentId);
        return ingestionService.deleteDocument(documentId);
    }

    /**
     * 解析 JSON 格式的元数据。
     */
    private Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return new ObjectMapper()
                    .readValue(metadataJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("⚠️ 元数据 JSON 解析失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }
}
