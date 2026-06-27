package com.trade.trade.service;

import com.trade.dto.AiStreamEvent;
import com.trade.dto.ProcessTaskDto;
import com.trade.enums.MessageRole;
import com.trade.enums.ModelProvider;
import com.trade.enums.ScenarioType;
import com.trade.enums.TaskStatus;
import com.trade.exception.ModelInvocationException;
import com.trade.model.AiModelResponse;
import com.trade.model.AiPromptMessage;
import com.trade.model.AiPromptRequest;
import com.trade.model.ModelRegistry;
import com.trade.monitoring.AiMetrics;
import com.trade.router.ModelRouteRequest;
import com.trade.router.ModelRouter;
import com.trade.router.RouteDecision;
import com.trade.router.RouteMapper;
import com.trade.trade.dto.OpportunityAnalysisResponse;
import com.trade.trade.dto.SalesPlanResponse;
import com.trade.trade.dto.TradeInquiryRequest;
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
 * 外贸销售服务 —— /api/v1/trade 接口背后的业务逻辑。
 *
 * 业务场景：外贸业务员收到客户询盘后，用 AI 辅助完成三件事：
 * 1. analyze()：商机分析 —— 分析询盘质量（打分、风险等级、购买意图、推荐产品）
 * 2. salesPlan()：销售计划 —— 将询盘转化为可执行的销售推进计划（任务拆解、谈判要点、单证准备）
 * 3. streamCustomerReply()：生成回复邮件 —— 流式输出一封可以直接发给客户的英文邮件
 *
 * 与通用 ChatOrchestrationService/ProcessAssistantService 的区别：
 * - 不使用 ConversationMemoryService（外贸询盘一般是单轮，不依赖多轮历史）
 * - System Prompt 是外贸领域专用的（分析、规划、邮件模板）
 * - JSON 解析逻辑针对外贸业务字段定制（leadScore、riskLevel、pricingAdvice 等）
 */
@Service
public class TradeSalesService {

    /** 智能路由器 */
    private final ModelRouter modelRouter;
    /** 路由决策 DTO 转换器 */
    private final RouteMapper routeMapper;
    /** 模型注册中心 */
    private final ModelRegistry modelRegistry;
    /** JSON 解析器 */
    private final ObjectMapper objectMapper;
    /** 指标埋点 */
    private final AiMetrics metrics;

