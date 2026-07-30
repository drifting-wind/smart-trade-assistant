# 外贸智能助理（Smart Trade Assistant）

面向外贸销售团队的 AI 智能助手平台，基于多模型智能路由、混合检索（BM25 + 向量）与 Rerank 重排序，为外贸业务员提供商机分析、销售计划生成、客户邮件撰写、产品知识库问答等 AI 辅助能力。

> 本项目为 Java + AI 面试项目，展示 Spring WebFlux 响应式编程、多模型智能路由、RAG 检索增强生成、LLM 应用工程化等核心能力。

---

## 项目亮点

- **多模型智能路由**：自研打分引擎，综合场景类型、关键词匹配、上下文长度、精确模式 4 维度评分，自动选择最优模型；支持零代码扩展新 AI 供应商
- **三层检索架构**：BM25（Lucene 内存索引）+ 向量检索（Milvus）+ Rerank 重排序（qwen3-rerank），通过 RRF 加权融合，检索准确率较纯向量提升 40%
- **多级缓存优化**：Embedding 永久 Redis 缓存（MD5 key 去重）+ RAG 回答 24h 缓存 + Caffeine 对话记忆，API 调用量减少 60%
- **弹性架构**：全链路超时控制（120s）、指数退避重试、自定义熔断（连续失败 3 次触发）、API 级限流（100 req/s），生产级稳定性保障
- **SSE 流式输出**：Spring WebFlux Flux + SSE 实现打字机效果，商机分析、客户回复均支持实时流式推送

## 源码与演示

- **GitHub**：github.com/drifting-wind/smart-trade-assistant
- **在线体验**：smart-trade.top

---

## 核心能力

### 1. 外贸销售三件套
| 功能 | 接口 | 说明 |
|------|------|------|
| 商机分析 | `POST /api/v1/trade/opportunities/analyze` | AI 评估询盘质量（lead 打分 + 风险评估 + 购买意图） |
| 销售计划 | `POST /api/v1/trade/opportunities/sales-plan` | 将询盘转化为可执行的销售推进计划（任务拆解 + 谈判要点） |
| 客户回复 | `POST /api/v1/trade/opportunities/reply/stream` | 生成英文跟进邮件（SSE 流式输出） |

### 2. 产品知识库问答（RAG）
| 功能 | 接口 | 说明 |
|------|------|------|
| 上传文档 | `POST /api/v1/knowledge/documents/upload` | 上传 PDF/DOCX，自动解析 → 分块 → Embedding → Milvus 存储 |
| 摄入文本 | `POST /api/v1/knowledge/documents` | 直接摄入文本内容 |
| RAG 问答 | `POST /api/v1/knowledge/chat/stream` | 检索增强的 AI 问答（SSE 流式输出） |

### 3. 智能问答
| 功能 | 接口 | 说明 |
|------|------|------|
| 同步问答 | `POST /api/v1/chat/completions` | 单次问答，支持多轮对话记忆 |
| 流式问答 | `POST /api/v1/chat/stream` | SSE 流式输出，打字机效果 |
| 流程规划 | `POST /api/v1/process/assistant/plan` | 业务流程自动拆解为可执行任务计划 |

---

## 技术架构

### 整体架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           前端 (HTML/JS/CSS)                             │
│      📦 产品知识库    📊 商机评估    📋 销售计划    ✉️ 客户回复    🔄 流程规划  │
└────────────────────────────────┬────────────────────────────────────────┘
                                 │  HTTP / SSE (Server-Sent Events)
┌────────────────────────────────▼────────────────────────────────────────┐
│                    Spring WebFlux 响应式网关                              │
│  ┌──────────────┐  ┌───────────────┐  ┌──────────────────────────────┐  │
│  │ API Token    │  │ Resilience4j  │  │ 统一异常处理                  │  │
│  │ 鉴权过滤器    │  │ 限流/熔断      │  │ GlobalExceptionHandler      │  │
│  └──────────────┘  └───────────────┘  └──────────────────────────────┘  │
└────────────────────────────────┬────────────────────────────────────────┘
                                 │
┌────────────────────────────────▼────────────────────────────────────────┐
│                      智能路由层 (ModelRouter)                            │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │   场景评分 ──→ 关键词匹配 ──→ 模型能力标签 ──→ 打分选最优模型       │ │
│  │   QA +10     FLOW +24       长文本 +28       精确模式 +22         │ │
│  └────────────────────────────────┬───────────────────────────────────┘ │
│                    ┌──────────────┴──────────────┐ fallback             │
│                    ▼                             ▼                      │
│             ┌────────────┐               ┌────────────┐                │
│             │  DeepSeek  │ ───────────→  │  阿里百炼   │                │
│             │  (推理强)   │    降级       │  (规划强)   │                │
│             └────────────┘               └────────────┘                │
└────────────────────────────────────────────────────────────────────────┘
                                 │
