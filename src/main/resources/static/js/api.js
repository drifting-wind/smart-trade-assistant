/**
 * API 请求封装
 */

async function checkHealth() {
  try {
    const res = await fetch("/actuator/health");
    const data = await res.json();
    dot.className = "dot ok";
    health.textContent = `服务状态：${data.status}`;
  } catch (error) {
    dot.className = "dot bad";
    health.textContent = "服务未连接";
  }
}

function payload() {
  const preferredModel = document.getElementById("preferredModel").value || null;
  return {
    conversationId: "trade-opportunity-browser",
    customerName: document.getElementById("customerName").value,
    companyName: document.getElementById("companyName").value,
    country: document.getElementById("country").value,
    productName: document.getElementById("productName").value,
    quantity: document.getElementById("quantity").value,
    targetPrice: document.getElementById("targetPrice").value,
    incoterm: document.getElementById("incoterm").value,
    destinationPort: document.getElementById("destinationPort").value,
    message: document.getElementById("message").value,
    preferredModel
  };
}

async function postJson(url, body) {
  const res = await fetch(url, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-API-Token": document.getElementById("token").value
    },
    body: JSON.stringify(body)
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${res.status} ${text}`);
  return JSON.parse(text);
}
