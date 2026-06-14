# RAG 知识库 API 文档

## 目录
1. [单文件上传](#单文件上传)
2. [批量文件上传](#批量文件上传)
3. [文本摄入](#文本摄入)
4. [语义搜索](#语义搜索)
5. [RAG 问答](#rag-问答)
6. [删除文档](#删除文档)

---

## 单文件上传

### 接口信息
- **URL**: `/api/v1/knowledge/documents/upload`
- **Method**: `POST`
- **Content-Type**: `multipart/form-data`
- **认证**: `X-API-Token` 请求头

### 请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| `file` | File | 是 | 文件内容 |
| `title` | String | 否 | 文档标题（默认使用文件名） |
| `contentType` | String | 否 | 文件类型（默认自动推断） |
| `metadata` | JSON String | 否 | 元数据（JSON 格式） |

### 支持的文件类型
- **PDF** (.pdf)
- **DOC** (.doc) - Word 97-2003 格式
- **DOCX** (.docx) - Word 2007+ 格式
- **TXT** (.txt) - 纯文本
- **Markdown** (.md, .markdown)

### 请求示例

```bash
curl -X POST "http://localhost:8080/api/v1/knowledge/documents/upload" \
  -H "X-API-Token: dev-token" \
  -F "file=@product-manual.pdf" \
  -F "title=产品手册" \
  -F "contentType=pdf" \
  -F "metadata={\"category\":\"product\",\"department\":\"sales\"}"
```

### 响应示例

```json
{
  "documentId": "550e8400-e29b-41d4-a716-446655440000",
  "title": "产品手册",
  "chunkCount": 42,
  "status": "success",
  "message": null,
  "metadata": {
    "category": "product",
    "department": "sales"
  },
  "createdAt": "2026-06-12T10:30:00Z"
}
```

---

## 批量文件上传

### 接口信息
- **URL**: `/api/v1/knowledge/documents/upload/batch`
- **Method**: `POST`
- **Content-Type**: `multipart/form-data`
- **认证**: `X-API-Token` 请求头
- **限制**: 单次最多 10 个文件

### 请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| `files` | File[] | 是 | 文件列表（支持多文件） |
| `titlePrefix` | String | 否 | 标题前缀（会自动附加文件名） |
| `metadata` | JSON String | 否 | 元数据（应用于所有文件） |

### 请求示例

```bash
curl -X POST "http://localhost:8080/api/v1/knowledge/documents/upload/batch" \
  -H "X-API-Token: dev-token" \
  -F "files=@doc1.pdf" \
  -F "files=@doc2.docx" \
  -F "files=@readme.md" \
  -F "titlePrefix=产品文档" \
  -F "metadata={\"category\":\"product\"}"
```

### 响应示例

```json
{
  "totalCount": 3,
  "successCount": 2,
  "failedCount": 1,
  "results": [
    {
      "filename": "doc1.pdf",
      "documentId": "550e8400-e29b-41d4-a716-446655440000",
      "chunkCount": 42,
      "status": "success",
      "error": null
    },
    {
      "filename": "doc2.docx",
      "documentId": "660e8400-e29b-41d4-a716-446655440001",
      "chunkCount": 28,
      "status": "success",
      "error": null
    },
    {
      "filename": "unsupported.exe",
      "documentId": null,
      "chunkCount": 0,
      "status": "failed",
      "error": "不支持的文件类型"
    }
  ],
  "processedAt": "2026-06-12T10:30:00Z"
}
```

---

## 文本摄入

### 接口信息
- **URL**: `/api/v1/knowledge/documents`
- **Method**: `POST`
- **Content-Type**: `application/json`

### 请求示例

```bash
curl -X POST "http://localhost:8080/api/v1/knowledge/documents" \
  -H "X-API-Token: dev-token" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "外贸流程指南",
    "textContent": "第一步：客户询盘...",
    "contentType": "txt",
    "metadata": {"category": "process", "department": "sales"}
  }'
```

---

## 语义搜索

### 接口信息
- **URL**: `/api/v1/knowledge/search`
- **Method**: `GET`

### 请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| `query` | String | 是 | 搜索关键词 |

### 请求示例

```bash
curl "http://localhost:8080/api/v1/knowledge/search?query=如何报价" \
  -H "X-API-Token: dev-token"
```

---

## RAG 问答

### 同步问答

```bash
curl -X POST "http://localhost:8080/api/v1/knowledge/chat" \
  -H "X-API-Token: dev-token" \
  -H "Content-Type: application/json" \
  -d '{
    "question": "LED 产品的功率是多少？",
    "preferredModel": "DEEPSEEK"
  }'
```

### 流式问答（SSE）

```bash
curl -N -X POST "http://localhost:8080/api/v1/knowledge/chat/stream" \
  -H "X-API-Token: dev-token" \
  -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -d '{
    "question": "LED 产品的功率是多少？"
  }'
```

---

## 删除文档

### 接口信息
- **URL**: `/api/v1/knowledge/documents/{documentId}`
- **Method**: `DELETE`

### 请求示例

```bash
curl -X DELETE "http://localhost:8080/api/v1/knowledge/documents/550e8400-e29b-41d4-a716-446655440000" \
  -H "X-API-Token: dev-token"
```

---

## 错误码

| HTTP 状态码 | 说明 |
|-------------|------|
| 400 | 请求参数错误（如不支持的文件类型） |
| 401 | 认证失败（缺少或无效的 API Token） |
| 500 | 服务器内部错误 |

## 支持的文件类型汇总

| 文件类型 | 扩展名 | 解析方式 |
|----------|--------|----------|
| PDF | .pdf | Apache PDFBox |
| DOC | .doc | Apache POI HWPF |
| DOCX | .docx | Apache POI XWPF |
| TXT | .txt | 直接读取 |
| Markdown | .md, .markdown | 直接读取（保留格式） |
