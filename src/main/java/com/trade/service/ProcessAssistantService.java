package com.trade.service;

import com.trade.dto.AiStreamEvent;
import com.trade.dto.ProcessPlanResponse;
import com.trade.dto.ProcessRequest;
import com.trade.dto.ProcessTaskDto;
import com.trade.enums.ModelProvider;
import com.trade.enums.ScenarioType;
import com.trade.enums.TaskStatus;
import com.trade.exception.ModelInvocationException;
import com.trade.model.AiModelResponse;
import com.trade.model.AiPromptRequest;
import com.trade.model.ModelRegistry;
import com.trade.monitoring.AiMetrics;
import com.trade.router.ModelRouteRequest;
import com.trade.router.ModelRouter;
import com.trade.router.RouteDecision;
import com.trade.router.RouteMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 流程编排助手服务 —— 处理 /api/v1/flows 请求的核心业务逻辑。
 *
 * 与 ChatOrchestrationService 的区别：
 * - Chat 用于普通问答，返回文本答案
 * - Process 用于业务流程规划，要求 AI 输出结构化的 JSON（含任务拆解、风险、监控指标）
 *
 * 业务流程：
 * 1. 将 ProcessRequest（目标、流程名、当前状态、变量）转为 AiPromptRequest
 * 2. 智能路由（FLOW 场景优先阿里百炼，因为长上下文和规划能力更强）
 * 3. 调用模型 + 降级
 * 4. JSON 解析 —— 将 AI 的 JSON 输出解析为 ProcessPlanResponse（含 ProcessTaskDto 列表）
 * 5. 解析失败时降级为 fallbackPlan（默认模板），保证服务不中断
 *
 * 技术难点：AI 模型输出的 JSON 可能不严格（含 Markdown 代码块包裹等），
 * 因此用 extractJson 方法从原始文本中提取最外层 { } 之间的内容再解析。
 */
@Service
public class ProcessAssistantService {

    /** ProcessRequest → AiPromptRequest 转换器 */
    private final AiRequestMapper mapper;
    /** 会话历史管理 */
    private final ConversationMemoryService memoryService;
    /** System Prompt 工厂 —— flowSystemPrompt 要求 AI 输出 JSON */
    private final PromptFactory promptFactory;
    /** 智能路由器 */
    private final ModelRouter modelRouter;
    /** 路由决策 DTO 转换器 */
    private final RouteMapper routeMapper;
    /** 模型注册中心 */
    private final ModelRegistry modelRegistry;
    /** JSON 解析器 —— 解析 AI 输出的 JSON 结构 */
    private final ObjectMapper objectMapper;
    /** 指标埋点 */
    private final AiMetrics metrics;

