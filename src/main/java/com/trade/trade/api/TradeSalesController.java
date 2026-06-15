package com.trade.trade.api;

import com.trade.dto.AiStreamEvent;
import com.trade.trade.dto.OpportunityAnalysisResponse;
import com.trade.trade.dto.SalesPlanResponse;
import com.trade.trade.dto.TradeInquiryRequest;
import com.trade.trade.service.TradeSalesService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 外贸销售 REST 接口 —— /api/v1/trade
 *
 * 业务场景：外贸业务员收到海外客户询盘后，用 AI 辅助完成三件事：
 * 1. 商机分析 —— 评估询盘质量（打分、风险等级、购买意图、推荐产品、缺失信息）
 * 2. 销售计划 —— 将询盘转化为可执行的销售推进计划（任务拆解、谈判要点、单证准备）
 * 3. 回复邮件 —— 生成一封可以直接发给客户的英文跟进邮件
 *
 * 技术栈：
 * - Spring WebFlux 响应式框架（非阻塞，高并发）
 * - SSE (Server-Sent Events) 流式推送（回复邮件逐字输出）
 * - JSR-303 Bean Validation 参数校验（@Valid + @NotBlank）
 * - Jackson JSON 解析（将 AI 输出转为结构化商机分析/销售计划）
 *
 * 端点说明：
 * - POST /opportunities/analyze：同步商机分析，返回 OpportunityAnalysisResponse
 * - POST /opportunities/sales-plan：同步销售计划，返回 SalesPlanResponse
 * - POST /opportunities/reply/stream：流式回复邮件，SSE 推送英文邮件正文
 */
@Validated
@RestController
@RequestMapping("/api/v1/trade")
public class TradeSalesController {

    /**
     * 外贸销售服务 —— 处理三个业务：
     * 1. analyze()：商机分析（QA 场景 + 精确模式，优先 DeepSeek 推理）
     * 2. salesPlan()：销售计划（FLOW 场景，优先阿里百炼规划）
     * 3. streamCustomerReply()：流式生成客户回复邮件（QA 场景 + 精确模式）
     *
     * 与通用 ChatOrchestrationService 的区别：
     * - 不依赖会话历史（外贸询盘一般是单轮，不需要多轮记忆）
     * - System Prompt 是外贸领域专用的（分析模板、规划模板、邮件模板）
     * - JSON 解析针对外贸字段定制（leadScore、riskLevel、pricingAdvice 等）
     */
    private final TradeSalesService tradeSalesService;

    public TradeSalesController(TradeSalesService tradeSalesService) {
        this.tradeSalesService = tradeSalesService;
    }

    /**
     * 商机分析 —— AI 评估外贸询盘质量，返回结构化评分结果。
     *
     * 业务流程：
     * 客户端提交 TradeInquiryRequest（客户、公司、国家、产品、数量、目标价等）
     * → 格式化为结构化 prompt（客户名称、公司、国家、产品、数量、贸易条款、目的港、原始询盘）
     * → System Prompt 要求 AI 以"外贸销售运营经理"身份输出 JSON
     * → 智能路由：QA 场景 + 精确模式，优先 DeepSeek（推理分析能力强）
     * → 调用 AI 模型，等待完整 JSON 返回
     * → 解析 JSON：提取 leadScore(0-100)、riskLevel、buyingIntent、recommendedProducts 等
     * → 返回 OpportunityAnalysisResponse
     *
     * 请求示例：
     * POST /api/v1/trade/opportunities/analyze
     * {
     *   "customerName": "John Smith",
     *   "companyName": "ABC Lighting Inc",
     *   "country": "US",
     *   "productName": "LED Panel 60x60",
     *   "quantity": "5000",
     *   "targetPrice": "$12.50/pc",
     *   "incoterm": "CIF",
     *   "destinationPort": "Los Angeles",
     *   "message": "Hi, we are interested in your LED panels. Please send your best price for 5000pcs..."
     * }
     *
     * 响应示例：
     * {
     *   "opportunityId": "ABC-LIGHTING-INC-LED-PANEL-60X60",
     *   "leadScore": 78,
     *   "riskLevel": "MEDIUM",
     *   "buyingIntent": "HIGH_INTENT",
     *   "summary": "客户有明确采购数量和目的港，意向较高...",
     *   "recommendedProducts": ["LED Panel 60x60 40W"],
     *   "missingInformation": ["确认付款条件", "是否需要 UL 认证"],
     *   "nextActions": ["发送阶梯报价表", "确认 UL 认证要求", "寄送样品"],
     *   "pricingAdvice": "结合目标价、MOQ、CIF 运费重新核算报价..."
     * }
     *
     * 关键技术点：
     * - Mono<OpportunityAnalysisResponse>：响应式单次返回
     * - @Valid 校验：customerName、companyName、country 等字段标记 @NotBlank，为空返回 400
     * - JSON 解析失败时返回默认值（60 分、中等风险、需跟进），保证服务不中断
     */
    @PostMapping("/opportunities/analyze")
    public Mono<OpportunityAnalysisResponse> analyze(@Valid @RequestBody TradeInquiryRequest request) {
        return tradeSalesService.analyze(request);
    }

