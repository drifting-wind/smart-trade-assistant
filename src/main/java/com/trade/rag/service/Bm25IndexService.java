package com.trade.rag.service;

import jakarta.annotation.PostConstruct;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * BM25 磁盘全文索引服务 —— 基于 Apache Lucene 9.11.0。
 *
 * 职责：
 * 1. 在磁盘上维护 chunk 的 BM25 索引（使用 FSDirectory）
 * 2. 支持 addChunk() 写入单条 chunk
 * 3. 支持 search() 执行关键词查询，返回 Top-K 匹配结果
 * 4. 线程安全：使用 ReentrantReadWriteLock 保护索引读写
 *
 * 注意：
 * - 索引存储在磁盘，服务重启后无需重建（直接加载）
 * - 由 Bm25IndexRebuildService 负责首次启动时从 Milvus 构建索引
 * - 磁盘索引大幅降低 JVM 堆内存压力，768MB 堆可支撑 50 万+ 文档
 */
@Service
public class Bm25IndexService {

    private static final Logger log = LoggerFactory.getLogger(Bm25IndexService.class);

    /** BM25 索引磁盘存储路径 */
    @Value("${bm25.index.path:/app/data/bm25-index}")
    private String indexPath;

    /** Lucene 磁盘索引目录（FSDirectory，持久化存储） */
    private Directory directory;

    /** Lucene 索引写入器 */
    private IndexWriter writer;

    /** 读写锁，保护索引并发访问 */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** 当前索引中的文档数量 */
    private int documentCount = 0;

    /** 字段名常量 */
    private static final String FIELD_DOCUMENT_ID = "document_id";
    private static final String FIELD_CHUNK_INDEX = "chunk_index";
    private static final String FIELD_CONTENT = "content";

    /**
     * 初始化 Lucene 索引。
     * 使用 StandardAnalyzer（适合英文和中文分词），BM25 相似度算法。
     * 如果磁盘索引已存在则直接加载，否则创建新索引。
     */
    @PostConstruct
    public void init() throws IOException {
        Path path = Paths.get(indexPath);
        // 确保索引目录存在
        if (!Files.exists(path)) {
            Files.createDirectories(path);
            log.info("📁 创建 BM25 索引目录: {}", indexPath);
        }
        directory = FSDirectory.open(path);
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setSimilarity(new BM25Similarity());
        writer = new IndexWriter(directory, config);
        // 从磁盘索引初始化文档计数（重启后索引仍在，无需重建）
        DirectoryReader reader = DirectoryReader.open(writer);
        documentCount = reader.numDocs();
        reader.close();
        log.info("✅ BM25 磁盘索引初始化完成, 路径: {}, 已有文档数: {}", indexPath, documentCount);
    }

    /**
     * 向 BM25 索引添加一条 chunk。
     *
     * @param documentId 文档 ID（Milvus 中的 document_id）
     * @param chunkIndex 块索引（一个文档的第几个 chunk）
     * @param text       块文本内容
     */
    public void addChunk(String documentId, int chunkIndex, String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        lock.writeLock().lock();
        try {
            Document doc = new Document();
            doc.add(new StringField(FIELD_DOCUMENT_ID, documentId, Field.Store.YES));
            doc.add(new StringField(FIELD_CHUNK_INDEX, String.valueOf(chunkIndex), Field.Store.YES));
            doc.add(new TextField(FIELD_CONTENT, text, Field.Store.YES));
            writer.addDocument(doc);
            documentCount++;

            // 每 100 条提交一次，平衡性能和实时性
            if (documentCount % 100 == 0) {
                writer.commit();
                log.debug("📝 BM25 索引已提交，当前共 {} 条", documentCount);
            }
        } catch (IOException e) {
            log.error("❌ BM25 索引写入失败: documentId={}, chunkIndex={}", documentId, chunkIndex, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 执行 BM25 关键词搜索。
     *
     * @param query 查询字符串
     * @param topK  返回的最大结果数
     * @return 匹配结果列表（按 BM25 分数降序）
     */
    public List<Bm25Match> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        lock.readLock().lock();
        try {
            // 确保所有已写入的数据可见
            writer.commit();

            DirectoryReader reader = DirectoryReader.open(writer);
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setSimilarity(new BM25Similarity());

            QueryParser parser = new QueryParser(FIELD_CONTENT, new StandardAnalyzer());
            Query luceneQuery = parser.parse(query);

            TopDocs topDocs = searcher.search(luceneQuery, topK);

            List<Bm25Match> results = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                String documentId = doc.get(FIELD_DOCUMENT_ID);
                int chunkIndex = Integer.parseInt(doc.get(FIELD_CHUNK_INDEX));
                String content = doc.get(FIELD_CONTENT);
                results.add(new Bm25Match(documentId, chunkIndex, content, scoreDoc.score));
            }

            reader.close();
            log.debug("🔍 BM25 搜索: query='{}', hits={}", query, results.size());
            return results;
        } catch (Exception e) {
            log.error("❌ BM25 搜索失败: query={}", query, e);
            return List.of();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 提交索引，确保所有写入的数据对搜索可见。
     */
    public void commit() {
        lock.writeLock().lock();
        try {
            writer.commit();
            log.debug("📝 BM25 索引手动提交完成，当前共 {} 条", documentCount);
        } catch (IOException e) {
            log.error("❌ BM25 索引提交失败", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 获取当前索引中的文档数量。
     */
    public int getDocumentCount() {
        return documentCount;
    }

    /**
     * 清空索引并重置计数器。
     * 磁盘索引需要先删除文件再重新创建。
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            writer.close();
            directory.close();
            documentCount = 0;
            // 删除磁盘索引文件
            Path path = Paths.get(indexPath);
            if (Files.exists(path)) {
                Files.walk(path)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            log.warn("⚠️ 删除索引文件失败: {}", p);
                        }
                    });
            }
            // 重新初始化
            init();
            log.info("🗑️ BM25 磁盘索引已清空");
        } catch (IOException e) {
            log.error("❌ BM25 索引清空失败", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * BM25 匹配结果记录。
     *
     * @param documentId 文档 ID
     * @param chunkIndex 块索引
     * @param content    块文本
     * @param score      BM25 相关性分数
     */
    public record Bm25Match(String documentId, int chunkIndex, String content, float score) {
    }
}