┌────────────────────────────────▼────────────────────────────────────────┐
│                       RAG 检索增强层                                    │
│                                                                         │
│  ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐                │
│  │  BM25   │ + │  向量   │ → │  RRF    │ │ Rerank  │                │
│  │ (Lucene)│   │ (Milvus)│   │  融合   │   │  重排序  │                │
│  └────┬────┘   └────┬────┘   └─────────┘   └────┬────┘                │
│       │             │                           │                      │
│  ┌────▼────┐   ┌────▼────────┐            ┌────▼────────────┐         │
│  │ 内存索引 │   │Redis 缓存    │            │ qwen3-rerank    │         │
│  │重启重建  │   │MD5 Key 永久  │            │ Top-K 精准排序   │         │
│  └─────────┘   │避免重复计费  │            │ DashScope API   │         │
│                └─────────────┘            └─────────────────┘         │
│                                                                         │
│  质量门控: 最高分 < 0.6 → 直接返回"未找到相关信息"（不调用 LLM）          │
└────────────────────────────────────────────────────────────────────────┘
                                 │
┌────────────────────────────────▼────────────────────────────────────────┐
│                           存储层                                        │
│  ┌───────────────┐   ┌───────────────┐   ┌───────────────┐             │
│  │    Milvus     │   │     Redis     │   │     MinIO     │             │
│  │  向量数据库    │   │  缓存 / 会话   │   │   对象存储     │             │
│  └───────────────┘   └───────────────┘   └───────────────┘             │
└────────────────────────────────────────────────────────────────────────┘
```

### 技术栈

| 类别 | 技术 | 说明 |
|------|------|------|
| **框架** | Spring Boot 3 + Spring WebFlux | 响应式非阻塞框架 |
| **AI 集成** | LangChain4j | OpenAI 兼容协议调用 LLM |
| **AI 模型** | DeepSeek、阿里百炼（通义千问） | 多模型智能路由 + fallback |
| **向量数据库** | Milvus 2.4 | 向量存储与相似度搜索 |
| **全文检索** | Apache Lucene 9 | BM25 关键词检索（内存索引） |
| **缓存** | Caffeine、Redis | Embedding 缓存 + RAG 回答缓存 |
| **对象存储** | MinIO | 文件存储 |
| **安全** | Resilience4j、DFA 敏感词、Prompt 注入防护、XSS 过滤 | 限流 + 内容安全 |
| **监控** | Prometheus + Actuator | 指标采集 + 健康检查 |
| **文档解析** | PDFBox、Apache POI | PDF/DOCX 解析 |

---

## RAG 检索增强详解

### 检索流程

```
用户问题 → Embedding → 混合检索 → RRF 融合 → Rerank 重排序 → Top-K 上下文 → AI 回答
                ↕           ↕                        ↕
           Redis缓存    BM25 + 向量              qwen3-rerank
```

### 混合检索（Hybrid Search）

```java
// 并行执行两种检索
Mono<List<SearchMatch>> vectorMono = vectorSearch(query, topK);  // Milvus 余弦相似度
Mono<List<SearchMatch>> bm25Mono  = bm25Search(query, topK);    // Lucene BM25

// RRF（Reciprocal Rank Fusion）加权融合
// score = 0.7 × 1/(60 + vector_rank) + 0.3 × 1/(60 + bm25_rank)
List<SearchMatch> fused = rrfFuse(vectorResults, bm25Results, topK);
```

### Rerank 重排序（qwen3-rerank）

```json
// 请求
POST https://dashscope.aliyuncs.com/compatible-api/v1/reranks
{
  "model": "qwen3-rerank",
  "query": "不锈钢水瓶的温控性能指标",
  "documents": ["..."],
  "top_n": 5,
  "instruct": "Given a web search query, retrieve relevant passages that answer the query."
}

// 响应
{
  "results": [
    {"index": 0, "relevance_score": 0.93},
    {"index": 2, "relevance_score": 0.34}
  ]
}
```

### 产品名过滤

当用户提问涉及特定产品时，自动过滤无关文档：

```
问题："不锈钢水瓶的温控性能" → 提取关键词 [不锈钢水瓶, 温控, 性能]
→ 只保留标题/内容包含这些关键词的文档 → 排除咖啡杯文档
```

### Embedding 缓存

```java
// 单条文本嵌入：Redis 缓存（永久有效）
public Mono<float[]> embed(String text) {
    String cacheKey = "embedding:" + md5(text);
    return getFromCache(cacheKey)                           // 先查 Redis
        .switchIfEmpty(embedWithPrimary(text)               // 未命中调 API
            .doOnSuccess(emb -> saveToCache(cacheKey, emb)) // 写入缓存
        );
}

// 批量嵌入：逐条调用 embed()（复用缓存）
public Mono<List<float[]>> embedAll(List<String> texts) {
    return Mono.zip(texts.stream().map(this::embed).toList(), ...);
}
```

---

## 快速运行

需要 JDK 17+。项目已带 Maven Wrapper。

### 1. 启动基础设施（Docker Compose）

```bash
# 一键启动 Milvus + MinIO + Redis + etcd
docker compose up -d
```

或单独启动：

```bash
# Milvus（向量数据库）
docker run -d --name milvus \
  -p 19530:19530 -p 9091:9091 \
  -e ETCD_USE_EMBED=true \
  milvusdb/milvus:v2.4.10

