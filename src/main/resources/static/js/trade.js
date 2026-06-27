/**
 * 商机评估、销售计划、客户回复
 */

function setBusy(button, busy) {
  button.disabled = busy;
  button.textContent = busy ? "处理中..." : button.dataset.label;
}

function show(name) {
  summary.classList.toggle("hidden", name !== "summary");
  tasks.classList.toggle("hidden", name !== "tasks");
  replyBox.classList.toggle("hidden", name !== "reply");
  document.querySelectorAll(".tab").forEach(tab => tab.classList.remove("active"));
  document.getElementById(name === "summary" ? "tabSummary" : name === "tasks" ? "tabTasks" : "tabReply").classList.add("active");
}

function updateMetrics(data) {
  score.textContent = data.leadScore ?? "-";
  risk.textContent = data.riskLevel ?? "-";
  intent.textContent = data.buyingIntent ?? "已生成评估";
  // 同模型不重复显示，不同时说明发生了降级
  const m = data.model || "-";
  const r = data.route?.selectedModel;
  model.textContent = r && m !== r ? `${m}（降级自 ${r}）` : m;
  next.textContent = (data.nextActions || []).slice(0, 2).join("；") || "查看详情";
}

function renderTasks(plan) {
  tasks.innerHTML = "";
  (plan.tasks || []).forEach(task => {
    const el = document.createElement("div");
    el.className = "task";
    el.innerHTML = `
      <div class="task-title">${task.order}. ${task.name}</div>
      <div class="task-meta">${task.ownerRole} · ${task.status}</div>
      <ul>${(task.actions || []).map(action => `<li>${action}</li>`).join("")}</ul>
    `;
    tasks.appendChild(el);
  });
  if (!plan.tasks || plan.tasks.length === 0) {
    tasks.textContent = "没有生成任务。";
  }
}

function initTradeEvents() {
  document.querySelectorAll(".actions button").forEach(button => button.dataset.label = button.textContent);
  document.getElementById("tabSummary").addEventListener("click", () => show("summary"));
  document.getElementById("tabTasks").addEventListener("click", () => show("tasks"));
  document.getElementById("tabReply").addEventListener("click", () => show("reply"));

  document.getElementById("analyze").addEventListener("click", async event => {
    const button = event.currentTarget;
    setBusy(button, true);
    show("summary");
    summary.innerHTML = "<p>正在评估商机...</p>";
    try {
      const data = await postJson("/api/v1/trade/opportunities/analyze", payload());
      updateMetrics(data);
      // ⭐ 使用后端返回的 summary（HTML 表格样式），而非原始 JSON
      if (data.summary) {
        summary.innerHTML = data.summary;
      } else {
        summary.innerHTML = "<pre>" + JSON.stringify(data, null, 2) + "</pre>";
      }
    } catch (error) {
      summary.innerHTML = `<p style="color:red">评估失败：${error.message}</p>`;
    } finally {
      setBusy(button, false);
    }
  });

  document.getElementById("plan").addEventListener("click", async event => {
    const button = event.currentTarget;
    setBusy(button, true);
    show("tasks");
    tasks.textContent = "正在生成销售推进计划...";
    try {
      const data = await postJson("/api/v1/trade/opportunities/sales-plan", payload());
      const m2 = data.model || "-";
      const r2 = data.route?.selectedModel;
      model.textContent = r2 && m2 !== r2 ? `${m2}（降级自 ${r2}）` : m2;
      next.textContent = (data.negotiationPoints || []).slice(0, 2).join("；") || "推进计划已生成";
      renderTasks(data);
    } catch (error) {
      tasks.textContent = `生成失败：${error.message}`;
    } finally {
      setBusy(button, false);
    }
  });

  document.getElementById("reply").addEventListener("click", async event => {
    const button = event.currentTarget;
    setBusy(button, true);
    show("reply");
    replyBox.textContent = "";
    try {
      const res = await fetch("/api/v1/trade/opportunities/reply/stream", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Accept": "text/event-stream",
          "X-API-Token": document.getElementById("token").value
        },
        body: JSON.stringify(payload())
      });
      if (!res.ok || !res.body) throw new Error(`${res.status} ${await res.text()}`);
      const reader = res.body.getReader();
      const decoder = new TextDecoder("utf-8");
      // ⭐ 缓冲区：处理 SSE 分块传输时 data 行可能被截断的情况
      let buffer = "";
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        // 按行分割，最后一行可能不完整，保留到下次拼接
        const lines = buffer.split(/\r?\n/);
        buffer = lines.pop(); // 最后一行不完整，放回缓冲区
        for (const line of lines) {
          if (!line.startsWith("data:")) continue;
          try {
            const eventData = JSON.parse(line.slice(5));
            if (eventData.type === "TOKEN") replyBox.textContent += eventData.content;
            if (eventData.type === "ROUTE") {
              const sm = eventData.route.selectedModel;
              const fm = eventData.route.fallbackModel;
              model.textContent = fm && fm !== sm ? `${sm}（备用 ${fm}）` : sm;
            }
          } catch (parseError) {
            // ⭐ 单条事件解析失败不影响后续事件，仅记录
            console.warn("SSE 事件解析跳过：", parseError.message, line.slice(0, 100));
          }
        }
        replyBox.scrollTop = replyBox.scrollHeight;
      }
      // 处理流结束后缓冲区中剩余的内容
      if (buffer.startsWith("data:")) {
        try {
          const eventData = JSON.parse(buffer.slice(5));
          if (eventData.type === "TOKEN") replyBox.textContent += eventData.content;
          if (eventData.type === "ROUTE") {
            const sm2 = eventData.route.selectedModel;
            const fm2 = eventData.route.fallbackModel;
            model.textContent = fm2 && fm2 !== sm2 ? `${sm2}（备用 ${fm2}）` : sm2;
          }
        } catch (parseError) {
          console.warn("SSE 末尾事件解析跳过：", parseError.message);
        }
      }
    } catch (error) {
      replyBox.textContent = `生成失败：${error.message}`;
    } finally {
      setBusy(button, false);
    }
  });
}