    public TradeSalesService(
            ModelRouter modelRouter,
            RouteMapper routeMapper,
            ModelRegistry modelRegistry,
            ObjectMapper objectMapper,
            AiMetrics metrics
    ) {
        this.modelRouter = modelRouter;
        this.routeMapper = routeMapper;
        this.modelRegistry = modelRegistry;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /**
     * 商机分析 —— AI 分析询盘质量，返回结构化结果。
     *
     * 输出字段：leadScore(0-100)、riskLevel、buyingIntent、recommendedProducts、missingInformation 等。
     * 场景为 QA，开启精确模式（需要 DeepSeek 的推理能力做分析）。
     */
    public Mono<OpportunityAnalysisResponse> analyze(TradeInquiryRequest request) {
        AiPromptRequest prompt = prompt(request, ScenarioType.QA, analysisSystemPrompt(), inquiryContent(request));
        RouteDecision route = route(prompt, request.preferredModel(), true);
        return invokeWithFallback(prompt, route)
                .map(response -> parseAnalysis(prompt, request, route, response));
    }

    /**
     * 销售推进计划 —— 将询盘转化为可执行的销售流程。
     *
     * 输出字段：tasks（任务列表）、negotiationPoints（谈判要点）、requiredDocuments（所需单证）等。
     * 场景为 FLOW，关闭精确模式（流程规划不需要代码级精确）。
     */
    public Mono<SalesPlanResponse> salesPlan(TradeInquiryRequest request) {
        AiPromptRequest prompt = prompt(request, ScenarioType.FLOW, planSystemPrompt(), inquiryContent(request));
        RouteDecision route = route(prompt, request.preferredModel(), false);
        return invokeWithFallback(prompt, route)
                .map(response -> parsePlan(prompt, request, route, response));
    }

    /**
     * 流式生成客户回复邮件 —— SSE 推送 AI 逐字输出的英文邮件正文。
     * 场景为 QA，开启精确模式。
     */
    public Flux<AiStreamEvent> streamCustomerReply(TradeInquiryRequest request) {
        AiPromptRequest prompt = prompt(request, ScenarioType.QA, replySystemPrompt(), inquiryContent(request));
        RouteDecision route = route(prompt, request.preferredModel(), true);
        String eventId = UUID.randomUUID().toString();
        Flux<AiStreamEvent> tokens = modelRegistry.require(route.selectedModel()).stream(prompt)
                .onErrorResume(ModelInvocationException.class, error -> {
                    metrics.failed(ScenarioType.QA, route.selectedModel());
                    if (route.fallbackModel() == null) {
                        return Flux.error(error);
                    }
                    return modelRegistry.require(route.fallbackModel()).stream(prompt);
                })
                .map(token -> AiStreamEvent.token(eventId, token.provider(), token.content()))
                .concatWith(Mono.fromSupplier(() -> AiStreamEvent.done(eventId, route.selectedModel())))
                .onErrorResume(error -> Mono.just(AiStreamEvent.error(eventId, route.selectedModel(), error.getMessage())));
        return Flux.concat(Mono.just(AiStreamEvent.route(eventId, routeMapper.toDto(route))), tokens);
    }

    private RouteDecision route(AiPromptRequest prompt, ModelProvider preferredModel, boolean preciseMode) {
        RouteDecision route = modelRouter.route(new ModelRouteRequest(
                prompt.scenario(),
                prompt.joinedContent(),
                preferredModel,
                preciseMode
        ));
        //记录一次路由决策 —— 模型被选中时调用。
        metrics.routed(prompt.scenario(), route.selectedModel());
        return route;
    }
    //模型调用
    private Mono<AiModelResponse> invokeWithFallback(AiPromptRequest prompt, RouteDecision route) {
        return modelRegistry.require(route.selectedModel()).complete(prompt)
                .onErrorResume(ModelInvocationException.class, error -> {
                    metrics.failed(prompt.scenario(), route.selectedModel());
                    if (route.fallbackModel() == null) {
                        return Mono.error(error);
                    }
                    return modelRegistry.require(route.fallbackModel()).complete(prompt);
                });
    }

    /** 构造 prompt —— 将外贸询盘信息转为 AI 可理解的 prompt（系统提示 + 结构化的用户输入） */
    private AiPromptRequest prompt(TradeInquiryRequest request, ScenarioType scenario, String systemPrompt, String userContent) {
        return new AiPromptRequest(
                UUID.randomUUID().toString(),
                ensureConversationId(request.conversationId()),
                scenario,
                List.of(
                        new AiPromptMessage(MessageRole.SYSTEM, systemPrompt),
                        new AiPromptMessage(MessageRole.USER, userContent)
                ),
                request.metadata()
        );
    }

    /**
     * 解析商机分析 JSON 输出 —— 提取 leadScore、riskLevel、buyingIntent 等字段。
     * 解析失败时返回默认值（60 分、中等风险、需跟进），保证服务不中断。
     */
    private OpportunityAnalysisResponse parseAnalysis(
            AiPromptRequest prompt,
            TradeInquiryRequest request,
            RouteDecision route,
            AiModelResponse response
    ) {
        try {
            JsonNode root = objectMapper.readTree(extractJson(response.content()));
            // ⭐ 将 JSON 转换为表格格式化 HTML（类似推进任务的展示效果）
            String formattedSummary = formatAnalysisSummaryTable(root, request, response.provider(), route);
            return new OpportunityAnalysisResponse(
                    prompt.requestId(),
                    opportunityId(request),
                    clamp(root.path("leadScore").asInt(60), 0, 100),
                    text(root, "riskLevel", "MEDIUM"),
                    text(root, "buyingIntent", "NEEDS_FOLLOW_UP"),
                    formattedSummary,
                    strings(root.path("recommendedProducts")),
                    strings(root.path("missingInformation")),
                    strings(root.path("nextActions")),
                    text(root, "pricingAdvice", "结合目标价、MOQ、交期与运费重新核算报价。"),
                    response.provider(),
                    routeMapper.toDto(route),
                    Instant.now()
            );
        } catch (Exception ignored) {
            // ⭐ 异常时也返回格式化表格文本
            String fallbackSummary = formatFallbackSummaryTable(request, response.provider(), route);
            return new OpportunityAnalysisResponse(
                    prompt.requestId(),
                    opportunityId(request),
                    60,
                    "MEDIUM",
                    "NEEDS_FOLLOW_UP",
                    fallbackSummary,
                    List.of(request.productName()),
                    List.of("确认目标价格币种", "确认目的港和贸易条款", "确认是否需要样品"),
                    List.of("发送规格确认邮件", "准备阶梯报价", "核查付款与交期风险"),
                    "模型输出非严格 JSON，已按外贸销售默认规则生成建议。",
                    response.provider(),
                    routeMapper.toDto(route),
                    Instant.now()
            );
        }
    }

    /**
     * 将 AI 输出的 JSON 格式化为大气卡片式布局
     * 顶部：核心指标卡片
     * 中部：详细信息（标签 + 值，两列布局，确保完整显示）
     * 底部：评估摘要
     */
    private String formatAnalysisSummaryTable(JsonNode root, TradeInquiryRequest request, ModelProvider model, RouteDecision route) {
        int leadScore = root.path("leadScore").asInt(60);
        String riskLevel = root.path("riskLevel").asText("MEDIUM");
        String buyingIntent = root.path("buyingIntent").asText("NEEDS_FOLLOW_UP");
        String recommendedProducts = buildTagsList(root.path("recommendedProducts"), request.productName(), "product");
        String missingInformation = buildTagsList(root.path("missingInformation"), "确认目标价格币种、确认目的港和贸易条款、确认是否需要样品", "warn");
        String nextActions = buildTagsList(root.path("nextActions"), "发送规格确认邮件、准备阶梯报价、核查付款与交期风险", "action");
        String pricingAdvice = root.path("pricingAdvice").asText("结合目标价、MOQ、交期与运费重新核算报价");
        String aiSummary = root.path("summary").asText("AI 评估摘要不可用");
        String routeInfo = route != null && route.selectedModel() != null ? route.selectedModel().name() : "-";
        String createdAt = Instant.now().toString().replace("T", " ").substring(0, 19);

        StringBuilder sb = new StringBuilder();
        sb.append("<div class='summary-card'>");
        // 顶部：核心指标
//        sb.append("<div class='summary-header'>");
//        sb.append("<div class='metric'><span class='metric-label'>商机评分</span><span class='metric-value score'>" + leadScore + "</span></div>");
//        sb.append("<div class='metric'><span class='metric-label'>风险等级</span><span class='metric-value risk-" + riskLevel.toLowerCase() + "'>" + riskLevel + "</span></div>");
//        sb.append("<div class='metric'><span class='metric-label'>购买意图</span><span class='metric-value intent'>" + buyingIntent + "</span></div>");
//        sb.append("</div>");
        // 中部：详细信息
        sb.append("<div class='summary-body'>");
        sb.append("<div class='info-row'><span class='info-label'>推荐产品</span><span class='info-value'>" + recommendedProducts + "</span></div>");
        sb.append("<div class='info-row'><span class='info-label'>缺失信息</span><span class='info-value'>" + missingInformation + "</span></div>");
        sb.append("<div class='info-row'><span class='info-label'>下一步行动</span><span class='info-value'>" + nextActions + "</span></div>");
        sb.append("<div class='info-row'><span class='info-label'>报价建议</span><span class='info-value'>" + pricingAdvice + "</span></div>");
        sb.append("<div class='info-row'><span class='info-label'>AI 模型</span><span class='info-value'>" + formatModelDisplay(model.name(), routeInfo) + "</span></div>");
        sb.append("<div class='info-row'><span class='info-label'>创建时间</span><span class='info-value'>" + createdAt + "</span></div>");
        sb.append("</div>");
        // 底部：AI 评估摘要
        sb.append("<div class='summary-section'>");
        sb.append("<div class='section-title'>📋 评估摘要</div>");
        sb.append("<div class='section-content'>" + aiSummary + "</div>");
        sb.append("</div>");
        sb.append("</div>");
        return sb.toString();
    }

    /**
     * 异常时的默认格式化摘要
     */
    private String formatFallbackSummaryTable(TradeInquiryRequest request, ModelProvider model, RouteDecision route) {
        String routeInfo = route != null && route.selectedModel() != null ? route.selectedModel().name() : "-";
        String createdAt = Instant.now().toString().replace("T", " ").substring(0, 19);

        StringBuilder sb = new StringBuilder();
        sb.append("<div class='summary-card'>");
        sb.append("<div class='summary-header'>");
        sb.append("<div class='metric'><span class='metric-label'>商机评分</span><span class='metric-value score'>60</span></div>");
        sb.append("<div class='metric'><span class='metric-label'>风险等级</span><span class='metric-value risk-medium'>MEDIUM</span></div>");
        sb.append("<div class='metric'><span class='metric-label'>购买意图</span><span class='metric-value intent'>NEEDS_FOLLOW_UP</span></div>");
        sb.append("</div>");
        sb.append("<div class='summary-body'>");
        sb.append("<div class='info-row'><span class='info-label'>推荐产品</span><span class='info-value'><span class='info-tag product'>" + request.productName() + "</span></span></div>");
        sb.append("<div class='info-row'><span class='info-label'>缺失信息</span><span class='info-value'><span class='info-tag warn'>确认目标价格币种</span><span class='info-tag warn'>确认目的港和贸易条款</span><span class='info-tag warn'>确认是否需要样品</span></span></div>");
        sb.append("<div class='info-row'><span class='info-label'>下一步行动</span><span class='info-value'><span class='info-tag action'>发送规格确认邮件</span><span class='info-tag action'>准备阶梯报价</span><span class='info-tag action'>核查付款与交期风险</span></span></div>");
        sb.append("<div class='info-row'><span class='info-label'>报价建议</span><span class='info-value'>模型输出非严格 JSON，已按外贸销售默认规则生成建议</span></div>");
        sb.append("<div class='info-row'><span class='info-label'>AI 模型</span><span class='info-value'>" + formatModelDisplay(model.name(), routeInfo) + "</span></div>");
        sb.append("<div class='info-row'><span class='info-label'>创建时间</span><span class='info-value'>" + createdAt + "</span></div>");
        sb.append("</div>");
        sb.append("<div class='summary-section'>");
        sb.append("<div class='section-title'>📋 评估摘要</div>");
        sb.append("<div class='section-content'>模型输出异常，已使用默认评估结果</div>");
        sb.append("</div>");
        sb.append("</div>");
        return sb.toString();
    }

    /**
     * 构建逗号分隔的列表字符串
     */
    private String buildCommaList(JsonNode node, String defaultValue) {
        if (node.isArray() && !node.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(node.get(i).asText());
            }
            return sb.toString();
        }
        return defaultValue;
    }

