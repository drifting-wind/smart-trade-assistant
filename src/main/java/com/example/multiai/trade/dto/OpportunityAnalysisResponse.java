package com.example.multiai.trade.dto;

import com.example.multiai.dto.RouteDecisionDto;
import com.example.multiai.enums.ModelProvider;

import java.time.Instant;
import java.util.List;

/**
 * 商机分析响应 DTO —— AI 对外贸询盘的质量评估结果。
 * 由 TradeSalesService.parseAnalysis() 从 AI 的 JSON 输出解析而来。
 */
public record OpportunityAnalysisResponse(
        /**
         * 请求 ID —— 本次请求唯一标识（UUID）。
         */
        String id,

        /**
         * 商机唯一 ID —— 由 "公司名-产品名" 生成（如 ABCCORP-LEDPANEL）。
         * 用于在业务系统中唯一标识这个商机。
         */
        String opportunityId,

        /**
         * 商机评分 —— 0 到 100 的整数分数。
         * AI 基于数量、目标价、国家风险、信息完整度、采购意图综合打分。
         * 解析失败时默认 60 分。
         */
        int leadScore,

        /**
         * 风险等级 —— LOW（低风险）、MEDIUM（中等）、HIGH（高风险）。
         * 解析失败时默认 MEDIUM。
         */
        String riskLevel,

        /**
         * 购买意图 —— HIGH_INTENT（高意向）、PRICE_SHOPPING（比价中）、
         * NEEDS_FOLLOW_UP（需跟进）、LOW_FIT（匹配度低）。
         * 解析失败时默认 NEEDS_FOLLOW_UP。
         */
        String buyingIntent,

        /**
         * 商机摘要 —— AI 对本次询盘的分析总结。
         */
        String summary,

        /**
         * 推荐产品列表 —— AI 建议主推给该客户的产品或规格。
         */
        List<String> recommendedProducts,

        /**
         * 缺失信息列表 —— AI 认为还需要向客户确认的关键信息。
         * 例如 ["确认目标价格币种", "确认目的港和贸易条款"]。
         */
        List<String> missingInformation,

        /**
         * 下一步行动列表 —— 销售团队应该执行的动作。
         * 例如 ["发送规格确认邮件", "准备阶梯报价", "核查付款与交期风险"]。
         */
        List<String> nextActions,

        /**
         * 报价建议 —— AI 对如何重新核算报价的具体建议。
         */
        String pricingAdvice,

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
