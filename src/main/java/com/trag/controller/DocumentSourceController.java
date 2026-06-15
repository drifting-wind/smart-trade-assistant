package com.trag.controller;

import com.trade.dto.DocumentChunkDto;
import com.trade.rag.MilvusVectorStoreClient;
import io.milvus.grpc.QueryResults;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.QueryResultsWrapper.RowRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;

/**
 * 文档溯源控制器 —— 提供文档原文查询接口，支持溯源定位。
 *
 * 功能：
 * 1. 根据 documentId 查询文档的所有 chunk
 * 2. 根据 documentId + chunkIndex 查询特定 chunk
 * 3. 返回原文内容，用于溯源验证
 */
@RestController
@RequestMapping("/api/v1/knowledge/documents")
public class DocumentSourceController {

    private static final Logger log = LoggerFactory.getLogger(DocumentSourceController.class);

    private final MilvusVectorStoreClient vectorStoreClient;

    public DocumentSourceController(MilvusVectorStoreClient vectorStoreClient) {
        this.vectorStoreClient = vectorStoreClient;
    }

    /**
     * 查询文档的特定 chunk（用于溯源定位）。
     *
     * @param documentId 文档 ID
     * @param chunkIndex 文档块索引
     * @return 文档块内容
     */
    @GetMapping("/{documentId}/chunks/{chunkIndex}")
    public Mono<ResponseEntity<DocumentChunkDto>> getChunk(
            @PathVariable String documentId,
            @PathVariable int chunkIndex
    ) {
        log.info("🔍 查询文档原文: documentId={}, chunkIndex={}", documentId, chunkIndex);

        return Mono.<ResponseEntity<DocumentChunkDto>>fromCallable(() -> {
            // 构建查询表达式
            String expr = String.format("document_id == \"%s\" && chunk_index == %d", documentId, chunkIndex);

            // 查询 Milvus
            io.milvus.param.R<QueryResults> response = vectorStoreClient.query(expr);

            if (response.getStatus() != io.milvus.param.R.Status.Success.getCode()) {
                log.warn("⚠️ 查询文档原文失败: {}", response.getMessage());
                return ResponseEntity.<DocumentChunkDto>notFound().build();
            }

            // 解析结果
            QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
            List<RowRecord> records = wrapper.getRowRecords();

            if (records.isEmpty()) {
                log.warn("⚠️ 未找到文档块: documentId={}, chunkIndex={}", documentId, chunkIndex);
                return ResponseEntity.notFound().build();
            }

            // 构建返回结果
            RowRecord record = records.get(0);
            DocumentChunkDto chunk = new DocumentChunkDto(
                    documentId,
                    chunkIndex,
                    getStringValue(record, "text"),
                    getMetadataValue(record, "metadata")
            );

            log.info("✅ 查询文档原文成功: documentId={}, chunkIndex={}", documentId, chunkIndex);
            return ResponseEntity.ok(chunk);
        })
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorResume(e -> {
            log.error("❌ 查询文档原文异常: {}", e.getMessage(), e);
            return Mono.just(ResponseEntity.<DocumentChunkDto>internalServerError().build());
        });
    }

    /**
     * 查询文档的所有 chunk。
     *
     * @param documentId 文档 ID
     * @return 文档块列表
     */
    @GetMapping("/{documentId}/chunks")
    public Mono<ResponseEntity<List<DocumentChunkDto>>> getAllChunks(@PathVariable String documentId) {
        log.info("🔍 查询文档所有 chunk: documentId={}", documentId);

        return Mono.<ResponseEntity<List<DocumentChunkDto>>>fromCallable(() -> {
            // 构建查询表达式
            String expr = String.format("document_id == \"%s\"", documentId);

            // 查询 Milvus
            io.milvus.param.R<QueryResults> response = vectorStoreClient.query(expr);

            if (response.getStatus() != io.milvus.param.R.Status.Success.getCode()) {
                log.warn("⚠️ 查询文档失败: {}", response.getMessage());
                return ResponseEntity.<List<DocumentChunkDto>>notFound().build();
            }

            // 解析结果
            QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
            List<RowRecord> records = wrapper.getRowRecords();

            List<DocumentChunkDto> chunks = new ArrayList<>();
            for (RowRecord record : records) {
                int chunkIndex = getIntValue(record, "chunk_index");
                String text = getStringValue(record, "text");
                Map<String, Object> metadata = getMetadataValue(record, "metadata");

                chunks.add(new DocumentChunkDto(documentId, chunkIndex, text, metadata));
            }

            // 按 chunkIndex 排序
            chunks.sort(Comparator.comparingInt(DocumentChunkDto::chunkIndex));

            log.info("✅ 查询文档成功: documentId={}, chunkCount={}", documentId, chunks.size());
            return ResponseEntity.ok(chunks);
        })
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorResume(e -> {
            log.error("❌ 查询文档异常: {}", e.getMessage(), e);
            return Mono.just(ResponseEntity.<List<DocumentChunkDto>>internalServerError().build());
        });
    }

    // ==================== 辅助方法 ====================

    private String getStringValue(RowRecord record, String fieldName) {
        Object value = record.get(fieldName);
        return value != null ? value.toString() : "";
    }

    private int getIntValue(RowRecord record, String fieldName) {
        Object value = record.get(fieldName);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMetadataValue(RowRecord record, String fieldName) {
        Object value = record.get(fieldName);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return Collections.emptyMap();
    }
}
