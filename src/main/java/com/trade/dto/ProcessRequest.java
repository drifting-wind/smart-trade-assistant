package com.trade.dto;

import com.trade.enums.ModelProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 流程规划请求 DTO —— 客户端提交业务目标，要求 AI 生成可执行的流程计划。
 */
public record ProcessRequest(
        /**
         * 会话 ID —— 标识对话上下文，为空时自动生成 UUID。
         * 同一 conversationId 下的多次请求会携带历史记忆。
         */
        String conversationId,

        /**
         * 流程目标 —— 用户希望 AI 规划的业务流程或任务目标。
         * 必填（@NotBlank），最大 30000 字符，例如"新员工入职流程"。
         */
        @NotBlank @Size(max = 30000) String goal,

        /**
         * 流程名称 —— 给流程起一个标识名，用于响应回显和前端展示。
         * 可选，为空时默认使用"未命名流程"。
         */
        String processName,

        /**
         * 当前状态 —— 流程已执行到哪一步、遇到了什么阻塞。
         * 可选，最大 10000 字符，AI 会基于此状态生成下一步计划。
         */
        @Size(max = 10000) String currentState,

        /**
         * 首选模型 —— 指定希望用哪个 AI 模型生成流程计划。
         * 可选，FLOW 场景下路由器默认优先选阿里百炼（规划能力强）。
         */
        ModelProvider preferredModel,

        /**
         * 流程变量 —— 键值对形式的上下文参数，透传到 AI 的 prompt 中。
         * 例如 {"department": "研发部", "level": "P6"}，让 AI 基于变量定制流程。
         */
        Map<String, Object> variables
) {
}
