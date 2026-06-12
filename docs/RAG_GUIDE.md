# RAG 知识库使用指南

## 概述

本项目实现了完整的 RAG（Retrieval-Augmented Generation，检索增强生成）功能，支持：

1. **文档上传**：上传 PDF、DOCX、TXT、Markdown 等文件
2. **自动解析**：提取文档文本内容
3. **智能分块**：将长文本切分为语义完整的片段
4. **向量化**：调用 Embedding 模型将文本转为向量
5. **向量存储**：写入 Milvus 向量数据库
6. **语义检索**：根据问题查找相关文档块
7. **检索增强**：将检索结果注入 Prompt，生成高质量回答

## 架构

```
┌─────────────────────────────────────────────────────────────────┐
│                         客户端请求                               │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                      RagController                               │
│  POST /api/v1/knowledge/documents/upload                        │
│  POST /api/v1/knowledge/documents                               │
│  GET  /api/v1/knowledge/search                                  │
│  POST /api/v1/knowledge/chat                                    │
└─────────────────────────────────────────────────────────────────┘
                                │
                ┌───────────────┼───────────────┐
                ▼               ▼               ▼
┌───────────────────┐ ┌─────────────────┐ ┌─────────────────────┐
│ DocumentIngestion │ │ RagOrchestration│ │ MilvusVectorStore   │
│ Service           │ │ Service         │ │ Client              │
│                   │ │                 │ │                     │
│ • PDFBox (PDF)    │ │ • Embedding     │ │ • REST API          │
│ • Apache POI(DOCX)│ │ • Search        │ │ • IVF_FLAT Index    │
│ • LangChain4j     │ │ • Prompt Augment│ │ • COSINE Similarity │
│   (Chunking)      │ │                 │ │                     │
└───────────────────┘ └─────────────────┘ └─────────────────────┘
                │               │               │
                └───────────────┼───────────────┘
                                ▼
                    ┌─────────────────────┐
                    │    Milvus DB        │
                    │  (127.0.0.1:19530)  │
                    └─────────────────────┘
```

## 快速开始

### 1. 启动 Milvus

```powershell
# 使用 Docker 启动 Milvus v2.4.10 单节点
docker run -d --name milvus `
  -p 19530:19530 `
  -p 9091:9091 `
  -e ETCD_USE_EMBED=true `
  -e MINIO_ADDRESS=minio:9000 `
  milvusdb/milvus:v2.4.10
```

**端口说明**：
- `19530`：gRPC 端口（Milvus SDK 使用）
- `9091`：HTTP/REST 端口（本项目使用）

### 2. 启动应用

```powershell
$env:DEEPSEEK_API_KEY="sk-xxx"
$env:BAILIAN_API_KEY="sk-xxx"
$env:AI_GATEWAY_TOKENS="dev-token"
mvn spring-boot:run
```

### 3. 上传文档

```powershell
# 使用 curl 上传 PDF
curl -X POST http://localhost:8080/api/v1/knowledge/documents/upload `
  -H "X-API-Token: dev-token" `
  -F "file=@product_manual.pdf" `
  -F "title=产品手册" `
  -F "contentType=pdf" `
  -F 'metadata={"category": "product", "department": "sales"}'
```

### 4. 语义搜索

```powershell
# 搜索相关文档
curl "http://localhost:8080/api/v1/knowledge/search?query=如何报价" `
  -H "X-API-Token: dev-token"
```

### 5. RAG 问答

```powershell
# 使用知识库增强的问答
curl -X POST http://localhost:8080/api/v1/knowledge/chat `
  -H "X-API-Token: dev-token" `
  -H "Content-Type: application/json" `
  -d '{
    "question": "如何开发新客户？",
    "preferredModel": "DEEPSEEK"
  }'
```

### 6. 普通对话 + 知识库增强

```powershell
# 在普通对话中启用知识库
curl -X POST http://localhost:8080/api/v1/chat/completions `
  -H "X-API-Token: dev-token" `
  -H "Content-Type: application/json" `
  -d '{
    "question": "如何开发新客户？",
    "useKnowledgeBase": true,
    "preferredModel": "DEEPSEEK"
  }'
