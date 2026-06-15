package com.trade.trade.dto;

import com.trade.enums.ModelProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 外贸询盘请求 DTO —— 客户端提交的外贸销售辅助请求。
 * 包含完整的客户信息和询盘内容，用于商机分析、销售计划和回复邮件生成。
 */
public record TradeInquiryRequest(
        /**
         * 会话 ID —— 标识对话上下文，为空时自动生成 UUID。
         */
        String conversationId,

        /**
         * 客户名称 —— 询盘中客户的姓名或联系人，必填。
         */
        @NotBlank String customerName,

        /**
         * 公司名称 —— 客户所在公司，必填。
         * 与 productName 组合生成商机唯一 ID（opportunityId）。
         */
        @NotBlank String companyName,

        /**
         * 国家/地区 —— 客户所在国家，必填。
         * 影响风险评估和报价策略（某些国家风险较高）。
         */
        @NotBlank String country,

        /**
         * 询盘产品 —— 客户感兴趣的产品名称，必填。
         */
        @NotBlank String productName,

        /**
         * 采购数量 —— 客户要求的采购数量，必填。
         * 数量大小直接影响商机评分（MOQ 是否满足）。
         */
        @NotBlank String quantity,

        /**
         * 目标价格 —— 客户期望的单价，可选。
         * 未提供时显示"未提供"，AI 会在 missingInformation 中标注。
         */
        String targetPrice,

        /**
         * 贸易条款 —— 如 FOB、CIF、EXW 等，可选。
         * 影响报价构成（是否包含运费、保险费等）。
         */
        String incoterm,

        /**
         * 目的港 —— 货物需要送达的港口，可选。
         * 用于计算海运费和物流风险。
         */
        String destinationPort,

        /**
         * 原始询盘内容 —— 客户发来的原始邮件或消息，必填。
         * 最大 20000 字符，AI 基于此做深度分析。
         */
        @NotBlank @Size(max = 20000) String message,

        /**
         * 首选模型 —— 指定希望用哪个 AI 模型分析。
         * 可选，商机分析场景默认开启精确模式（优先 DeepSeek）。
         */
        ModelProvider preferredModel,

        /**
         * 扩展元数据 —— 键值对附加信息，透传到内部请求。
         */
        Map<String, Object> metadata
) {
}
