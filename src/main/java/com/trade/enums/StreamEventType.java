package com.trade.enums;

/** SSE 流式事件类型 —— 控制前端对不同事件的展示逻辑 */
public enum StreamEventType {
    ROUTE,      // 路由决策事件
    TOKEN,      // 增量文本事件
    DONE,       // 完成事件
    ERROR,      // 错误事件
    CITATIONS   // 引用信息事件（流式结束后发送）
}
