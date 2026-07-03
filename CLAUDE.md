# CLAUDE.md

## 技术栈

| 类别 | 技术 |
|------|------|
| 框架 | Spring Boot 3 + Spring WebFlux（响应式） |
| AI 集成 | LangChain4j（OpenAI 兼容协议） |
| 向量数据库 | Milvus |
| 搜索引擎 | Lucene（BM25） |
| 嵌入 & 重排 | DashScope（通义千问） |
| 缓存 | Caffeine、Redis |
| 对象存储 | MinIO |
| AI 模型 | DeepSeek、阿里百炼 |
| 安全 | Resilience4j 限流、DFA 敏感词过滤 |
| 监控 | Prometheus + Actuator |

## 必须遵守的规则

### 构建与运行

```bash
docker compose up -d              # 启动基础设施
./mvnw spring-boot:run            # 运行应用
./mvnw test -Dtest=ModelRouterTest  # 跑单个测试
docker build -t smart-trade-assistant:1.0.0 .  # 构建镜像
```

### 代码规范（必须遵守）

- **DTO 必须使用 Java record**，不使用普通 class
- **服务与控制器方法必须返回 `Mono`/`Flux`**，严禁在请求路径中调用 `.block()`
- **系统提示词集中放在 `PromptFactory`** — 不在此处修改视为违反规范
- 新增模型须同时扩展 `AiGatewayProperties` 的 `models` 配置块与 `ModelProvider` 枚举
- 遵循阿里巴巴Java开发手册

### 非显而易见的架构约束

- 路由评分权重（`ModelRouter`）：基础分 `50 × 权重`；QA 场景 +10；FLOW +24；长上下文(>4000字) +28；流程关键词 +18；推理/代码 +22；默认模型 +2
- 限流上限：api 100 req/s、chat 20 req/s、upload 10 req/min（请勿私自放宽）
- Caffeine 最多缓存 10 万会话，Redis RAG 答案缓存 24h

## 禁止指令
- 禁止使用System.out.println()
- 禁止使用硬编码字符串
- 禁止忽略编译警告

### 环境变量

`.env` 中定义：`DEEPSEEK_API_KEY`、`BAILIAN_API_KEY`（或 `DASHSCOPE_API_KEY`）、`AI_GATEWAY_TOKENS`、`REDIS_PASSWORD`
