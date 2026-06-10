# 部署指南

## 单机部署

```powershell
mvn clean package -DskipTests
java -jar target/smart-trade-assistant-1.0.0.jar
```

必要环境变量：

- `DEEPSEEK_API_KEY`
- `BAILIAN_API_KEY`
- `AI_GATEWAY_TOKENS`

## Docker 部署

```powershell
docker build -t smart-trade-assistant:1.0.0 .
docker run -p 8080:8080 `
  -e DEEPSEEK_API_KEY=sk-xxx `
  -e BAILIAN_API_KEY=sk-xxx `
  -e AI_GATEWAY_TOKENS=prod-token `
  smart-trade-assistant:1.0.0
```

## 集群扩展

- 前置网关保持 SSE 长连接超时时间大于模型超时时间。
- 多实例部署时，当前内存会话缓存适合单实例或粘性会话；生产可替换为 Redis。
- API Key 使用 Kubernetes Secret、Vault 或云厂商密钥服务注入。
- 监控采集 `/actuator/prometheus`，重点关注 `ai.route.total`、`ai.model.failed.total`、HTTP 延迟与连接池指标。

## 降级策略

路由结果内包含 `fallbackModel`。当主模型 API 超时、网络失败或响应异常时，服务会自动尝试 fallback 模型；若全部失败，返回 `MODEL_INVOCATION_ERROR`。