    /**
     * 销售推进计划 —— AI 将询盘转化为可执行的销售流程。
     *
     * 业务流程：
     * 客户端提交 TradeInquiryRequest（同商机分析的请求参数）
     * → System Prompt 要求 AI 以"外贸销售流程负责人"身份输出 JSON
     * → 智能路由：FLOW 场景，优先阿里百炼（长上下文和规划能力强）
     * → 调用 AI 模型，等待完整 JSON 返回
     * → 解析 JSON：提取 tasks[]、negotiationPoints[]、requiredDocuments[]、monitoringSignals[]
     * → 返回 SalesPlanResponse
     *
     * 请求示例：（同 analyze 接口，复用 TradeInquiryRequest）
     * POST /api/v1/trade/opportunities/sales-plan
     * {
     *   "customerName": "John Smith",
     *   "companyName": "ABC Lighting Inc",
     *   "country": "US",
     *   "productName": "LED Panel 60x60",
     *   "quantity": "5000",
     *   "targetPrice": "$12.50/pc",
     *   "incoterm": "CIF",
     *   "destinationPort": "Los Angeles",
     *   "message": "Hi, we are interested in your LED panels..."
     * }
     *
     * 响应示例：
     * {
     *   "opportunityId": "ABC-LIGHTING-INC-LED-PANEL-60X60",
     *   "planSummary": "从询盘澄清到出货交付的完整销售推进计划",
     *   "tasks": [
     *     {"order": 1, "name": "规格确认与首轮报价", "ownerRole": "外贸销售", "status": "TODO"},
     *     {"order": 2, "name": "样品寄送与确认", "ownerRole": "外贸销售", "status": "TODO"},
     *     {"order": 3, "name": "PI 确认与付款", "ownerRole": "财务", "status": "TODO"}
     *   ],
     *   "negotiationPoints": ["用阶梯价格换取更高 MOQ", "样品费可抵扣订单款"],
     *   "requiredDocuments": ["PI", "产品规格书", "装箱单模板", "交期承诺"],
     *   "monitoringSignals": ["客户回复时效", "报价有效期", "样品付款状态"]
     * }
     *
     * 关键技术点：
     * - 与 analyze() 的区别：场景类型为 FLOW（影响路由打分），输出 JSON 结构不同
     * - ProcessTaskDto 嵌套 DTO：每个任务包含 order、name、ownerRole、status、actions、acceptanceCriteria
     * - JSON 解析失败时返回默认销售模板，保证服务不中断
     */
    @PostMapping("/opportunities/sales-plan")
    public Mono<SalesPlanResponse> salesPlan(@Valid @RequestBody TradeInquiryRequest request) {
        return tradeSalesService.salesPlan(request);
    }

    /**
     * 流式生成客户回复邮件 —— SSE 实时推送 AI 生成的英文邮件正文。
     *
     * 业务流程：
     * 客户端提交 TradeInquiryRequest（同上述请求参数）
     * → System Prompt 要求 AI 以"专业外贸销售"身份生成英文回复邮件
     * → 智能路由：QA 场景 + 精确模式，优先 DeepSeek
     * → 流式调用模型：AI 逐字输出邮件内容
     * → 事件流：route → token × N → done/error
     *
     * 请求示例：
     * POST /api/v1/trade/opportunities/reply/stream
     * {
     *   "customerName": "John Smith",
     *   "companyName": "ABC Lighting Inc",
     *   "country": "US",
     *   "productName": "LED Panel 60x60",
     *   "quantity": "5000",
     *   "targetPrice": "$12.50/pc",
     *   "incoterm": "CIF",
     *   "destinationPort": "Los Angeles",
     *   "message": "Hi, we are interested in your LED panels..."
     * }
     *
     * SSE 响应示例：
     *
     * id: uuid-1
     * event: route
     * data: {"scenario":"QA","selectedModel":"DEEPSEEK","reason":"精确模式..."}
     *
     * id: uuid-1
     * event: token
     * data: {"content":"Dear John,\n\nThank you for your inquiry regarding our LED"}
     *
     * id: uuid-1
     * event: token
     * data: {"content":" panels 60x60. Based on your requirement of 5000pcs..."}
     *
     * id: uuid-1
     * event: done
     * data: {"model":"DEEPSEEK"}
     *
     * 关键技术点：
     * - produces = TEXT_EVENT_STREAM_VALUE：声明 SSE 协议
     * - Flux<ServerSentEvent<AiStreamEvent>>：Flux 发射多个事件实现持续推送
     * - ServerSentEvent.builder()：自动序列化事件数据为 JSON
     * - onErrorResume：流式降级，主模型失败切换 fallback，仍无法恢复则发送 error 事件
     * - 邮件生成要求：语气专业、明确下一步、主动索要缺失参数、不虚构最终价格
     */
    @PostMapping(value = "/opportunities/reply/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AiStreamEvent>> streamReply(@Valid @RequestBody TradeInquiryRequest request) {
        return tradeSalesService.streamCustomerReply(request)
                // 将内部 AiStreamEvent 包装为 HTTP SSE 格式推送给客户端
                .map(event -> ServerSentEvent.<AiStreamEvent>builder()
                        .id(event.id())                           // SSE 事件 ID，用于断线重连定位
                        .event(event.type().name().toLowerCase()) // 事件类型：route/token/done/error
                        .data(event)                              // JSON 序列化后的事件数据
                        .build());
    }
}