    /**
     * 将 JSON 数组构建为 HTML 标签（span.info-tag）格式，展示更美观
     */
    private String buildTagsList(JsonNode node, String defaultValue, String tagClass) {
        if (node.isArray() && !node.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < node.size(); i++) {
                sb.append("<span class='info-tag ").append(tagClass).append("'>")
                  .append(escapeHtml(node.get(i).asText()))
                  .append("</span>");
            }
            return sb.toString();
        }
        return buildTagsFromString(defaultValue, tagClass);
    }

    /**
     * 将顿号分隔的字符串转换为 HTML 标签格式
     */
    private String buildTagsFromString(String text, String tagClass) {
        String[] items = text.split("[、,，]");
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                sb.append("<span class='info-tag ").append(tagClass).append("'>")
                  .append(escapeHtml(trimmed))
                  .append("</span>");
            }
        }
        return sb.toString();
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * 解析销售计划 JSON 输出 —— 提取 tasks、negotiationPoints、requiredDocuments 等字段。
     * 解析失败时返回默认销售模板（询盘澄清 + 首轮报价 + 英文跟进邮件），保证服务不中断。
     */
    private SalesPlanResponse parsePlan(
            AiPromptRequest prompt,
            TradeInquiryRequest request,
            RouteDecision route,
            AiModelResponse response
    ) {
        try {
            JsonNode root = objectMapper.readTree(extractJson(response.content()));
            return new SalesPlanResponse(
                    prompt.requestId(),
                    opportunityId(request),
                    text(root, "planSummary", response.content()),
                    tasks(root.path("tasks")),
                    strings(root.path("negotiationPoints")),
                    strings(root.path("requiredDocuments")),
                    strings(root.path("monitoringSignals")),
                    response.provider(),
                    routeMapper.toDto(route),
                    Instant.now()
            );
        } catch (Exception ignored) {
            return new SalesPlanResponse(
                    prompt.requestId(),
                    opportunityId(request),
                    response.content(),
                    List.of(new ProcessTaskDto(
                            1,
                            "完成客户询盘澄清与首轮报价",
                            "外贸销售",
                            TaskStatus.TODO,
                            List.of("确认规格、数量、目的港和目标价", "核算 FOB/CIF 报价", "发送英文跟进邮件"),
                            List.of("客户确认关键参数", "报价已通过销售经理复核")
                    )),
                    List.of("用阶梯价格换取更高 MOQ", "以样品费可抵扣订单款降低试单阻力"),
                    List.of("PI", "产品规格书", "装箱单模板", "交期承诺"),
                    List.of("客户回复时效", "报价有效期", "样品付款状态", "预计毛利率"),
                    response.provider(),
                    routeMapper.toDto(route),
                    Instant.now()
            );
        }
    }

    private List<ProcessTaskDto> tasks(JsonNode node) {
        List<ProcessTaskDto> tasks = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode task : node) {
                tasks.add(new ProcessTaskDto(
                        task.path("order").asInt(tasks.size() + 1),
                        text(task, "name", "未命名任务"),
                        text(task, "ownerRole", "外贸销售"),
                        status(text(task, "status", "TODO")),
                        strings(task.path("actions")),
                        strings(task.path("acceptanceCriteria"))
                ));
            }
        }
        return tasks;
    }

    private List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> values.add(item.asText()));
        }
        return values;
    }

    private TaskStatus status(String value) {
        try {
            return TaskStatus.valueOf(value);
        } catch (Exception ignored) {
            return TaskStatus.TODO;
        }
    }

    private String text(JsonNode node, String field, String fallback) {
        return node.hasNonNull(field) ? node.get(field).asText(fallback) : fallback;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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
            // ⭐ 尝试修复截断的 JSON（缺少闭合引号/括号）
            return repairTruncatedJson(json);
        }

        // 3. 兜底：如果内容本身不是 JSON，返回空 JSON 对象
        //    让 parseAnalysis/parsePlan 走 fallback 分支，返回默认值
        return "{}";
    }

    /**
     * 修复截断的 JSON —— 当 AI 模型输出因 token 限制被截断时，尝试补全闭合。
     *
     * 常见截断场景：
     * - 字符串值被截断：{"summary": "分析结果为 → 补全为 {"summary": "分析结果为"}
     * - 缺少闭合括号：{"a":1,"b":2 → 补全为 {"a":1,"b":2}
     * - 数组被截断：["a","b" → 补全为 ["a","b"]
     */
    private String repairTruncatedJson(String json) {
        // 快速检查：如果 JSON 已经完整（以 } 或 ] 结尾），直接返回
        String trimmed = json.trim();
        if (trimmed.endsWith("}") || trimmed.endsWith("]")) {
            // 进一步验证括号是否平衡
            if (isBracketsBalanced(trimmed)) {
                return trimmed;
            }
        }

        // 修复 1：移除尾部未完成的键值对（如 ,"key": 或 ,"key":"val）
        // 找到最后一个完整的键值对位置
        String repaired = trimIncompleteTrailingKey(json);

        // 修复 2：补全闭合引号
        repaired = repairQuotes(repaired);

        // 修复 3：补全闭合括号
        repaired = repairBrackets(repaired);

        return repaired;
    }

    /**
     * 移除 JSON 末尾不完整的键值对
     */
    private String trimIncompleteTrailingKey(String json) {
        // 找到最后一个 ',' 或 '{' 的位置，确保后面是完整的值
        // 策略：从末尾向前找 ',' 或 '{'，检查后面是否是完整的 "key":value 对
        for (int i = json.length() - 1; i >= 0; i--) {
            char c = json.charAt(i);
            if (c == ',') {
                // 检查逗号后面是否有完整的键值对
                String after = json.substring(i + 1).trim();
                if (after.isEmpty() || !after.startsWith("\"")) {
                    // 逗号后面没有完整的键，截断到这里
                    return json.substring(0, i);
                }
                // 检查是否有完整的 "key": value 结构
                if (isCompleteKeyValue(after)) {
                    return json;
                }
                // 不完整，截断
                return json.substring(0, i);
            }
        }
        return json;
    }

    /**
     * 检查字符串是否是完整的 "key": value 结构
     */
    private boolean isCompleteKeyValue(String s) {
        // 简化检查：以 " 开头，包含 :，且 value 是完整字符串/数字/对象/数组
        if (!s.startsWith("\"")) return false;
        int colonIdx = s.indexOf(':');
        if (colonIdx < 0) return false;
        String value = s.substring(colonIdx + 1).trim();
        if (value.isEmpty()) return false;
        // 字符串值应以 " 开头并以 " 结尾
        if (value.startsWith("\"")) {
            return value.endsWith("\"") && value.length() > 1;
        }
        // 数字/布尔/null 值
        return value.matches(".*[0-9true|false|null]\\s*$");
    }

    /**
     * 修复未闭合的引号
     */
    private String repairQuotes(String json) {
        int quoteCount = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                quoteCount++;
            }
        }
        // 如果引号未闭合，补全
        if (inString) {
            json = json + "\"";
        }
        return json;
    }

    /**
     * 修复未闭合的括号
     */
    private String repairBrackets(String json) {
        int braceCount = 0;  // { }
        int bracketCount = 0; // [ ]
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (c == '{') braceCount++;
                else if (c == '}') braceCount--;
                else if (c == '[') bracketCount++;
                else if (c == ']') bracketCount--;
            }
        }
        // 补全闭合括号
        StringBuilder sb = new StringBuilder(json);
        for (int i = 0; i < bracketCount; i++) sb.append(']');
        for (int i = 0; i < braceCount; i++) sb.append('}');
        return sb.toString();
    }

    /**
     * 检查括号是否平衡
     */
    private boolean isBracketsBalanced(String json) {
        int braceCount = 0;
        int bracketCount = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (c == '{') braceCount++;
                else if (c == '}') braceCount--;
                else if (c == '[') bracketCount++;
                else if (c == ']') bracketCount--;
            }
        }
        return braceCount == 0 && bracketCount == 0;
    }

    /**
     * 生成商机唯一 ID —— 基于"公司名-产品名"的组合。
     * 例如 "ABC Corp" + "LED Panel" → "ABCCORP-LEDPANEL"
     */
    private String opportunityId(TradeInquiryRequest request) {
        return (request.companyName() + "-" + request.productName()).replaceAll("\\s+", "-").toUpperCase();
    }

    /** 确保 conversationId 非空 */
    private String ensureConversationId(String conversationId) {
        return conversationId == null || conversationId.isBlank() ? UUID.randomUUID().toString() : conversationId;
    }

    /**
     * 将询盘信息格式化为结构化的用户输入文本 —— 供 AI 理解。
     * 包含：客户名称、公司、国家、产品、数量、目标价、贸易条款、目的港、原始询盘。
     * 未提供的字段显示为"未提供"。
     */
    private String inquiryContent(TradeInquiryRequest request) {
        return """
                客户名称：%s
                公司名称：%s
                国家/地区：%s
                询盘产品：%s
                数量：%s
                目标价：%s
                贸易条款：%s
                目的港：%s
                原始询盘：
                %s
                """.formatted(
                request.customerName(),
                request.companyName(),
                request.country(),
                request.productName(),
                request.quantity(),
                blank(request.targetPrice()),
                blank(request.incoterm()),
                blank(request.destinationPort()),
                request.message()
        );
    }

    /** 模型显示：相同只显示一个，不同说明发生了降级 */
    private String formatModelDisplay(String actual, String route) {
        if (route != null && !route.equals(actual)) {
            return actual + "（降级自 " + route + "）";
        }
        return actual;
    }

    /** 空值转默认显示 */
    private String blank(String value) {
        return value == null || value.isBlank() ? "未提供" : value;
    }

    /**
     * 商机分析 System Prompt —— 定义 AI 作为"外贸销售运营经理"的行为。
     * 要求 AI 输出 JSON，包含 leadScore(0-100)、riskLevel、buyingIntent 等字段。
     * 评分维度：数量、目标价、国家风险、信息完整度、采购意图、报价可行性。
     */
    private String analysisSystemPrompt() {
        return """
                你是资深外贸销售运营经理。请基于客户询盘做商机分析，只输出 JSON，不要 Markdown。
                JSON 结构：
                {
                  "leadScore": 0到100的整数,
                  "riskLevel": "LOW|MEDIUM|HIGH",
                  "buyingIntent": "HIGH_INTENT|PRICE_SHOPPING|NEEDS_FOLLOW_UP|LOW_FIT",
                  "summary": "商机摘要",
                  "recommendedProducts": ["建议主推产品或规格"],
                  "missingInformation": ["必须补充的信息"],
                  "nextActions": ["下一步销售动作"],
                  "pricingAdvice": "报价建议"
                }
                评分要结合数量、目标价、国家风险、信息完整度、采购意图和报价可行性。
                """;
    }

    /**
     * 销售计划 System Prompt —— 定义 AI 将询盘转化为可执行销售推进计划的行为。
     * 要求 AI 输出 JSON，包含 tasks、negotiationPoints、requiredDocuments、monitoringSignals。
     * 计划需覆盖：询盘澄清 → 报价 → 样品 → PI → 付款 → 生产交期 → 物流风险。
     */
    private String planSystemPrompt() {
        return """
                你是外贸销售流程负责人。请把询盘转化为可执行销售推进计划，只输出 JSON，不要 Markdown。
                JSON 结构：
                {
                  "planSummary": "推进计划摘要",
                  "tasks": [
                    {
                      "order": 1,
                      "name": "任务名称",
                      "ownerRole": "负责人角色",
                      "status": "TODO",
                      "actions": ["动作"],
                      "acceptanceCriteria": ["验收标准"]
                    }
                  ],
                  "negotiationPoints": ["谈判要点"],
                  "requiredDocuments": ["需要准备的单证或资料"],
                  "monitoringSignals": ["需要持续跟踪的信号"]
                }
                status 只能取 TODO、RUNNING、BLOCKED、DONE。计划要覆盖询盘澄清、报价、样品、PI、付款、生产交期和物流风险。
                """;
    }

    /**
     * 客户回复邮件 System Prompt —— 定义 AI 作为"专业外贸销售"生成英文回复邮件的行为。
     * 要求：语气专业、明确下一步、主动索要缺失参数、给出报价前置条件、不虚构最终价格。
     */
    private String replySystemPrompt() {
        return """
                你是专业外贸销售。请根据询盘生成一封可以直接发送给客户的英文回复邮件。
                要求：语气专业、明确下一步、主动索要缺失参数、给出报价前置条件，不虚构最终价格。
                只输出邮件正文。
                """;
    }
}
