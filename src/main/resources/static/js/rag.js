/**
 * RAG 知识库问答
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
    let bubbleContent = msg.content;
    if (msg.sources && msg.sources.length > 0) {
      const sourceTags = msg.sources.map(s => `<span class="source-tag">${s.title || "文档"}</span>`).join("");
      bubbleContent += `<div class="source">📎 来源：${sourceTags}</div>`;
    }
    div.innerHTML = `
      <div class="rag-avatar">${avatar}</div>
      <div class="rag-bubble">${bubbleContent}</div>
    `;
    ragMessages.appendChild(div);
  });
  ragMessages.scrollTop = ragMessages.scrollHeight;
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
    const sources = [];
    let assistantIndex = -1;

    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      const chunk = decoder.decode(value, { stream: true });
      for (const line of chunk.split(/\r?\n/)) {
        if (!line.startsWith("data:")) continue;
        try {
          const eventData = JSON.parse(line.slice(5));
          if (eventData.type === "TOKEN") answer += eventData.content;
          if (eventData.type === "ROUTE" && eventData.route) {
            const match = eventData.route.reason && eventData.route.reason.match(/(\d+)\s*个相关文档/);
            if (match) sources.push({ title: `${match[1]} 个相关文档块` });
          }
        } catch (e) { /* skip malformed */ }
      }
      removeTypingIndicator();
      if (assistantIndex === -1) {
        ragSession.push({ role: "assistant", content: answer, sources });
        assistantIndex = ragSession.length - 1;
      } else {
        ragSession[assistantIndex] = { role: "assistant", content: answer, sources };
      }
      renderRagMessages();
      addTypingIndicator();
    }
    removeTypingIndicator();
    if (!answer) throw new Error("未收到回答");
    if (assistantIndex !== -1) {
      ragSession[assistantIndex] = { role: "assistant", content: answer, sources };
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
