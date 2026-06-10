# API 文档与示例

## 智能问答

### 非流式

```http
POST /api/v1/chat/completions
Content-Type: application/json
X-API-Token: dev-token
```

```json
{
  "conversationId": "c-001",
  "question": "请说明采购系统接口超时的排查步骤",
  "preciseMode": true,
  "metadata": {
    "department": "it"
  }
}
```

### SSE 流式

```http
POST /api/v1/chat/stream
Accept: text/event-stream
Content-Type: application/json
X-API-Token: dev-token
```

```json
{
  "conversationId": "c-001",
  "question": "继续给出 Java WebClient 的超时配置示例"
}
```

事件类型：

- `route`: 返回本次选用模型、fallback 模型、候选模型分数与原因
- `token`: 返回模型增量 token
- `done`: 输出完成
- `error`: 输出失败

## 流程助手

### 生成流程计划

```http
POST /api/v1/flows/assistant
Content-Type: application/json
X-API-Token: dev-token
```

```json
{
  "conversationId": "f-001",
  "processName": "合同审批流程",
  "currentState": "销售已提交合同草案，法务未审核",
  "goal": "拆解合同审批流程，明确步骤、负责人、风险和监控指标",
  "variables": {
    "amount": 120000,
    "customerLevel": "A"
  }
}
```

响应会包含 `tasks`、`risks`、`monitoringSignals`、`route` 等字段。

### 流式流程助手

```http
POST /api/v1/flows/assistant/stream
Accept: text/event-stream
Content-Type: application/json
X-API-Token: dev-token
```

请求体同上。适合前端边生成边渲染流程步骤。