    public ProcessAssistantService(
            AiRequestMapper mapper,
            ConversationMemoryService memoryService,
            PromptFactory promptFactory,
            ModelRouter modelRouter,
            RouteMapper routeMapper,
            ModelRegistry modelRegistry,
            ObjectMapper objectMapper,
            AiMetrics metrics
    ) {
        this.mapper = mapper;
        this.memoryService = memoryService;
        this.promptFactory = promptFactory;
        this.modelRouter = modelRouter;
        this.routeMapper = routeMapper;
        this.modelRegistry = modelRegistry;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /**
     * 流程规划 —— 让 AI 将业务目标拆解为可执行的任务计划。
     *
     * 业务流程：
     * 1. 构造 prompt：系统提示词要求 AI 输出 JSON 格式的流程计划
     * 2. 智能路由：FLOW 场景，默认精确模式关闭
     * 3. 调用模型 + 降级
     * 4. parsePlan：解析 AI 的 JSON 输出为结构化的 ProcessPlanResponse
     * 5. 保存会话记录
     */
    public Mono<ProcessPlanResponse> plan(ProcessRequest request) {
        AiPromptRequest prompt = mapper.flowToPrompt(
                request,
                memoryService.history(ConversationIds.ensure(request.conversationId())),
                promptFactory.flowSystemPrompt()
        );
        RouteDecision route = route(prompt, request.preferredModel());
        return invokeWithFallback(prompt, route)
                .map(response -> parsePlan(prompt, request, route, response))
                .doOnNext(plan -> memoryService.appendTurn(plan.conversationId(), request.goal(), plan.summary()));
    }

    /**
     * 流程规划流式响应 —— SSE 实时推送流程规划文本。
     *
     * 与 plan() 的区别：不解析 JSON，直接推送原始文本流。
     * 适用于需要实时展示生成过程的场景。
     */
    public Flux<AiStreamEvent> stream(ProcessRequest request) {
        AiPromptRequest prompt = mapper.flowToPrompt(
                request,
                memoryService.history(ConversationIds.ensure(request.conversationId())),
                promptFactory.flowSystemPrompt()
        );
        RouteDecision route = route(prompt, request.preferredModel());
        String eventId = UUID.randomUUID().toString();
        StringBuilder answer = new StringBuilder();
        Flux<AiStreamEvent> tokens = modelRegistry.require(route.selectedModel()).stream(prompt)
                .onErrorResume(ModelInvocationException.class, error -> {
                    metrics.failed(ScenarioType.FLOW, route.selectedModel());
                    if (route.fallbackModel() == null) {
                        return Flux.error(error);
                    }
                    return modelRegistry.require(route.fallbackModel()).stream(prompt);
                })
                .doOnNext(token -> answer.append(token.content()))
                .map(token -> AiStreamEvent.token(eventId, token.provider(), token.content()))
                .concatWith(Mono.fromSupplier(() -> {
                    memoryService.appendTurn(prompt.conversationId(), request.goal(), answer.toString());
                    return AiStreamEvent.done(eventId, route.selectedModel());
                }))
                .onErrorResume(error -> Mono.just(AiStreamEvent.error(eventId, route.selectedModel(), error.getMessage())));
        return Flux.concat(Mono.just(AiStreamEvent.route(eventId, routeMapper.toDto(route))), tokens);
    }

    /** FLOW 场景路由 —— 精确模式默认关闭（流程规划不需要代码级精确） */
    private RouteDecision route(AiPromptRequest prompt, ModelProvider preferredModel) {
        RouteDecision route = modelRouter.route(new ModelRouteRequest(
                ScenarioType.FLOW,
                prompt.joinedContent(),
                preferredModel,
                false
        ));
        //AI 指标埋点 —— 通过 Micrometer 统计路由和调用的关键指标。
        metrics.routed(ScenarioType.FLOW, route.selectedModel());
        return route;
    }

    /** 模型调用 + 自动降级（同 ChatOrchestrationService 的逻辑，但场景为 FLOW） */
    private Mono<AiModelResponse> invokeWithFallback(AiPromptRequest prompt, RouteDecision route) {
        return modelRegistry.require(route.selectedModel()).complete(prompt)
                .onErrorResume(ModelInvocationException.class, error -> {
                    metrics.failed(ScenarioType.FLOW, route.selectedModel());
                    if (route.fallbackModel() == null) {
                        return Mono.error(error);
                    }
                    return modelRegistry.require(route.fallbackModel()).complete(prompt);
                });
    }

    /**
     * 解析 AI 输出的 JSON 为结构化的 ProcessPlanResponse。
     *
     * 技术难点：AI 输出可能不是严格 JSON（可能包裹在 Markdown 代码块中，或附带解释文字），
     * 解决：extractJson 方法提取第一个 { 到最后一个 } 之间的内容，再用 Jackson 解析。
     * 如果解析失败，降级为 fallbackPlan —— 返回一个默认模板，保证服务不中断。
     */
    private ProcessPlanResponse parsePlan(
            AiPromptRequest prompt,
            ProcessRequest request,
            RouteDecision route,
            AiModelResponse response
    ) {
        try {
            JsonNode root = objectMapper.readTree(extractJson(response.content()));
            return new ProcessPlanResponse(
                    prompt.requestId(),
                    prompt.conversationId(),
                    text(root, "processName", fallbackProcessName(request)),
                    text(root, "summary", response.content()),
                    tasks(root.path("tasks")),
                    strings(root.path("risks")),
                    strings(root.path("monitoringSignals")),
                    response.provider(),
                    routeMapper.toDto(route),
                    Instant.now()
            );
        } catch (Exception ignored) {
            return fallbackPlan(prompt, request, route, response);
        }
    }

    /**
     * JSON 解析失败时的降级方案 —— 返回默认流程模板。
     * 适用于 AI 输出不规范、网络截断、JSON 格式错误等场景。
     */
    private ProcessPlanResponse fallbackPlan(AiPromptRequest prompt, ProcessRequest request, RouteDecision route, AiModelResponse response) {
        ProcessTaskDto task = new ProcessTaskDto(
                1,
                "根据模型输出执行流程拆解",
                "流程负责人",
                TaskStatus.TODO,
                List.of(response.content()),
                List.of("任务已被负责人确认", "后续步骤与风险已记录")
        );
        return new ProcessPlanResponse(
                prompt.requestId(),
                prompt.conversationId(),
                fallbackProcessName(request),
                response.content(),
                List.of(task),
                List.of("模型输出不是严格 JSON，已降级为文本流程计划"),
                List.of("任务完成率", "阻塞任务数", "超时任务数"),
                response.provider(),
                routeMapper.toDto(route),
                Instant.now()
        );
    }

    /** 解析 JSON 数组为 ProcessTaskDto 列表 —— 用于解析 tasks 字段 */
    private List<ProcessTaskDto> tasks(JsonNode node) {
        List<ProcessTaskDto> tasks = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode task : node) {
                tasks.add(new ProcessTaskDto(
                        task.path("order").asInt(tasks.size() + 1),
                        text(task, "name", "未命名任务"),
                        text(task, "ownerRole", "流程负责人"),
                        status(text(task, "status", "TODO")),
                        strings(task.path("actions")),
                        strings(task.path("acceptanceCriteria"))
                ));
            }
        }
        return tasks;
    }

    /** 解析 JSON 字符串数组 —— 用于解析 risks、monitoringSignals 等字段 */
    private List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> values.add(item.asText()));
        }
        return values;
    }

    /** 安全转换字符串为 TaskStatus 枚举，解析失败返回 TODO */
    private TaskStatus status(String value) {
        try {
            return TaskStatus.valueOf(value);
        } catch (Exception ignored) {
            return TaskStatus.TODO;
        }
    }

    /** 安全获取 JSON 文本字段 —— 字段不存在或为空时返回 fallback 默认值 */
    private String text(JsonNode node, String field, String fallback) {
        return node.hasNonNull(field) ? node.get(field).asText(fallback) : fallback;
    }

    /**
     * 从 AI 输出中提取 JSON 字符串 —— 支持 Markdown 代码块、前后文本包裹、截断修复。
     *
     * AI 模型返回的 JSON 可能有多种包裹形式：
     * 1. 纯 JSON：{"key": "value"}
     * 2. Markdown 代码块：```json\n{"key": "value"}\n```
     * 3. 前后文本包裹：以下是分析结果：\n{"key": "value"}\n以上是分析结果
     * 4. 截断的 JSON：{"key": "value (缺少闭合括号，尝试自动修复)
     */
    private String extractJson(String content) {
        if (content == null || content.isBlank()) {
            return "{}";
        }

        String trimmed = content.trim();

        // 1. 尝试提取 Markdown 代码块 ```json ... ```
        int codeBlockStart = trimmed.indexOf("```json");
        if (codeBlockStart >= 0) {
            int jsonStart = trimmed.indexOf('\n', codeBlockStart);
            int codeBlockEnd = trimmed.indexOf("```", jsonStart);
            if (jsonStart >= 0 && codeBlockEnd > jsonStart) {
                return trimmed.substring(jsonStart + 1, codeBlockEnd).trim();
            }
        }
        // 也尝试 ``` ... ``` (无 json 标记)
        codeBlockStart = trimmed.indexOf("```");
        if (codeBlockStart >= 0) {
            int jsonStart = trimmed.indexOf('\n', codeBlockStart);
            int codeBlockEnd = trimmed.indexOf("```", jsonStart);
            if (jsonStart >= 0 && codeBlockEnd > jsonStart) {
                return trimmed.substring(jsonStart + 1, codeBlockEnd).trim();
            }
        }

        // 2. 提取第一个 { 到最后一个 } 的内容
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            String json = trimmed.substring(start, end + 1);
            // 尝试修复截断的 JSON（缺少闭合引号/括号）
            return repairTruncatedJson(json);
        }

        // 3. 兜底：返回空 JSON 对象，让 parsePlan 走 fallback 分支
        return "{}";
    }

    /**
     * 修复截断的 JSON —— 当 AI 模型输出因 token 限制被截断时，尝试补全闭合。
     */
    private String repairTruncatedJson(String json) {
        String trimmed = json.trim();
        if (trimmed.endsWith("}") || trimmed.endsWith("]")) {
            if (isBracketsBalanced(trimmed)) {
                return trimmed;
            }
        }
        String repaired = trimIncompleteTrailingKey(json);
        repaired = repairQuotes(repaired);
        repaired = repairBrackets(repaired);
        return repaired;
    }

    private String trimIncompleteTrailingKey(String json) {
        for (int i = json.length() - 1; i >= 0; i--) {
            char c = json.charAt(i);
            if (c == ',') {
                String after = json.substring(i + 1).trim();
                if (after.isEmpty() || !after.startsWith("\"")) {
                    return json.substring(0, i);
                }
                if (isCompleteKeyValue(after)) {
                    return json;
                }
                return json.substring(0, i);
            }
        }
        return json;
    }

    private boolean isCompleteKeyValue(String s) {
        if (!s.startsWith("\"")) return false;
        int colonIdx = s.indexOf(':');
        if (colonIdx < 0) return false;
        String value = s.substring(colonIdx + 1).trim();
        if (value.isEmpty()) return false;
        if (value.startsWith("\"")) {
            return value.endsWith("\"") && value.length() > 1;
        }
        return value.matches(".*[0-9true|false|null]\\s*$");
    }

    private String repairQuotes(String json) {
        int quoteCount = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; quoteCount++; }
        }
        if (inString) { json = json + "\""; }
        return json;
    }

    private String repairBrackets(String json) {
        int braceCount = 0;
        int bracketCount = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (!inString) {
                if (c == '{') braceCount++;
                else if (c == '}') braceCount--;
                else if (c == '[') bracketCount++;
                else if (c == ']') bracketCount--;
            }
        }
        StringBuilder sb = new StringBuilder(json);
        for (int i = 0; i < bracketCount; i++) sb.append(']');
        for (int i = 0; i < braceCount; i++) sb.append('}');
        return sb.toString();
    }

    private boolean isBracketsBalanced(String json) {
        int braceCount = 0;
        int bracketCount = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (!inString) {
                if (c == '{') braceCount++;
                else if (c == '}') braceCount--;
                else if (c == '[') bracketCount++;
                else if (c == ']') bracketCount--;
            }
        }
        return braceCount == 0 && bracketCount == 0;
    }

    /** 流程名称为空时使用默认名 */
    private String fallbackProcessName(ProcessRequest request) {
        return request.processName() == null || request.processName().isBlank() ? "业务流程" : request.processName();
    }
}
