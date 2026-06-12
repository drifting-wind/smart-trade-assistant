package com.example.trade.monitoring;

import com.example.trade.enums.ModelProvider;
import com.example.trade.enums.ScenarioType;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * AI 指标埋点 —— 通过 Micrometer 统计路由和调用的关键指标。
 *
 * 技术栈：
 * - Micrometer：Spring Boot 标准的指标采集框架
 * - Prometheus：配合 micrometer-registry-prometheus 依赖，暴露 /actuator/prometheus 端点
 * - Grafana：可以拉取 Prometheus 数据，构建监控面板
 *
 * 作用：
 * 每次路由决策和模型调用失败时埋点，运维可以通过 Grafana 面板查看：
 * - 各模型被调用了多少次（ai_route_total）
 * - QA 和 FLOW 场景各占多少比例
 * - 哪个模型失败率最高（ai_model_failed_total）
 */
@Component
public class AiMetrics {

    /**
     * Micrometer 指标注册表 —— Spring Boot 自动注入，
     * 所有指标数据通过这个入口写入到 Prometheus 等监控系统。
     */
    private final MeterRegistry meterRegistry;

    public AiMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 记录一次路由决策 —— 模型被选中时调用。
     *
     * 埋点位置：
     * - ChatOrchestrationService.route() —— 问答场景路由后
     * - ProcessAssistantService.route() —— 流程场景路由后
     * - TradeSalesService.route() —— 外贸场景路由后
     *
     * 生成的指标示例（Prometheus 格式）：
     * ai_route_total{scenario="QA",model="DEEPSEEK"} 1523
     * ai_route_total{scenario="FLOW",model="ALIBABA_BAILIAN"} 847
     *
     * 可以通过 Grafana 面板查询：
     * - 按场景过滤：sum(rate(ai_route_total[5m])) by (scenario)
     * - 按模型过滤：sum(rate(ai_route_total[5m])) by (model)
     */
    public void routed(ScenarioType scenario, ModelProvider provider) {
        meterRegistry.counter("ai.route.total", "scenario", scenario.name(), "model", provider.name()).increment();
    }

    /**
     * 记录一次模型调用失败 —— 降级逻辑触发时调用。
     *
     * 埋点位置：
     * - ChatOrchestrationService.invokeWithFallback() / streamWithFallback() —— 首选模型失败时
     * - ProcessAssistantService.invokeWithFallback() / stream() —— 流程调用失败时
     * - TradeSalesService.invokeWithFallback() / streamCustomerReply() —— 外贸调用失败时
     *
     * 生成的指标示例：
     * ai_model_failed_total{scenario="QA",model="DEEPSEEK"} 3
     * ai_model_failed_total{scenario="FLOW",model="ALIBABA_BAILIAN"} 1
     *
     * 告警用途：可以配置 Prometheus 告警规则，
     * 例如"5 分钟内 DeepSeek 失败超过 10 次"时触发告警。
     */
    public void failed(ScenarioType scenario, ModelProvider provider) {
        meterRegistry.counter("ai.model.failed.total", "scenario", scenario.name(), "model", provider.name()).increment();
    }

    /**
     * 记录一次文档摄入 —— 文档成功写入 Milvus 时调用。
     *
     * 埋点位置：DocumentIngestionService.ingest*()
     *
     * 生成的指标示例：
     * ai.rag.document_ingested_total{type="pdf"} 15
     * ai.rag.document_ingested_total{type="docx"} 8
     *
     * 用途：统计知识库文档增长趋势，按类型分析摄入量。
     */
    public void documentIngested(String contentType) {
        meterRegistry.counter("ai.rag.document.ingested.total", "type", contentType).increment();
    }

    /**
     * 记录一次向量检索 —— Milvus 搜索完成时调用。
     *
     * 埋点位置：MilvusVectorStoreClient.search()
     *
     * 生成的指标示例：
     * ai.rag.search_total 234
     *
     * 配合 Micrometer 的 Timer 可统计 P99 延迟。
     */
    public void searchPerformed() {
        meterRegistry.counter("ai.rag.search.total").increment();
    }

    /**
     * 记录一次 RAG 问答 —— 检索增强对话完成时调用。
     *
     * 埋点位置：RagOrchestrationService.completeWithRetrieval() / streamWithRetrieval()
     *
     * 生成的指标示例：
     * ai.rag.chat_total{model="DEEPSEEK"} 56
     * ai.rag.chat_total{model="ALIBABA_BAILIAN"} 23
     *
     * 用途：统计 RAG 功能使用量，按模型分析负载。
     */
    public void ragChat(ScenarioType scenario, ModelProvider provider) {
        meterRegistry.counter("ai.rag.chat.total", "scenario", scenario.name(), "model", provider.name()).increment();
    }
}
