/**
 * 应用入口 —— 初始化所有模块
 */

// 页面加载完成后初始化
document.addEventListener("DOMContentLoaded", () => {
  checkHealth();
  initTradeEvents();
  initRagEvents();
});
