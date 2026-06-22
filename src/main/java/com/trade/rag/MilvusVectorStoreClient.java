package com.trade.rag;

import com.trade.config.AiGatewayProperties;
import com.trade.exception.VectorStoreException;
import com.trade.rag.dto.SearchResultDto.SearchMatch;
import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeIndexResponse;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FlushParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.param.index.DescribeIndexParam;
import io.milvus.response.QueryResultsWrapper.RowRecord;
import io.milvus.response.SearchResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Milvus 向量数据库客户端 —— 通过 Milvus Java SDK 连接（gRPC 端口 19530）。
 *
 * 技术栈：
 * - Milvus Java SDK 2.4.10：官方 Java 客户端，通过 gRPC 连接
 * - Project Reactor：将阻塞 SDK 调用包装为 Mono，适配 Spring WebFlux
 *
 * 注意：Milvus SDK 是阻塞的，所有方法通过 Mono.fromCallable + boundedElastic 调度器包装。
 */
@Component
public class MilvusVectorStoreClient {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStoreClient.class);

    private final AiGatewayProperties.Rag ragProperties;
    private final AiGatewayProperties.Rag.Milvus milvusProperties;
    private final String collectionName;
    private final int dimension;
    private final double similarityThreshold;
    private MilvusServiceClient milvusClient;

    public MilvusVectorStoreClient(AiGatewayProperties properties) {
        this.ragProperties = properties.getRag();
        this.milvusProperties = ragProperties.getMilvus();
        this.collectionName = ragProperties.getCollectionName();
        this.dimension = ragProperties.getDimension();
        this.similarityThreshold = ragProperties.getSimilarityThreshold();
    }

    /**
     * 初始化 Milvus 连接 —— 应用启动时自动执行。
     */
    @PostConstruct
    public void init() {
        try {
            // 使用 withHost + withPort + withDatabase 连接（gRPC 端口 19530）
            ConnectParam.Builder connectBuilder = ConnectParam.newBuilder()
                    .withHost(milvusProperties.getHost())
                    .withPort(milvusProperties.getPort())
                    .withDatabaseName(milvusProperties.getDatabase()) // 指定数据库
                    .withConnectTimeout(10, java.util.concurrent.TimeUnit.SECONDS);

            // 如果配置了用户名和密码，则添加认证信息
            if (milvusProperties.getUsername() != null && !milvusProperties.getUsername().isEmpty()) {
                connectBuilder.withAuthorization(milvusProperties.getUsername(), milvusProperties.getPassword());
            }

            this.milvusClient = new MilvusServiceClient(connectBuilder.build());
            log.info("✅ Milvus 连接成功: {}:{}, database={}",
                    milvusProperties.getHost(), milvusProperties.getPort(), milvusProperties.getDatabase());

            // 确保集合和索引存在
            ensureCollectionExists();
            ensureIndexExists();
            loadCollection();
        } catch (Exception e) {
            log.error("❌ Milvus 初始化失败: {}", e.getMessage(), e);
            log.warn("⚠️ RAG 功能将不可用，请检查 Milvus 服务是否已启动");
        }
    }

    /**
     * 关闭 Milvus 连接 —— 应用停止时自动执行。
     */
    @PreDestroy
    public void close() {
        if (milvusClient != null) {
            try {
                milvusClient.close();
                log.info("Milvus 连接已关闭");
            } catch (Exception e) {
                log.warn("关闭 Milvus 连接失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 批量插入向量 —— 将文档块及其向量写入 Milvus。
     */
    public Mono<Void> insert(List<Map<String, Object>> rows) {
        return Mono.<Void>fromCallable(() -> {
            log.debug("📝 向 Milvus 插入 {} 条向量", rows.size());

            // 转换为 Milvus SDK 需要的格式（使用 Gson JsonObject）
            List<JsonObject> data = rows.stream().map(row -> {
                JsonObject entity = new JsonObject();
                entity.addProperty("document_id", (String) row.get("document_id"));
                entity.addProperty("chunk_index", ((Number) row.get("chunk_index")).intValue());
                entity.addProperty("text", (String) row.get("text"));

                // 添加向量
                float[] embedding = (float[]) row.get("embedding");
                List<Float> embeddingList = new ArrayList<>();
                for (float v : embedding) {
                    embeddingList.add(v);
                }
                entity.add("embedding", com.google.gson.JsonParser.parseString(
                        new com.google.gson.Gson().toJson(embeddingList)));

                // 添加元数据
                Map<String, Object> metadata = (Map<String, Object>) row.get("metadata");
                log.debug("📦 插入文档: document_id={}, chunk_index={}, metadata={}",
                        row.get("document_id"), row.get("chunk_index"), metadata);
                if (metadata != null) {
                    String metadataJson = new com.google.gson.Gson().toJson(metadata);
                    log.debug("📦 metadata JSON: {}", metadataJson);
                    entity.add("metadata", com.google.gson.JsonParser.parseString(metadataJson));
                }

                return entity;
            }).collect(Collectors.toList());

            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withRows(data)
                    .build();

            R<MutationResult> response = milvusClient.insert(insertParam);
            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new VectorStoreException("向量插入失败: " + response.getMessage());
            }

            // 刷新集合
            milvusClient.flush(FlushParam.newBuilder()
                    .addCollectionName(collectionName)
                    .build());

            log.info("✅ 成功插入 {} 条向量", rows.size());
            return null;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorMap(e -> new VectorStoreException("向量插入失败: " + e.getMessage(), e));
    }

    /**
     * 相似度搜索 —— 根据查询向量检索最相似的文档块。
     */
    public Mono<List<SearchMatch>> search(float[] queryEmbedding, int topK) {
        return Mono.fromCallable(() -> {
            long startTime = System.currentTimeMillis();

            // 转换 float[] 为 List<List<Float>>（Milvus SDK 要求）
            List<Float> embeddingList = new ArrayList<>();
            for (float v : queryEmbedding) {
                embeddingList.add(v);
            }
            List<List<Float>> vectors = Collections.singletonList(embeddingList);

            // 构建搜索参数（使用 withFloatVectors 明确指定 FloatVector 类型）
            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withMetricType(MetricType.COSINE)
                    .withTopK(topK)
                    .withFloatVectors(vectors)
                    .withVectorFieldName("embedding")
                    .withOutFields(Arrays.asList("document_id", "chunk_index", "text", "metadata"))
                    .withParams("{\"nprobe\": 16}")
                    .build();

            // 执行搜索
            log.debug("🔍 执行 Milvus 搜索: collection={}, topK={}", collectionName, topK);
            R<SearchResults> response = milvusClient.search(searchParam);
            if (response.getStatus() != R.Status.Success.getCode()) {
                String msg = response.getMessage();
                log.error("❌ Milvus 搜索失败: status={}, message={}", response.getStatus(), msg);
                throw new VectorStoreException("向量搜索失败: " + (msg != null ? msg : "未知错误"));
            }
            log.debug("✅ Milvus 搜索成功");

            // 解析结果
            SearchResults searchResults = response.getData();
            List<SearchMatch> matches = new ArrayList<>();

            if (searchResults != null && searchResults.getResults() != null) {
                SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResults.getResults());
                List<RowRecord> rowRecords = wrapper.getRowRecords();

                for (RowRecord record : rowRecords) {
                    // 从 IDScore 获取分数（Milvus 2.4.x 使用 IDScore）
                    // 注意：RowRecord 的 get 方法返回字段值
                    Object scoreObj = record.get("score");
                    double score = scoreObj instanceof Number ? ((Number) scoreObj).doubleValue() : 0.0;

                    // 过滤低于阈值的结果
                    if (score < similarityThreshold) {
                        continue;
                    }

                    // 提取字段值
                    String documentId = getStringValue(record, "document_id");
                    int chunkIndex = getIntValue(record, "chunk_index");
                    String text = getStringValue(record, "text");
                    Map<String, Object> metadata = getMetadataValue(record, "metadata");

                    matches.add(new SearchMatch(text, score, documentId, chunkIndex, metadata));
                }
            }

            long searchTimeMs = System.currentTimeMillis() - startTime;
            log.debug("🔍 Milvus 搜索完成: {} 条匹配, 耗时 {}ms", matches.size(), searchTimeMs);

            return matches;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorMap(e -> new VectorStoreException("向量搜索失败: " + e.getMessage(), e));
    }

    /**
     * 按过滤条件搜索 —— 使用表达式过滤搜索结果（如 document_id == "xxx"）
     *
     * @param expr 过滤表达式
     * @param topK 返回数量
     * @return 匹配的文档列表
     */
    public List<SearchMatch> searchWithFilter(String expr, int topK) {
        try {
            log.debug("🔍 Milvus 过滤搜索: expr={}, topK={}", expr, topK);

            // 构建搜索参数
            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withMetricType(MetricType.COSINE)
                    .withTopK(topK)
                    .withExpr(expr)
                    .withOutFields(Arrays.asList("document_id", "chunk_index", "text", "metadata"))
                    .withParams("{\"nprobe\": 16}")
                    .build();

            // 执行搜索
            R<SearchResults> response = milvusClient.search(searchParam);
            if (response.getStatus() != R.Status.Success.getCode()) {
                log.error("❌ Milvus 过滤搜索失败: {}", response.getMessage());
                return Collections.emptyList();
            }

            // 解析结果
            SearchResults searchResults = response.getData();
            List<SearchMatch> matches = new ArrayList<>();

            if (searchResults != null && searchResults.getResults() != null) {
                SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResults.getResults());
                List<RowRecord> rowRecords = wrapper.getRowRecords();

                for (RowRecord record : rowRecords) {
                    String documentId = getStringValue(record, "document_id");
                    int chunkIndex = getIntValue(record, "chunk_index");
                    String text = getStringValue(record, "text");
                    Map<String, Object> metadata = getMetadataValue(record, "metadata");
                    double score = 1.0; // 过滤搜索无分数

                    matches.add(new SearchMatch(text, score, documentId, chunkIndex, metadata));
                }
            }

            log.debug("✅ Milvus 过滤搜索完成: {} 条匹配", matches.size());
            return matches;
        } catch (Exception e) {
            log.error("❌ Milvus 过滤搜索异常: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 按文档 ID 删除向量 —— 删除指定文档的所有向量。
     */
    public Mono<Void> deleteByDocumentId(String documentId) {
        return Mono.<Void>fromCallable(() -> {
            log.debug("🗑️ 删除文档 {} 的所有向量", documentId);

            String expr = "document_id == \"" + documentId + "\"";
            R<MutationResult> deleteResponse = milvusClient.delete(DeleteParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withExpr(expr)
                    .build());

            if (deleteResponse.getStatus() != R.Status.Success.getCode()) {
                throw new VectorStoreException("向量删除失败: " + deleteResponse.getMessage());
            }

            // 刷新确保删除生效
            milvusClient.flush(FlushParam.newBuilder()
                    .addCollectionName(collectionName)
                    .build());

            log.info("✅ 文档 {} 的向量已删除", documentId);
            return null;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorMap(e -> new VectorStoreException("向量删除失败: " + e.getMessage(), e));
    }

    /**
     * 检查集合是否存在，不存在则创建。
     */
    private void ensureCollectionExists() {
        R<Boolean> hasResponse = milvusClient.hasCollection(
                HasCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build()
        );

        if (hasResponse.getData() != null && hasResponse.getData()) {
            log.info("✅ 集合 {} 已存在", collectionName);
            return;
        }

        log.info("📦 创建集合: {}", collectionName);

        // 构建字段
        List<io.milvus.param.collection.FieldType> fields = new ArrayList<>();
        fields.add(io.milvus.param.collection.FieldType.newBuilder()
                .withName("id")
                .withDataType(DataType.Int64)
                .withPrimaryKey(true)
                .withAutoID(true)
                .build());
        fields.add(io.milvus.param.collection.FieldType.newBuilder()
                .withName("document_id")
                .withDataType(DataType.VarChar)
                .addTypeParam("max_length", "64")
                .build());
        fields.add(io.milvus.param.collection.FieldType.newBuilder()
                .withName("chunk_index")
                .withDataType(DataType.Int32)
                .build());
        fields.add(io.milvus.param.collection.FieldType.newBuilder()
                .withName("text")
                .withDataType(DataType.VarChar)
                .addTypeParam("max_length", "65535")
                .build());
        fields.add(io.milvus.param.collection.FieldType.newBuilder()
                .withName("embedding")
                .withDataType(DataType.FloatVector)
                .withDimension(dimension)
                .build());
        fields.add(io.milvus.param.collection.FieldType.newBuilder()
                .withName("metadata")
                .withDataType(DataType.JSON)
                .build());

        // 创建集合
        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldTypes(fields)
                .build();

        R<io.milvus.param.RpcStatus> response = milvusClient.createCollection(createParam);
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new VectorStoreException("创建集合失败: " + response.getMessage());
        }

        log.info("✅ 集合 {} 创建成功", collectionName);
    }

    /**
     * 检查索引是否存在，不存在则创建。
     */
    private void ensureIndexExists() {
        // 检查索引是否存在
        R<DescribeIndexResponse> indexResponse = milvusClient.describeIndex(
                DescribeIndexParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build()
        );

        if (indexResponse.getData() != null && indexResponse.getData().getIndexDescriptionsCount() > 0) {
            log.info("✅ 集合 {} 的索引已存在", collectionName);
            return;
        }

        log.info("📇 为集合 {} 创建索引", collectionName);

        CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName("embedding")
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"nlist\": 1024}")
                .build();

        R<io.milvus.param.RpcStatus> response = milvusClient.createIndex(indexParam);
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new VectorStoreException("创建索引失败: " + response.getMessage());
        }

        log.info("✅ 索引创建成功");
    }

    /**
     * 加载集合到内存 —— Milvus 2.4.x 中，新创建的集合需要显式加载才能搜索。
     */
    private void loadCollection() {
        log.info("📥 加载集合 {} 到内存", collectionName);

        try {
            milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build());

            log.info("✅ 集合 {} 加载成功", collectionName);
        } catch (Exception e) {
            log.warn("⚠️ 加载集合失败: {}", e.getMessage());
        }
    }

    /**
     * 根据表达式查询文档块 —— 用于溯源定位，查询特定文档或特定 chunk。
     *
     * @param expr 查询表达式，如 "document_id == \"xxx\" && chunk_index == 0"
     * @return 查询结果
     */
    public io.milvus.param.R<io.milvus.grpc.QueryResults> query(String expr) {
        return milvusClient.query(QueryParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(expr)
                .withOutFields(Arrays.asList("document_id", "chunk_index", "text", "metadata"))
                .build());
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
        log.debug("🔍 获取元数据: fieldName={}, value={}, type={}", fieldName, value,
                value != null ? value.getClass().getName() : "null");
        if (value == null) {
            log.debug("🔍 元数据为 null，返回空 Map");
            return Collections.emptyMap();
        }
        // 如果已经是 Map，直接返回
        if (value instanceof Map) {
            log.debug("🔍 元数据是 Map: {}", value);
            return (Map<String, Object>) value;
        }
        // 如果是 JsonObject（Milvus SDK 返回类型），转换为 Map
        if (value instanceof com.google.gson.JsonObject) {
            log.debug("🔍 元数据是 JsonObject: {}", value);
            com.google.gson.JsonObject jsonObject = (com.google.gson.JsonObject) value;
            Map<String, Object> map = new java.util.HashMap<>();
            for (String key : jsonObject.keySet()) {
                com.google.gson.JsonElement element = jsonObject.get(key);
                if (element.isJsonPrimitive()) {
                    map.put(key, element.getAsString());
                } else {
                    map.put(key, element.toString());
                }
            }
            log.debug("🔍 转换后的元数据 Map: {}", map);
            return map;
        }
        // 如果是 JSON 字符串，解析为 Map
        if (value instanceof String) {
            try {
                String jsonStr = (String) value;
                log.debug("🔍 元数据是 JSON 字符串: {}", jsonStr);
                if (jsonStr.startsWith("{") && jsonStr.endsWith("}")) {
                    Map<String, Object> parsed = new com.google.gson.Gson().fromJson(jsonStr,
                            new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
                    log.debug("🔍 解析后的元数据: {}", parsed);
                    return parsed;
                }
            } catch (Exception e) {
                log.warn("解析元数据 JSON 失败: {}", e.getMessage());
            }
        }
        log.debug("🔍 元数据格式未知，返回空 Map");
        return Collections.emptyMap();
    }
}