```

## API 参考

### 上传文件

```
POST /api/v1/knowledge/documents/upload
Content-Type: multipart/form-data

Parameters:
  - file: 文件内容（PDF/DOCX/TXT）
  - title: 文档标题（可选）
  - contentType: 文件类型（可选，自动推断）
  - metadata: JSON 格式元数据（可选）

Response:
{
  "documentId": "abc-123",
  "title": "产品手册.pdf",
  "chunkCount": 42,
  "status": "success",
  "message": null,
  "metadata": {"category": "product"},
  "createdAt": "2026-06-10T10:30:00Z"
}
```

### 摄入文本

```
POST /api/v1/knowledge/documents
Content-Type: application/json

Body:
{
  "title": "外贸流程指南",
  "textContent": "第一步：客户询盘...",
  "contentType": "txt",
  "metadata": {"category": "process"}
}
```

### 语义搜索

```
GET /api/v1/knowledge/search?query=如何报价

Response:
{
  "query": "如何报价",
  "matches": [
    {
      "text": "报价时应考虑...",
      "score": 0.92,
      "documentId": "abc-123",
      "chunkIndex": 5,
      "metadata": {"title": "报价指南.pdf"}
    }
  ],
  "searchTimeMs": 45
}
```

### RAG 问答

```
POST /api/v1/knowledge/chat
Content-Type: application/json

Body:
{
  "question": "如何开发新客户？",
  "preferredModel": "DEEPSEEK"
}

Response:
{
  "requestId": "req-456",
  "conversationId": "conv-789",
  "provider": "DEEPSEEK",
  "answer": "根据《客户开发指南》，建议采取以下步骤...",
  "routeDecision": {...},
  "usage": {"promptTokens": 1523, "completionTokens": 256, "totalTokens": 1779},
  "timestamp": "2026-06-10T10:35:00Z"
}
```

## 配置说明

### Embedding 配置

```yaml
ai:
  gateway:
    rag:
      embedding:
        model: text-embedding-v3        # DashScope 模型
        base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
        path: /embeddings
        api-key: ${BAILIAN_API_KEY}
        encoding-format: float
```

### Milvus 配置

```yaml
ai:
  gateway:
    rag:
      milvus:
        host: 127.0.0.1
        port: 19530
        username: ""  # 本地部署通常不需要
        password: ""
        timeout: 10s
```

### 分块配置

```yaml
ai:
  gateway:
    rag:
      chunk-size: 500      # 每块约 500 字符
      chunk-overlap: 50    # 重叠 50 字符
      dimension: 1024      # 向量维度（需与 Embedding 模型一致）
```

## 技术栈

| 组件 | 技术 | 说明 |
|------|------|------|
| 文档解析 | Apache PDFBox | PDF 文本提取 |
| 文档解析 | Apache POI | DOCX 文本提取 |
| 文本分块 | LangChain4j | 递归分割器 |
| Embedding | DashScope API | text-embedding-v3 (1024维) |
| 向量存储 | Milvus REST API | IVF_FLAT 索引，余弦相似度 |
| 响应式 | Spring WebFlux | 全异步非阻塞 |

## 常见问题

### Q: Milvus 连接失败？

A: 确保 Milvus 服务已启动：
```powershell
docker ps | grep milvus
```

### Q: Embedding API 调用失败？

A: 检查 `BAILIAN_API_KEY` 环境变量是否设置，或配置中的 `api-key` 是否正确。

### Q: 检索结果为空？

A: 确保已上传文档，且文档已成功摄入。可以通过 `/api/v1/knowledge/search` 验证。

### Q: 如何调整检索精度？

A: 修改 `similarityThreshold`（越大越严格）和 `maxResults`（返回数量）。

## 监控指标

- `ai.rag.document.ingested.total` - 文档摄入统计
- `ai.rag.search.total` - 搜索次数统计
- `ai.rag.chat.total` - RAG 问答统计

可通过 `/actuator/prometheus` 端点查看。
