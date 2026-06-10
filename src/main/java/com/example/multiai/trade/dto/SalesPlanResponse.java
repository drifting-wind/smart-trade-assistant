package com.example.multiai.trade.dto;

import com.example.multiai.dto.ProcessTaskDto;
import com.example.multiai.dto.RouteDecisionDto;
import com.example.multiai.enums.ModelProvider;

import java.time.Instant;
import java.util.List;

/**
 * 销售推进计划响应 DTO —— AI 将询盘转化为可执行的销售计划。
 * 由 TradeSalesService.parsePlan() 从 AI 的 JSON 输出解析而来。
 */
public record SalesPlanResponse(
        /**
         * 请求 ID —— 本次请求唯一标识（UUID）。
         */
        String id,

        /**
         * 商机唯一 ID —— 由 "公司名-产品名" 生成，用于关联商机分析结果。
         */
        String opportunityId,

        /**
         * 计划摘要 —— AI 对本次销售推进计划的简要描述。
         */
        String planSummary,

        /**
         * 任务列表 —— 按执行顺序排列的销售任务（询盘澄清 → 报价 → 样品 → PI → 付款 → 物流）。
         * 每个任务包含 order、name、ownerRole、status、actions、acceptanceCriteria。
         */
        List<ProcessTaskDto> tasks,

        /**
         * 谈判要点列表 —— AI 建议的关键谈判策略。
         * 例如 ["用阶梯价格换取更高 MOQ", "以样品费可抵扣订单款降低试单阻力"]。
         */
        List<String> negotiationPoints,

        /**
         * 所需单证列表 —— 完成该订单需要准备的文件和资料。
         * 例如 ["PI", "产品规格书", "装箱单模板", "交期承诺"]。
         */
        List<String> requiredDocuments,

        /**
         * 监控信号列表 —— 需要持续跟踪的指标。
         * 例如 ["客户回复时效", "报价有效期", "样品付款状态", "预计毛利率"]。
         */
        List<String> monitoringSignals,

        /**
         * 实际使用的 AI 模型。
         */
        ModelProvider model,

        /**
         * 路由决策信息。
         */
        RouteDecisionDto route,

        /**
         * 响应创建时间 —— UTC 时间戳。
         */
        Instant createdAt
) {
}
