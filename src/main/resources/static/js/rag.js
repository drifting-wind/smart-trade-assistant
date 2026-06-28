/**
 * RAG 知识库问答 - 优化版
 *
 * 优化内容：
 * 1. 根据 hasRelevantInfo 字段决定是否展示引用
 * 2. 使用后端返回的 citations 列表（去重后）
 * 3. hasRelevantInfo = false 时隐藏引用
 */

const ragMessages = document.getElementById("ragMessages");
const ragInput = document.getElementById("ragInput");
const ragSend = document.getElementById("ragSend");
let ragSession = [];
let ragLoading = false;

function askQuick(question) {
  ragInput.value = question;
  askRag();
}

/**
 * 渲染 RAG 消息列表
 */
function renderRagMessages() {
  if (ragSession.length === 0) {
    ragMessages.innerHTML = `
      <div class="rag-empty">
        <div class="rag-empty-icon">💡</div>
        <div>试试提问：产品有哪些认证？支持定制吗？</div>
      </div>`;
    return;
  }
  ragMessages.innerHTML = "";
  ragSession.forEach(msg => {
    const div = document.createElement("div");
    div.className = `rag-msg ${msg.role}`;
    const avatar = msg.role === "user" ? "我" : "📦";

    // 构建消息内容
    let bubbleContent = msg.content;

    // ⭐ 优化：只有 hasRelevantInfo = true 时才展示引用，只展示文件名（可点击打开文档）
    if (msg.hasRelevantInfo && msg.citations && msg.citations.length > 0) {
      const citationHtml = msg.citations.map(c => {
        const title = escapeHtml(c.title || "未命名文档");
        return `
          <div class="citation-item">
            <span class="citation-index">[${c.index}]</span>
            <a href="/api/v1/knowledge/documents/${c.documentId}/preview"
               target="_blank"
               class="citation-link"
               title="打开文档">${title}</a>
          </div>
        `;
      }).join("");
      bubbleContent += `<div class="citations-list"><div class="citations-title">📎 参考来源：</div>${citationHtml}</div>`;
    }

    div.innerHTML = `
      <div class="rag-avatar">${avatar}</div>
      <div class="rag-bubble">${bubbleContent}</div>
    `;
    ragMessages.appendChild(div);
  });
  ragMessages.scrollTop = ragMessages.scrollHeight;
}

/**
 * HTML 转义
 */
function escapeHtml(text) {
  if (!text) return "";
  const div = document.createElement("div");
  div.textContent = text;
  return div.innerHTML;
}

function addTypingIndicator() {
  const div = document.createElement("div");
  div.className = "rag-msg assistant";
  div.id = "ragTyping";
  div.innerHTML = `
    <div class="rag-avatar">📦</div>
    <div class="rag-bubble"><div class="rag-typing"><span></span><span></span><span></span></div></div>
  `;
  ragMessages.appendChild(div);
  ragMessages.scrollTop = ragMessages.scrollHeight;
}

function removeTypingIndicator() {
  const el = document.getElementById("ragTyping");
  if (el) el.remove();
}

/**
 * 发送 RAG 问答请求（流式）
 */
async function askRag() {
  const question = ragInput.value.trim();
  if (!question || ragLoading) return;
  ragLoading = true;
  ragSend.disabled = true;
  ragSession.push({ role: "user", content: question });
  renderRagMessages();
  ragInput.value = "";
  addTypingIndicator();

  try {
    const res = await fetch("/api/v1/knowledge/chat/stream", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
        "X-API-Token": document.getElementById("token").value
      },
      body: JSON.stringify({ question, conversationId: "rag-knowledge-base" })
    });
    if (!res.ok) throw new Error(`${res.status}`);
    const reader = res.body.getReader();
    const decoder = new TextDecoder("utf-8");
    let answer = "";
    let hasRelevantInfo = false;
    let citations = [];
    let assistantIndex = -1;
    let buffer = ""; // ⭐ SSE 分块缓冲区

    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split(/\r?\n/);
      buffer = lines.pop(); // 最后一行可能不完整，保留到下次
      for (const line of lines) {
        if (!line.startsWith("data:")) continue;
        try {
          const eventData = JSON.parse(line.slice(5));

          // 处理 TOKEN 事件
          if (eventData.type === "TOKEN") {
            answer += eventData.content;
          }

          // ⭐ 优化：处理 CITATIONS 事件（后端返回的引用信息）
          if (eventData.type === "CITATIONS" && eventData.citations) {
            citations = eventData.citations;
            // 从 citations 推断 hasRelevantInfo
            hasRelevantInfo = citations.length > 0;
          }

          // 处理 DONE 事件（包含 hasRelevantInfo 字段）
          if (eventData.type === "DONE") {
            // 直接从事件中获取 hasRelevantInfo（不是从 response 中）
            if (eventData.hasRelevantInfo !== undefined && eventData.hasRelevantInfo !== null) {
              hasRelevantInfo = eventData.hasRelevantInfo;
            }
            if (eventData.citations) {
              citations = eventData.citations;
            }
          }
        } catch (e) { /* skip malformed */ }
      }
      removeTypingIndicator();
      // ⭐ 只在 DONE 事件后才设置 citations，避免流过程中引用先于回答出现
      if (assistantIndex === -1) {
        ragSession.push({ role: "assistant", content: answer });
        assistantIndex = ragSession.length - 1;
      } else {
        ragSession[assistantIndex] = { role: "assistant", content: answer };
      }
      renderRagMessages();
      addTypingIndicator();
    }
    removeTypingIndicator();
    if (!answer) {
      // ⭐ 回答失败时清除之前推送的空消息
      if (assistantIndex !== -1) {
        ragSession.splice(assistantIndex, 1);
      }
      throw new Error("未收到回答");
    }
    // ⭐ 只在流结束后、有回答时，才添加引用来源
    if (assistantIndex !== -1) {
      ragSession[assistantIndex] = { role: "assistant", content: answer, hasRelevantInfo, citations };
    }
    renderRagMessages();
  } catch (error) {
    removeTypingIndicator();
    ragSession.push({ role: "assistant", content: `提问失败：${error.message}` });
    renderRagMessages();
  } finally {
    ragLoading = false;
    ragSend.disabled = false;
  }
}

function initRagEvents() {
  document.querySelectorAll(".rag-quick-btn").forEach(btn => {
    btn.addEventListener("click", () => {
      ragInput.value = btn.getAttribute("data-question");
      askRag();
    });
  });
  ragInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter") askRag();
  });
  ragSend.addEventListener("click", () => askRag());
}
