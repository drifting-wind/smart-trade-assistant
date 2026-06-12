package com.example.trade.rag;

import com.example.trade.config.AiGatewayProperties;
import com.example.trade.exception.VectorStoreException;
import com.example.trade.rag.dto.DocumentUploadRequest;
import com.example.trade.rag.dto.DocumentUploadResponse;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.*;

/**
 * 文档摄入服务 —— 实现"文档 → 文本 → 分块 → Embedding → Milvus"的完整流程。
 *
 * 技术栈：
 * - LangChain4j DocumentParser：解析 PDF/DOCX 为纯文本
 * - LangChain4j DocumentSplitter：递归分块，支持重叠
 * - LangChain4j EmbeddingModel：文本向量化
 * - MilvusVectorStoreClient：向量存储
 *
 * 摄入流程：
 * 1. 获取文档内容（从 FilePart 或 URL 下载）
 * 2. 解析文档为纯文本（LangChain4j DocumentParser）
 * 3. 分块（DocumentSplitter，chunkSize=500, overlap=50）
 * 4. Embedding（调用 /v1/embeddings 转为向量）
 * 5. 写入 Milvus（批量插入）
 *
 * 支持的文件类型：
 * - PDF：使用 PdfDocumentParser
 * - DOCX：使用 docx4j 解析器（需额外依赖）
 * - TXT/Markdown：使用 TextDocumentParser
 *
 * 注意：LangChain4j 是阻塞库，所有调用通过 subscribeOn(boundedElastic) 包装。
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final EmbeddingService embeddingService;
    private final MilvusVectorStoreClient vectorStoreClient;
    private final Bm25IndexService bm25IndexService;
    private final AiGatewayProperties.Rag ragProperties;
    private final WebClient webClient;

    public DocumentIngestionService(
            EmbeddingService embeddingService,
            MilvusVectorStoreClient vectorStoreClient,
            Bm25IndexService bm25IndexService,
            AiGatewayProperties properties,
            WebClient.Builder webClientBuilder
    ) {
        this.embeddingService = embeddingService;
        this.vectorStoreClient = vectorStoreClient;
        this.bm25IndexService = bm25IndexService;
        this.ragProperties = properties.getRag();
        this.webClient = webClientBuilder.build();
    }

    /**
     * 摄入文档（从文件上传） —— 处理前端上传的单个文件。
     *
     * 支持的文件类型：PDF、DOCX、TXT、Markdown
     *
     * 流程：
     * 1. 读取 FilePart 内容为字节数组
     * 2. 根据 contentType 选择 DocumentParser
     * 3. 解析为纯文本 → 分块 → Embedding → 写入 Milvus
     *
     * @param file 上传的文件
     * @param request 上传请求元数据
     * @return 摄入结果
     */
    public Mono<DocumentUploadResponse> ingestFromFile(FilePart file, DocumentUploadRequest request) {
        String documentId = UUID.randomUUID().toString();

        return readFileBytes(file)
                .flatMap(bytes -> parseAndIngest(documentId, bytes, request))
                .onErrorMap(e -> new VectorStoreException("文件摄入失败: " + e.getMessage(), e));
    }

    /**
     * 批量摄入文档（从多个文件上传） —— 处理前端上传的多个文件。
     *
     * 支持的文件类型：PDF、DOC、DOCX、TXT、Markdown
     *
     * @param files 上传的文件列表
     * @param titlePrefix 标题前缀（可选）
     * @param metadataJson 元数据 JSON（可选）
     * @return 批量摄入结果
     */
    public Mono<com.example.trade.rag.dto.BatchUploadResponse> ingestFromFiles(
            List<FilePart> files,
            String titlePrefix,
            String metadataJson
    ) {
        Map<String, Object> metadata = parseMetadata(metadataJson);
        List<Mono<com.example.trade.rag.dto.BatchUploadResponse.FileUploadResult>> monos = files.stream()
                .map(file -> {
                    DocumentUploadRequest request = new DocumentUploadRequest(
                            titlePrefix != null && !titlePrefix.isBlank()
                                    ? titlePrefix + " - " + file.filename()
                                    : file.filename(),
                            null,
                            null,
                            null,
                            metadata
                    );
                    return ingestFromFile(file, request)
                            .map(resp -> new com.example.trade.rag.dto.BatchUploadResponse.FileUploadResult(
                                    file.filename(),
                                    resp.documentId(),
                                    resp.chunkCount(),
                                    "success",
                                    null
                            ))
                            .onErrorResume(e -> Mono.just(
                                    new com.example.trade.rag.dto.BatchUploadResponse.FileUploadResult(
                                            file.filename(),
                                            null,
                                            0,
                                            "failed",
                                            e.getMessage()
                                    )
                            ));
                })
                .toList();

        return Mono.zip(monos, results -> {
            List<com.example.trade.rag.dto.BatchUploadResponse.FileUploadResult> fileResults = new ArrayList<>();
            int successCount = 0;
            int failedCount = 0;
            for (Object obj : results) {
                com.example.trade.rag.dto.BatchUploadResponse.FileUploadResult r =
                        (com.example.trade.rag.dto.BatchUploadResponse.FileUploadResult) obj;
                fileResults.add(r);
                if ("success".equals(r.status())) {
                    successCount++;
                } else {
                    failedCount++;
                }
            }
            return new com.example.trade.rag.dto.BatchUploadResponse(
                    files.size(),
                    successCount,
                    failedCount,
                    fileResults,
                    Instant.now()
            );
        });
    }

    /**
     * 解析 JSON 格式的元数据
     */
    private Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(metadataJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("⚠️ 元数据 JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 摄入文档（从 URL 下载） —— 处理远程文件 URL。
     *
     * 流程：
     * 1. 通过 WebClient 下载文件字节
     * 2. 解析 → 分块 → Embedding → 写入 Milvus
     *
     * @param fileUrl 文件 URL
     * @param request 上传请求元数据
     * @return 摄入结果
     */
    public Mono<DocumentUploadResponse> ingestFromUrl(String fileUrl, DocumentUploadRequest request) {
        String documentId = UUID.randomUUID().toString();

        return downloadFileBytes(fileUrl)
                .flatMap(bytes -> parseAndIngest(documentId, bytes, request))
                .onErrorMap(e -> new VectorStoreException("URL 摄入失败: " + e.getMessage(), e));
    }

    /**
     * 摄入纯文本内容 —— 处理直接上传的文本。
     *
     * 流程：
     * 1. 分块 → Embedding → 写入 Milvus
     *
     * @param text 文本内容
     * @param request 上传请求元数据
     * @return 摄入结果
     */
    public Mono<DocumentUploadResponse> ingestText(String text, DocumentUploadRequest request) {
        String documentId = UUID.randomUUID().toString();
        return ingestText(documentId, text, request);
    }

    /**
     * 解析文件并摄入 —— 根据文件类型选择解析器。
     *
     * 改进：自动检测文件实际格式，解决扩展名与实际格式不匹配的问题。
     * 例如：文件扩展名是 .docx，但实际是旧版 .doc 格式（OLE2），则自动切换到 DOC 解析器。
     */
    private Mono<DocumentUploadResponse> parseAndIngest(String documentId, byte[] bytes, DocumentUploadRequest request) {
        String contentType = request.contentType();
        if (contentType == null) {
            contentType = detectContentType(request.title());
        }

        // 自动检测文件实际格式，解决扩展名与实际格式不匹配的问题
        String actualType = detectActualContentType(bytes, contentType);
        if (!actualType.equals(contentType)) {
            log.info("🔄 文件实际类型与扩展名不匹配: 扩展名={}, 实际类型={}", contentType, actualType);
            contentType = actualType;
        }

        // 解析文档为纯文本
        String text = parseDocument(bytes, contentType);
        log.info("📄 文档解析完成: {}, 文本长度: {} 字符", request.title(), text.length());

        // 摄入文本
        return ingestText(documentId, text, request);
    }

    /**
     * 检测文件实际内容类型（通过文件头字节判断）。
     *
     * OLE2 (旧版 .doc): 文件头为 D0 CF 11 E0 A1 B1 1A E1
     * OOXML (新版 .docx): 文件头为 50 4B 03 04 (ZIP 格式)
     */
    private String detectActualContentType(byte[] bytes, String defaultType) {
        if (bytes == null || bytes.length < 8) {
            return defaultType;
        }

        // 检查 OLE2 格式（旧版 .doc）
        if (bytes[0] == (byte) 0xD0 && bytes[1] == (byte) 0xCF &&
            bytes[2] == (byte) 0x11 && bytes[3] == (byte) 0xE0) {
            return "doc";
        }

        // 检查 OOXML 格式（新版 .docx, .xlsx, .pptx）- ZIP 格式
        if (bytes[0] == (byte) 0x50 && bytes[1] == (byte) 0x4B &&
            bytes[2] == (byte) 0x03 && bytes[3] == (byte) 0x04) {
            return "docx";
        }

        // 检查 PDF 格式
        if (bytes[0] == (byte) 0x50 && bytes[1] == (byte) 0x44 &&
            bytes[2] == (byte) 0x46 && bytes[3] == (byte) 0x2D) {
            return "pdf";
        }

        return defaultType;
    }

    /**
     * 解析文档为纯文本 —— 根据文件类型选择对应的解析器。
     *
     * 支持：
     * - PDF: Apache PDFBox（替代 LangChain4j PdfDocumentParser）
     * - DOCX: Apache POI XWPF（替代 LangChain4j DOCX Parser）
     * - TXT/Markdown: 直接解码为字符串
     *
     * @param bytes 文件字节内容
     * @param contentType 文件类型
     * @return 解析后的纯文本
     */
    private String parseDocument(byte[] bytes, String contentType) {
        if (contentType == null) {
            throw new VectorStoreException("无法确定文件类型");
        }

        switch (contentType.toLowerCase()) {
            case "pdf":
                return parsePdf(bytes);

            case "docx":
                return parseDocx(bytes);

            case "doc":
                return parseDoc(bytes);

            case "txt":
            case "markdown":
            case "md":
            case "html":
            default:
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * 解析 PDF 文件 —— 使用 Apache PDFBox 提取文本。
     *
     * PDFBox 是 Apache 开源的 PDF 库，支持提取文本、图片等。
     */
    private String parsePdf(byte[] bytes) {
        try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.pdmodel.PDDocument.load(
                new java.io.ByteArrayInputStream(bytes))) {
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            return stripper.getText(document);
        } catch (Exception e) {
            throw new VectorStoreException("PDF 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 DOCX 文件 —— 使用 Apache POI XWPF 提取文本（OOXML 格式）。
     *
     * 注意：需要额外添加 Apache POI 依赖：
     * <dependency>
     *   <groupId>org.apache.poi</groupId>
     *   <artifactId>poi-ooxml</artifactId>
     *   <version>5.3.0</version>
     * </dependency>
     */
    private String parseDocx(byte[] bytes) {
        try (java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(bytes);
             org.apache.poi.xwpf.usermodel.XWPFDocument doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(bis)) {

            StringBuilder sb = new StringBuilder();

            // 提取段落文本
            for (org.apache.poi.xwpf.usermodel.XWPFParagraph para : doc.getParagraphs()) {
                sb.append(para.getText()).append("\n");
            }

            // 提取表格文本
            for (org.apache.poi.xwpf.usermodel.XWPFTable table : doc.getTables()) {
                for (org.apache.poi.xwpf.usermodel.XWPFTableRow row : table.getRows()) {
                    for (org.apache.poi.xwpf.usermodel.XWPFTableCell cell : row.getTableCells()) {
                        sb.append(cell.getText()).append("\t");
                    }
                    sb.append("\n");
                }
            }

            return sb.toString();
        } catch (Exception e) {
            throw new VectorStoreException("DOCX 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析旧版 DOC 文件 —— 使用 Apache POI HWPF 提取文本（OLE2 格式）。
     *
     * 注意：需要额外添加 POI Scratchpad 依赖：
     * <dependency>
     *   <groupId>org.apache.poi</groupId>
     *   <artifactId>poi-scratchpad</artifactId>
     *   <version>5.3.0</version>
     * </dependency>
     */
    private String parseDoc(byte[] bytes) {
        try (java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(bytes);
             org.apache.poi.hwpf.HWPFDocument doc = new org.apache.poi.hwpf.HWPFDocument(bis)) {

            StringBuilder sb = new StringBuilder();

            // 提取段落文本（旧版 Word 使用 Range 接口）
            org.apache.poi.hwpf.usermodel.Range range = doc.getRange();
            for (int i = 0; i < range.numParagraphs(); i++) {
                org.apache.poi.hwpf.usermodel.Paragraph para = range.getParagraph(i);
                sb.append(para.text()).append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            throw new VectorStoreException("DOC 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 核心摄入逻辑 —— 文本 → 分块 → Embedding → Milvus。
     */
    private Mono<DocumentUploadResponse> ingestText(String documentId, String text, DocumentUploadRequest request) {
        return Mono.fromCallable(() -> {
            log.info("📄 开始摄入文档: {}, 文本长度: {} 字符", documentId, text.length());

            // 步骤 1: 分块
            List<String> chunks = chunkText(text);
            log.info("✂️ 分块完成: {} 块", chunks.size());

            if (chunks.isEmpty()) {
                throw new VectorStoreException("文本分块后为空，请检查内容");
            }

            // 步骤 2: Embedding
            List<float[]> embeddings = embeddingService.embedAll(chunks)
                    .subscribeOn(Schedulers.boundedElastic())
                    .block(); // 阻塞等待结果（在 boundedElastic 上）

            log.info("🧮 Embedding 完成: {} 个向量", embeddings.size());

            // 步骤 3: 构建 Milvus 行数据
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                Map<String, Object> row = new HashMap<>();
                row.put("document_id", documentId);
                row.put("chunk_index", i);
                row.put("text", chunks.get(i));
                row.put("embedding", embeddings.get(i));

                // 构建元数据
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("title", request.title());
                metadata.put("content_type", request.contentType());
                metadata.put("created_at", Instant.now().toString());
                if (request.metadata() != null) {
                    metadata.putAll(request.metadata());
                }
                row.put("metadata", metadata);

                rows.add(row);

                // 步骤 3.5: 同步更新 BM25 索引
                bm25IndexService.addChunk(documentId, i, chunks.get(i));
            }

            // 步骤 4: 写入 Milvus
            vectorStoreClient.insert(rows)
                    .subscribeOn(Schedulers.boundedElastic())
                    .block();

            // 步骤 5: 提交 BM25 索引，确保对搜索可见
            bm25IndexService.commit();

            log.info("✅ 文档摄入完成: {}", documentId);

            return new DocumentUploadResponse(
                    documentId,
                    request.title(),
                    chunks.size(),
                    "success",
                    null,
                    request.metadata(),
                    Instant.now()
            );
        })
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorMap(e -> new VectorStoreException("文档摄入失败: " + e.getMessage(), e));
    }

    /**
     * 文本分块 —— 使用 LangChain4j 的递归分割器。
     *
     * 策略：
     * - 优先在段落边界（\n\n）切分
     * - 其次在句子边界（。！？）切分
     * - 最后在空格处切分
     * - 重叠 chunkOverlap 字符保证上下文连贯
     *
     * @param text 原始文本
     * @return 分块后的文本列表
     */
    private List<String> chunkText(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        // 使用 LangChain4j 的递归分割器
        var splitter = DocumentSplitters.recursive(
                ragProperties.getChunkSize(),
                ragProperties.getChunkOverlap()
        );

        // 创建临时 Document 进行分块
        Document document = Document.from(text);
        List<TextSegment> segments = splitter.split(document);

        return segments.stream()
                .map(TextSegment::text)
                .filter(s -> !s.isBlank())
                .toList();
    }

    /**
     * 读取 FilePart 内容为字节数组。
     *
     * 注意：FilePart 是响应式的，需要收集所有 DataBuffer 再转换为字节。
     */
    private Mono<byte[]> readFileBytes(FilePart file) {
        return DataBufferUtils.join(file.content())
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    return bytes;
                });
    }

    /**
     * 下载远程文件为字节数组。
     */
    private Mono<byte[]> downloadFileBytes(String fileUrl) {
        return webClient.get()
                .uri(fileUrl)
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(java.time.Duration.ofSeconds(30));
    }

    /**
     * 根据文件名推断内容类型。
     * 支持：PDF、DOC、DOCX、TXT、Markdown
     */
    public String detectContentType(String filename) {
        if (filename == null) {
            return "txt";
        }
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".docx")) return "docx";
        if (lower.endsWith(".doc")) return "doc";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "markdown";
        if (lower.endsWith(".txt")) return "txt";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "html";
        return "txt";
    }

    /**
     * 删除文档 —— 删除 Milvus 中该文档的所有向量。
     *
     * @param documentId 文档 ID
     * @return 删除完成信号
     */
    public Mono<Void> deleteDocument(String documentId) {
        return vectorStoreClient.deleteByDocumentId(documentId);
    }
}
