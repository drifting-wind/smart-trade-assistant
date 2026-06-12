# 外贸智能销售助手（Smart Trade Assistant）

面向外贸销售团队的 AI 智能助手平台，基于多模型智能路由与 Spring WebFlux 响应式架构，为外贸业务员提供商机分析、销售计划生成、客户邮件撰写等 AI 辅助能力。

## 核心能力

- **多模型智能路由**：根据场景类型（QA/FLOW）、内容关键词、精确模式为 DeepSeek 和通义千问打分，自动选择最优模型并实现 fallback 降级
- **RAG 知识库检索增强**：上传 PDF/DOCX 文档 → 自动解析分块 → Embedding 向量化 → Milvus 存储 → 语义检索增强 AI 回答
- **外贸销售三件套**：商机分析（lead 打分 + 风险评估）、销售推进计划（任务拆解）、客户英文回复邮件生成
- **智能问答**：通用 QA 场景，支持多轮对话记忆
- **流程规划助手**：业务流程自动拆解为可执行任务计划
- **SSE 流式输出**：Spring WebFlux Flux + SSE 实现打字机效果实时推送
- **企业级能力**：API Token 鉴权、统一异常处理、超时重试、连接池、Caffeine 缓存、Actuator/Prometheus 监控
- **技术栈**：Spring Boot 3、Spring WebFlux、Project Reactor、LangChain4j、PDFBox、Apache POI、Milvus、Jackson、Caffeine、Micrometer

## 快速运行

需要 JDK 17+。项目已带 Maven Wrapper，也可使用本机 Maven 3.9+。

### 1. 启动 Milvus（RAG 功能依赖）

```powershell
# 使用 Docker 启动 Milvus v2.4.10（单节点模式）
docker run -d --name milvus `
  -p 19530:19530 `
  -p 9091:9091 `
  -e ETCD_USE_EMBED=true `
  -e MINIO_ADDRESS=minio:9000 `
  milvusdb/milvus:v2.4.10
```

**注意**：
- 端口 `19530` 是 gRPC 端口（Milvus SDK 使用）
- 端口 `9091` 是 HTTP/REST 端口（本项目 REST API 使用）
- 本项目默认连接 `127.0.0.1:9091`

### 2. 启动应用

```powershell
$env:DEEPSEEK_API_KEY="sk-xxx"
$env:BAILIAN_API_KEY="sk-xxx"
$env:AI_GATEWAY_TOKENS="dev-token"
.\mvnw.cmd spring-boot:run
```

### 3. 访问

- OpenAPI UI: `http://localhost:8080/swagger-ui.html`
- 健康检查: `http://localhost:8080/actuator/health`

## 关键配置

配置入口在 `src/main/resources/application.yml`：

### AI 模型配置
- `ai.gateway.models.deepseek.*`: DeepSeek 的 base-url、path、model、api-key、能力标签
- `ai.gateway.models.bailian.*`: 阿里百炼的 base-url、path、model、api-key、能力标签
- `ai.gateway.routing.*`: 默认模型、fallback 顺序、长上下文阈值

### RAG 知识库配置
- `ai.gateway.rag.vector-store-type`: 向量存储类型（目前仅支持 milvus）
- `ai.gateway.rag.collection-name`: Milvus 集合名称
- `ai.gateway.rag.dimension`: Embedding 向量维度（需与模型一致）
- `ai.gateway.rag.chunk-size`: 文本分块大小（字符数）
- `ai.gateway.rag.chunk-overlap`: 分块重叠字符数
- `ai.gateway.rag.embedding.*`: Embedding 模型配置（model、base-url、api-key）
- `ai.gateway.rag.milvus.*`: Milvus 连接配置（host、port、username、password）

### 安全配置
- `ai.gateway.security.tokens`: 允许访问业务 API 的 Token 列表

调用业务 API 时需要带上：

```http
X-API-Token: dev-token
```

或：

```http
Authorization: Bearer dev-token
```

## 模型扩展方式

新增模型时，复制 `ai.gateway.models.deepseek` 配置块，指定新的 `provider`、`base-url`、`path`、`model`、`api-key` 与 `capabilities`。如需新增枚举供应商，扩展 `ModelProvider`，并在路由规则中添加能力评分即可。

当前默认网关客户端使用 OpenAI-compatible Chat Completions 协议，DeepSeek 与阿里百炼均可通过该模板复用。

## RAG 知识库 API

### 1. 上传文档

```http
POST /api/v1/knowledge/documents/upload
Content-Type: multipart/form-data

file: 文件内容
title: 文档标题
contentType: pdf/docx/txt
metadata: {"category": "product"}
```

### 2. 摄入文本

```http
POST /api/v1/knowledge/documents
Content-Type: application/json

{
  "title": "外贸流程指南",
  "textContent": "第一步：客户询盘...",
  "contentType": "txt",
  "metadata": {"category": "process"}
}
```

### 3. 语义搜索

```http
GET /api/v1/knowledge/search?query=如何报价
```

### 4. RAG 问答

```http
POST /api/v1/knowledge/chat
Content-Type: application/json

{
  "question": "如何开发新客户？",
  "preferredModel": "DEEPSEEK"
}
```

### 5. 普通对话（带知识库增强）

```http
POST /api/v1/chat/completions
Content-Type: application/json

{
  "question": "如何开发新客户？",
  "useKnowledgeBase": true,
  "preferredModel": "DEEPSEEK"
}
```

## 容器化

```powershell
docker build -t smart-trade-assistant:1.0.0 .
docker compose up -d
```

生产环境建议把 `DEEPSEEK_API_KEY`、`BAILIAN_API_KEY`、`AI_GATEWAY_TOKENS` 放到密钥管理系统，不提交到仓库。
