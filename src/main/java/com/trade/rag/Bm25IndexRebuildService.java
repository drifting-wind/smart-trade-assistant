package com.trade.rag;

import com.trade.config.AiGatewayProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.QueryResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.response.QueryResultsWrapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * BM25 索引重建服务 —— 应用启动时从 Milvus 全量加载 chunk 重建内存 BM25 索引。
 *
 * 触发条件：
 * 1. 应用首次启动（BM25 索引为空）
 * 2. BM25 索引被手动清空后重启
 *
 * 实现逻辑：
 * 1. 检查 Milvus 集合是否存在
 * 2. 分页查询所有 chunk（document_id, chunk_index, text）
 * 3. 逐条添加到 BM25 索引
 * 4. 提交索引，确保对搜索可见
 *
 * 注意：
 * - 仅在索引为空时执行，避免重复重建
 * - 分页查询避免一次性加载过多数据导致 OOM
 */
@Service
public class Bm25IndexRebuildService {

    private static final Logger log = LoggerFactory.getLogger(Bm25IndexRebuildService.class);

    /** 每页查询的 chunk 数量 */
    private static final int PAGE_SIZE = 500;

    private final MilvusVectorStoreClient vectorStoreClient;
    private final Bm25IndexService bm25IndexService;
    private final AiGatewayProperties properties;

    public Bm25IndexRebuildService(
            MilvusVectorStoreClient vectorStoreClient,
            Bm25IndexService bm25IndexService,
            AiGatewayProperties properties
    ) {
        this.vectorStoreClient = vectorStoreClient;
        this.bm25IndexService = bm25IndexService;
        this.properties = properties;
    }

    /**
     * 应用启动时重建 BM25 索引。
     * 仅在索引为空时执行，避免重复重建。
     */
    @PostConstruct
    public void rebuildIndex() {
        try {
            // 如果 BM25 索引已有数据，跳过重建
            if (bm25IndexService.getDocumentCount() > 0) {
                log.info("✅ BM25 索引已有 {} 条数据，跳过重建", bm25IndexService.getDocumentCount());
                return;
            }

            log.info("🔄 开始从 Milvus 重建 BM25 索引...");

            // 创建临时 Milvus 客户端进行分页查询
            AiGatewayProperties.Rag.Milvus milvusProps = properties.getRag().getMilvus();
            ConnectParam connectParam = ConnectParam.newBuilder()
                    .withHost(milvusProps.getHost())
                    .withPort(milvusProps.getPort())
                    .withConnectTimeout(10, TimeUnit.SECONDS)
                    .build();

            MilvusServiceClient client = new MilvusServiceClient(connectParam);
            try {
                String collectionName = properties.getRag().getCollectionName();

                // 检查集合是否存在
                if (!client.hasCollection(HasCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build()).getData()) {
                    log.info("📦 Milvus 集合 {} 不存在，跳过 BM25 索引重建", collectionName);
                    return;
                }

                // 加载集合
                client.loadCollection(LoadCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build());

                // 分页查询所有 chunk
                int totalLoaded = 0;
                long offset = 0;

                while (true) {
                    QueryParam queryParam = QueryParam.newBuilder()
                            .withCollectionName(collectionName)
                            .withExpr("") // 空表达式 = 查询所有
                            .withOutFields(Arrays.asList("document_id", "chunk_index", "text"))
                            .withOffset(offset)
                            .withLimit((long) PAGE_SIZE)
                            .build();

                    R<QueryResults> response = client.query(queryParam);
                    if (response.getStatus() != R.Status.Success.getCode()) {
                        log.warn("⚠️ Milvus 查询失败: {}", response.getMessage());
                        break;
                    }

                    QueryResults results = response.getData();
                    if (results == null) {
                        break;
                    }

                    // 使用 QueryResultsWrapper 解析结果
                    QueryResultsWrapper wrapper = new QueryResultsWrapper(results);
                    if (wrapper.getRowCount() == 0) {
                        break;
                    }

                    // 逐行解析
                    for (QueryResultsWrapper.RowRecord record : wrapper.getRowRecords()) {
                        String documentId = String.valueOf(record.get("document_id"));
                        int chunkIndex = Integer.parseInt(String.valueOf(record.get("chunk_index")));
                        String text = String.valueOf(record.get("text"));

                        if (text != null && !text.isBlank()) {
                            bm25IndexService.addChunk(documentId, chunkIndex, text);
                            totalLoaded++;
                        }
                    }

                    // 如果本页不足 PAGE_SIZE，说明已到最后一页
                    if (wrapper.getRowCount() < PAGE_SIZE) {
                        break;
                    }
                    offset += PAGE_SIZE;

                    log.debug("📥 BM25 索引重建进度: 已加载 {} 条", totalLoaded);
                }

                // 提交索引
                bm25IndexService.commit();

                log.info("✅ BM25 索引重建完成: 共 {} 条 chunk", totalLoaded);
            } finally {
                client.close();
            }
        } catch (Exception e) {
            log.error("❌ BM25 索引重建失败: {}", e.getMessage(), e);
            log.warn("⚠️ BM25 关键词检索将不可用，仅保留向量检索");
        }
    }
}