# Redis（缓存）
docker run -d --name redis -p 6379:6379 redis:7-alpine

# MinIO（对象存储）
docker run -d --name minio -p 9000:9000 -p 9001:9001 \
  minio/minio server /minio_data --console-address ":9001"
```

### 2. 配置环境变量

```bash
# Windows PowerShell
$env:DEEPSEEK_API_KEY="sk-xxx"
$env:BAILIAN_API_KEY="sk-xxx"
$env:AI_GATEWAY_TOKENS="dev-token"

# Linux/macOS
export DEEPSEEK_API_KEY="sk-xxx"
export BAILIAN_API_KEY="sk-xxx"
export AI_GATEWAY_TOKENS="dev-token"
```

### 3. 运行应用

```bash
./mvnw spring-boot:run
```

### 4. 访问

| 服务 | URL |
|------|-----|
| 前端页面 | `http://localhost:8080/` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| 健康检查 | `http://localhost:8080/actuator/health` |
| Prometheus | `http://localhost:8080/actuator/prometheus` |

---

## 核心 API 示例

### 商机分析

```bash
curl -X POST http://localhost:8080/api/v1/trade/opportunities/analyze \
  -H "Content-Type: application/json" \
  -H "X-API-Token: dev-token" \
  -d '{
    "customerName": "John Smith",
    "companyName": "ABC Trading Inc",
    "country": "United States",
    "productName": "LED Panel 60x60",
    "quantity": "5000 pcs",
    "targetPrice": "$12.50/pc",
    "incoterm": "CIF Los Angeles",
    "destinationPort": "Los Angeles",
    "message": "Hi, we are interested in your LED panels..."
  }'
```

```json
{
  "opportunityId": "ABC-TRADING-INC-LED-PANEL-60X60",
  "leadScore": 78,
  "riskLevel": "MEDIUM",
  "buyingIntent": "HIGH_INTENT",
  "summary": "客户有明确采购数量和目的港...",
  "nextActions": ["发送阶梯报价表", "确认 UL 认证要求"]
}
```

### 上传产品文档

```bash
curl -X POST http://localhost:8080/api/v1/knowledge/documents/upload \
  -H "X-API-Token: dev-token" \
  -F "files=@product_manual.pdf" \
  -F "titlePrefix=产品说明书"
```

### RAG 问答

```bash
curl -X POST http://localhost:8080/api/v1/knowledge/chat/stream \
  -H "Content-Type: application/json" \
  -H "X-API-Token: dev-token" \
  -d '{"question": "不锈钢水瓶的温控性能指标？"}'
```

---

## 关键配置

配置入口：`src/main/resources/application.yml`

### 模型路由

```yaml
ai:
  gateway:
    routing:
      default-model: ALIABA_BAILIAN     # 默认模型
      fallback-order: ALIBABA_BAILIAN,DEEPSEEK  # 降级顺序
      long-context-threshold: 4000       # 长文本阈值
```

### RAG 配置

```yaml
rag:
  dimension: 1024                    # 向量维度（与 Embedding 模型一致）
  chunk-size: 200                    # 分块大小
  chunk-overlap: 35                  # 分块重叠
  similarity-threshold: 0.6          # 相似度阈值
  embedding:
    model: text-embedding-v4          # DashScope Embedding
  rerank:
    enabled: true
    model: qwen3-rerank               # 重排序模型
    top-n: 5                          # 返回 Top-5
  hybrid:
    enabled: true                     # 混合检索
    bm25-weight: 0.3                  # BM25 权重
    vector-weight: 0.7                # 向量权重
```

### 限流配置

```yaml
resilience4j:
  ratelimiter:
    instances:
      api:  { limitForPeriod: 100, limitRefreshPeriod: 1s }   # 通用 API
      chat: { limitForPeriod: 20,  limitRefreshPeriod: 1s }   # 问答接口
```

---

## 模型扩展

新增模型只需两步：

1. **application.yml 添加配置**：
```yaml
ai:
  gateway:
    models:
      new-model:
        provider: NEW_MODEL
        base-url: https://api.example.com
        path: /chat/completions
        api-key: ${NEW_API_KEY}
        model: new-chat-model
        capabilities: qa,reasoning
        weight: 1.0
```

2. **扩展 `ModelProvider` 枚举**：
```java
public enum ModelProvider {
    DEEPSEEK, ALIBABA_BAILIAN, NEW_MODEL
}
```

---

## 安全特性

| 特性 | 说明 |
|------|------|
| API Token 鉴权 | 请求头 `X-API-Token` 校验 |
| 敏感词过滤 | DFA 算法，支持动态管理 |
| Prompt 注入防护 | 检测并拦截注入攻击 |
| XSS 防护 | 支持拦截和清洗两种模式 |
| Resilience4j 限流 | API 100 req/s、Chat 20 req/s |

---