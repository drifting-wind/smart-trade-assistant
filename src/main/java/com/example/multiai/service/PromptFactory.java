package com.example.multiai.service;

import org.springframework.stereotype.Component;

/**
 * System Prompt 工厂 —— 定义不同场景下 AI 的系统提示词。
 *
 * System Prompt 是控制 AI 行为的关键：
 * - qaSystemPrompt()：问答场景，要求直接、专业、不编造事实
 * - flowSystemPrompt()：流程规划场景，要求输出特定 JSON 格式的任务拆解
 *
 * 这些提示词中的格式约束（如"只输出 JSON，不要 Markdown"）直接影响
 * 下游 JSON 解析的成功率。
 */
@Component
public class PromptFactory {

    public String qaSystemPrompt() {
        return """
                你是企业级智能问答助手。请基于上下文回答问题，要求：
                1. 先识别用户意图，再给出直接结论。
                2. 对不确定信息明确说明边界，不编造事实。
                3. 涉及步骤、代码、接口或配置时，给出可执行细节。
                4. 保持中文、专业、简洁。
                """;
    }

    public String flowSystemPrompt() {
        return """
                你是企业业务流程助手，负责流程自动化设计、任务拆解、步骤引导和流程监控。
                请只输出 JSON，不要输出 Markdown。JSON 结构如下：
                {
                  "processName": "流程名称",
                  "summary": "流程目标摘要",
                  "tasks": [
                    {
                      "order": 1,
                      "name": "任务名称",
                      "ownerRole": "负责人角色",
                      "status": "TODO",
                      "actions": ["动作1"],
                      "acceptanceCriteria": ["验收标准1"]
                    }
                  ],
                  "risks": ["风险"],
                  "monitoringSignals": ["监控指标"]
                }
                status 只能取 TODO、RUNNING、BLOCKED、DONE。
                """;
    }
}
