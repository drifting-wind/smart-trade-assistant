package com.example.trade.dto;

import com.example.trade.enums.TaskStatus;

import java.util.List;

/**
 * 流程任务 DTO —— 流程计划中的单个可执行任务。
 * 由 AI 从 JSON 输出中解析，每个 task 对应一个可跟踪、可分配的工作单元。
 */
public record ProcessTaskDto(
        /**
         * 任务序号 —— 在流程中的执行顺序，从 1 开始递增。
         * 前端按此字段排序展示任务列表。
         */
        int order,

        /**
         * 任务名称 —— 简短描述该任务要做什么，例如"分配工位和电脑"。
         */
        String name,

        /**
         * 负责人角色 —— 该任务应由哪个角色执行，例如"行政"、"IT"、"HR"。
         * 不是具体人名，而是角色标识，方便系统自动派单。
         */
        String ownerRole,

        /**
         * 任务状态 —— TODO（待处理）、RUNNING（执行中）、BLOCKED（阻塞）、DONE（已完成）。
         * 由 AI 根据流程上下文推断初始状态，后续由业务系统更新。
         */
        TaskStatus status,

        /**
         * 执行动作列表 —— 完成该任务需要做的具体操作，例如["分配工位", "领取电脑"]。
         */
        List<String> actions,

        /**
         * 验收标准列表 —— 判断该任务是否完成的标准，例如["工位已确认", "电脑已发放"]。
         */
        List<String> acceptanceCriteria
) {
}
