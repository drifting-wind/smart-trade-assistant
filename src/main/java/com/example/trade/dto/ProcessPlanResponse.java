package com.example.trade.dto;

import com.example.trade.enums.ModelProvider;

import java.time.Instant;
import java.util.List;

/**
 * 流程规划响应 DTO —— AI 生成的可执行流程计划。
 * 由 ProcessAssistantService.parsePlan() 从 AI 的 JSON 输出解析而来。
 */
public record ProcessPlanResponse(
        /**
         * 请求 ID —— 本次请求唯一标识（UUID），用于链路追踪。
         */
        String id,

        /**
         * 会话 ID —— 所属对话上下文，可用于后续追问和上下文延续。
         */
        String conversationId,

        /**
         * 流程名称 —— 从请求中的 processName 回显，或 AI 自动命名。
         */
        String processName,

        /**
         * 流程摘要 —— AI 对本次流程规划目标的简要描述。
         * 如果 AI 输出 JSON 解析失败，该字段会降级为原始文本内容。
         */
        String summary,

        /**
         * 任务列表 —— AI 将流程拆解为按顺序执行的 ProcessTaskDto 列表。
         * 每个任务包含 order（序号）、name（名称）、ownerRole（负责人）、actions（动作）、
         * acceptanceCriteria（验收标准）。
         */
        List<ProcessTaskDto> tasks,

        /**
         * 风险列表 —— AI 识别的潜在风险点，例如"工位可能紧张"。
         */
        List<String> risks,

        /**
         * 监控信号 —— 流程执行过程中需要持续跟踪的指标，例如"任务完成率"。
         */
        List<String> monitoringSignals,

        /**
         * 实际使用的 AI 模型 —— 可能与 preferredModel 不同（降级时）。
         */
        ModelProvider model,

        /**
         * 路由决策信息 —— 为什么选了这个模型、各模型打分情况。
         */
        RouteDecisionDto route,

        /**
         * 响应创建时间 —— UTC 时间戳。
         */
        Instant createdAt
) {
}
