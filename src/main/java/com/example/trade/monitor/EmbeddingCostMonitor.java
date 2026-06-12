package com.example.trade.monitor;

import com.example.trade.config.AiGatewayProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Embedding 成本监控器 —— 跟踪每次 Embedding 调用的 token 消耗和成本。
 *
 * 生产环境功能：
 * 1. 实时记录 token 消耗和成本
 * 2. 月度预算控制，超阈值自动告警
 * 3. Prometheus 指标暴露
 * 4. 定期生成成本报告
 *
 * 监控指标（Prometheus）：
 * - embedding.cost.total: 累计成本（元）
 * - embedding.cost.monthly: 当月成本（元）
 * - embedding.tokens.total: 累计 token 数
 * - embedding.tokens.monthly: 当月 token 数
 * - embedding.calls.total: 累计调用次数
 * - embedding.calls.monthly: 当月调用次数
 * - embedding.budget.usage: 预算使用率（百分比）
 *
 * 使用方式：
 * 1. 注入 EmbeddingCostMonitor
 * 2. 调用 recordUsage(model, tokenCount) 记录每次调用
 * 3. 通过 /actuator/metrics/embedding.* 查看指标
 */
@Component
public class EmbeddingCostMonitor {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingCostMonitor.class);
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    private final MeterRegistry meterRegistry;
    private final AiGatewayProperties properties;

    // 月度统计（线程安全）
    private final AtomicLong monthlyTokenCount = new AtomicLong(0);
    private final AtomicLong monthlyCallCount = new AtomicLong(0);
    private final AtomicLong monthlyCostMicros = new AtomicLong(0); // 微元，避免浮点精度问题

    // 总累计统计
    private final AtomicLong totalTokenCount = new AtomicLong(0);
    private final AtomicLong totalCallCount = new AtomicLong(0);
    private final AtomicLong totalCostMicros = new AtomicLong(0);

    // 当前月份（用于检测月份变化）
    private volatile String currentMonth;

    // 预算告警状态（避免重复告警）
    private volatile boolean budgetAlertSent = false;

    public EmbeddingCostMonitor(MeterRegistry meterRegistry, AiGatewayProperties properties) {
        this.meterRegistry = meterRegistry;
        this.properties = properties;
        this.currentMonth = YearMonth.now().format(MONTH_FORMATTER);

        // 注册 Prometheus 指标
        registerMetrics();

        log.info("✅ Embedding 成本监控器已启用");
    }

    /**
     * 注册 Prometheus 指标
     */
    private void registerMetrics() {
        // 累计指标
        meterRegistry.gauge("embedding.tokens.total", totalTokenCount, AtomicLong::get);
        meterRegistry.gauge("embedding.calls.total", totalCallCount, AtomicLong::get);
        meterRegistry.gauge("embedding.cost.total", totalCostMicros, v -> v.get() / 1_000_000.0);

        // 月度指标
        meterRegistry.gauge("embedding.tokens.monthly", monthlyTokenCount, AtomicLong::get);
        meterRegistry.gauge("embedding.calls.monthly", monthlyCallCount, AtomicLong::get);
        meterRegistry.gauge("embedding.cost.monthly", monthlyCostMicros, v -> v.get() / 1_000_000.0);

        // 预算使用率
        meterRegistry.gauge("embedding.budget.usage", this, EmbeddingCostMonitor::getBudgetUsagePercent);

        // 预算告警计数
        Counter.builder("embedding.budget.alerts")
                .description("预算告警次数")
                .register(meterRegistry);
    }

    /**
     * 记录一次 Embedding 调用的 token 消耗。
     *
     * @param model     模型名称（如 text-embedding-v3）
     * @param tokenCount 本次调用的 token 数
     * @param durationMs 调用耗时（毫秒）
     */
    public void recordUsage(String model, int tokenCount, long durationMs) {
        if (!properties.getRag().getCostMonitor().isEnabled()) {
            return;
        }

        // 计算成本（微元）
        double pricePerToken = properties.getRag().getEmbedding().getPricePerMillionTokens() / 1_000_000.0;
        long costMicros = (long) (tokenCount * pricePerToken * 1_000_000);

        // 更新统计
        monthlyTokenCount.addAndGet(tokenCount);
        monthlyCallCount.incrementAndGet();
        monthlyCostMicros.addAndGet(costMicros);

        totalTokenCount.addAndGet(tokenCount);
        totalCallCount.incrementAndGet();
        totalCostMicros.addAndGet(costMicros);

        // 记录详细日志（如果启用）
        if (properties.getRag().getCostMonitor().isLogDetailedUsage()) {
            log.info("💰 Embedding 调用: model={}, tokens={}, cost={}元, duration={}ms",
                    model, tokenCount, costMicros / 1_000_000.0, durationMs);
        }

        // 检查预算
        checkBudget();
    }

    /**
     * 检查预算使用率，超过阈值时触发告警。
     */
    private void checkBudget() {
        double budget = properties.getRag().getCostMonitor().getMonthlyBudget();
        if (budget <= 0) {
            return; // 未配置预算
        }

        double usagePercent = getBudgetUsagePercent();
        int alertThreshold = properties.getRag().getCostMonitor().getBudgetAlertThreshold();

        if (usagePercent >= alertThreshold && !budgetAlertSent) {
            // 触发告警
            budgetAlertSent = true;
            meterRegistry.counter("embedding.budget.alerts").increment();

            log.warn("⚠️ Embedding 预算告警: 当月使用率 {}% 超过阈值 {}%, 当月成本 {}元 / 预算 {}元",
                    String.format("%.1f", usagePercent),
                    alertThreshold,
                    String.format("%.2f", monthlyCostMicros.get() / 1_000_000.0),
                    budget);
        }

        // 预算使用率超过 100% 时严重告警
        if (usagePercent >= 100) {
            log.error("🚨 Embedding 预算超支: 当月使用率 {}%, 当月成本 {}元 / 预算 {}元",
                    String.format("%.1f", usagePercent),
                    String.format("%.2f", monthlyCostMicros.get() / 1_000_000.0),
                    budget);
        }
    }

    /**
     * 获取预算使用率（百分比）
     */
    private double getBudgetUsagePercent() {
        double budget = properties.getRag().getCostMonitor().getMonthlyBudget();
        if (budget <= 0) {
            return 0.0;
        }
        return (monthlyCostMicros.get() / 1_000_000.0) / budget * 100;
    }

    /**
     * 月度重置 —— 每月 1 日 00:00 重置月度统计。
     */
    @Scheduled(cron = "0 0 0 1 * ?") // 每月 1 日 00:00
    public void resetMonthlyStats() {
        String newMonth = YearMonth.now().format(MONTH_FORMATTER);

        // 生成上月成本报告
        if (currentMonth != null) {
            generateMonthlyReport(currentMonth);
        }

        // 重置月度统计
        monthlyTokenCount.set(0);
        monthlyCallCount.set(0);
        monthlyCostMicros.set(0);
        budgetAlertSent = false;
        currentMonth = newMonth;

        log.info("📊 Embedding 月度统计已重置: {}", newMonth);
    }

    /**
     * 生成月度成本报告
     */
    private void generateMonthlyReport(String month) {
        double monthlyCost = monthlyCostMicros.get() / 1_000_000.0;
        long tokens = monthlyTokenCount.get();
        long calls = monthlyCallCount.get();
        double budget = properties.getRag().getCostMonitor().getMonthlyBudget();
        double usagePercent = budget > 0 ? (monthlyCost / budget * 100) : 0;

        log.info("📊 Embedding 月度成本报告 [{}]:\n" +
                        "  - 调用次数: {}\n" +
                        "  - Token 总数: {}\n" +
                        "  - 总成本: {}元\n" +
                        "  - 月度预算: {}元\n" +
                        "  - 预算使用率: {}%\n" +
                        "  - 平均每次调用 token: {}\n" +
                        "  - 平均每次调用成本: {}元",
                month, calls, tokens,
                String.format("%.2f", monthlyCost),
                String.format("%.2f", budget),
                String.format("%.1f", usagePercent),
                calls > 0 ? tokens / calls : 0,
                calls > 0 ? String.format("%.4f", monthlyCost / calls) : "0.0000");
    }

    /**
     * 获取当前成本统计（用于 API 查询）
     */
    public Map<String, Object> getStats() {
        double monthlyCost = monthlyCostMicros.get() / 1_000_000.0;
        double totalCost = totalCostMicros.get() / 1_000_000.0;
        double budget = properties.getRag().getCostMonitor().getMonthlyBudget();

        return Map.of(
                "currentMonth", currentMonth,
                "monthly", Map.of(
                        "tokens", monthlyTokenCount.get(),
                        "calls", monthlyCallCount.get(),
                        "cost", monthlyCost,
                        "budget", budget,
                        "usagePercent", budget > 0 ? (monthlyCost / budget * 100) : 0
                ),
                "total", Map.of(
                        "tokens", totalTokenCount.get(),
                        "calls", totalCallCount.get(),
                        "cost", totalCost
                ),
                "budgetAlertSent", budgetAlertSent
        );
    }
}
