# 外贸智能销售助手（Smart Trade Assistant）

面向外贸销售团队的 AI 智能助手平台，基于多模型智能路由与 Spring WebFlux 响应式架构，为外贸业务员提供商机分析、销售计划生成、客户邮件撰写等 AI 辅助能力。

## 核心能力

- **多模型智能路由**：根据场景类型（QA/FLOW）、内容关键词、精确模式为 DeepSeek 和通义千问打分，自动选择最优模型并实现 fallback 降级
- **外贸销售三件套**：商机分析（lead 打分 + 风险评估）、销售推进计划（任务拆解）、客户英文回复邮件生成
- **智能问答**：通用 QA 场景，支持多轮对话记忆
- **流程规划助手**：业务流程自动拆解为可执行任务计划
- **SSE 流式输出**：Spring WebFlux Flux + SSE 实现打字机效果实时推送
- **企业级能力**：API Token 鉴权、统一异常处理、超时重试、连接池、Caffeine 缓存、Actuator/Prometheus 监控
- **技术栈**：Spring Boot 3、Spring WebFlux、Project Reactor、Jackson、Caffeine、Micrometer

## 快速运行

需要 JDK 17+。项目已带 Maven Wrapper，也可使用本机 Maven 3.9+。

```powershell
$env:DEEPSEEK_API_KEY="sk-xxx"
$env:BAILIAN_API_KEY="sk-xxx"
$env:AI_GATEWAY_TOKENS="dev-token"
.\mvnw.cmd spring-boot:run
```

访问：

- OpenAPI UI: `http://localhost:8080/swagger-ui.html`
- 健康检查: `http://localhost:8080/actuator/health`

## 关键配置

配置入口在 `src/main/resources/application.yml`：

- `ai.gateway.models.deepseek.*`: DeepSeek 的 base-url、path、model、api-key、能力标签
- `ai.gateway.models.bailian.*`: 阿里百炼的 base-url、path、model、api-key、能力标签
- `ai.gateway.routing.*`: 默认模型、fallback 顺序、长上下文阈值
- `ai.gateway.security.tokens`: 允许访问业务 API 的 Token 列表
- `spring.ai.dashscope.enabled`: 默认 `false`，需要启用 Spring AI Alibaba DashScope 自动装配时改为 `true` 并配置 `spring.ai.dashscope.api-key`

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

## 容器化

```powershell
docker build -t smart-trade-assistant:1.0.0 .
docker compose up -d
```

生产环境建议把 `DEEPSEEK_API_KEY`、`BAILIAN_API_KEY`、`AI_GATEWAY_TOKENS` 放到密钥管理系统，不提交到仓库。
