package com.example.trade.trade.service;

import com.example.trade.dto.AiStreamEvent;
import com.example.trade.dto.ProcessTaskDto;
import com.example.trade.enums.MessageRole;
import com.example.trade.enums.ModelProvider;
import com.example.trade.enums.ScenarioType;
import com.example.trade.enums.TaskStatus;
import com.example.trade.exception.ModelInvocationException;
import com.example.trade.model.AiModelResponse;
import com.example.trade.model.AiPromptMessage;
import com.example.trade.model.AiPromptRequest;
import com.example.trade.model.ModelRegistry;
import com.example.trade.monitoring.AiMetrics;
import com.example.trade.router.ModelRouteRequest;
import com.example.trade.router.ModelRouter;
import com.example.trade.router.RouteDecision;
import com.example.trade.router.RouteMapper;
import com.example.trade.trade.dto.OpportunityAnalysisResponse;
import com.example.trade.trade.dto.SalesPlanResponse;
import com.example.trade.trade.dto.TradeInquiryRequest;
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
            return new OpportunityAnalysisResponse(
                    prompt.requestId(),
                    opportunityId(request),
                    clamp(root.path("leadScore").asInt(60), 0, 100),
                    text(root, "riskLevel", "MEDIUM"),
                    text(root, "buyingIntent", "NEEDS_FOLLOW_UP"),
                    text(root, "summary", response.content()),
                    strings(root.path("recommendedProducts")),
                    strings(root.path("missingInformation")),
                    strings(root.path("nextActions")),
                    text(root, "pricingAdvice", "结合目标价、MOQ、交期与运费重新核算报价。"),
                    response.provider(),
                    routeMapper.toDto(route),
                    Instant.now()
            );
        } catch (Exception ignored) {
            return new OpportunityAnalysisResponse(
                    prompt.requestId(),
                    opportunityId(request),
                    60,
                    "MEDIUM",
                    "NEEDS_FOLLOW_UP",
                    response.content(),
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

    private String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return content;
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
