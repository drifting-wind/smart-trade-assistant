package com.trade.router;

import com.trade.config.AiGatewayProperties;
import com.trade.enums.ModelProvider;
import com.trade.enums.ScenarioType;
import com.trade.exception.NoAvailableModelException;
import com.trade.model.ModelRegistry;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 智能模型路由器 —— 根据请求内容、场景、用户偏好，决策使用哪个 AI 模型。
 *
 * 核心设计思想：不同 AI 模型有各自的优势，路由通过关键词匹配 + 场景匹配 + 能力标签
 * 为每个可用模型打分，选最高分的作为目标模型。
 *
 * 打分规则（基础分 50 × 权重）：
 * - QA 场景 + 模型具备 qa 能力 → +10
 * - FLOW 场景 + 模型具备 process/planning 能力 → +24
 * - 长上下文（超过阈值）+ 阿里百炼 → +28（千问长文本理解能力强）
 * - 含流程关键词 + 阿里百炼 → +18
 * - 含推理/代码关键词或精确模式 + DeepSeek → +22（DeepSeek 推理能力强）
 * - 默认模型 → +2（同分情况下优先默认）
 *
 * 降级策略：如果首选模型不可用（API Key 失效、网络不通），按 fallbackOrder 降级。
 */
@Component
public class ModelRouter {

    // 推理类关键词 —— 命中这些词时优先选 DeepSeek（推理/代码能力强）
    private static final Set<String> REASONING_KEYWORDS = Set.of(
            "推理", "计算", "代码", "bug", "异常", "sql", "java", "接口", "算法", "分析", "精确"
    );
    // 流程类关键词 —— 命中这些词时优先选阿里百炼（长上下文/规划能力强）
    private static final Set<String> PROCESS_KEYWORDS = Set.of(
            "流程", "审批", "步骤", "任务", "排期", "计划", "负责人", "监控", "自动化", "里程碑"
    );

    /** 从 application.yml 读取的网关配置 */
    private final AiGatewayProperties properties;
    /** 模型注册中心 —— 查询模型可用性和配置参数 */
    private final ModelRegistry registry;

    public ModelRouter(AiGatewayProperties properties, ModelRegistry registry) {
        this.properties = properties;
        this.registry = registry;
    }

    /**
     * 路由决策入口 —— 为每个可用模型打分，选最高分。
     *
     * 流程：
     * 1. 遍历所有已注册的模型，检查可用性（API Key、baseUrl 等配置是否完整）
     * 2. 对每个可用模型调用 score() 打分
     * 3. 如果没有任何可用模型，抛出 NoAvailableModelException
     * 4. select() 选择最优模型（用户指定首选则直接用，否则选最高分）
     * 5. fallbackFor() 确定降级模型
     */
    public RouteDecision route(ModelRouteRequest request) {
        Map<ModelProvider, Double> scores = new EnumMap<>(ModelProvider.class);
        for (ModelProvider provider : ModelProvider.values()) {
            if (registry.available(provider).isPresent()) {
                //对每个可用模型调用 score() 打分
                scores.put(provider, score(provider, request));
            }
        }
        if (scores.isEmpty()) {
            throw new NoAvailableModelException("没有可用模型，请检查 DEEPSEEK_API_KEY 或 BAILIAN_API_KEY 配置");
        }
        ModelProvider selected = select(request, scores);
        ModelProvider fallback = fallbackFor(selected, scores);
        double score = scores.get(selected);
        return new RouteDecision(
                request.scenario(),
                selected,
                fallback,
                score,
                reason(selected, request),
                scores
        );
    }

    /**
     * 选择最终模型 —— 用户指定了 preferredModel 且可用则直接用，否则选打分最高的。
     *
     * 技术点：Stream API 的 max 操作 + Comparator，配合 Optional.orElse 提供兜底值。
     */
    private ModelProvider select(ModelRouteRequest request, Map<ModelProvider, Double> scores) {
        if (request.preferredModel() != null && scores.containsKey(request.preferredModel())) {
            return request.preferredModel();
        }
        return scores.entrySet().stream()
                .max(Comparator.comparingDouble(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(properties.getRouting().getDefaultModel());
    }

    /**
     * 确定降级模型 —— 从配置的 fallbackOrder 中找第一个不等于已选模型且可用的。
     * 如果没有可用的 fallback 则返回 null，表示不可降级。
     */
    private ModelProvider fallbackFor(ModelProvider selected, Map<ModelProvider, Double> scores) {
        return properties.getRouting().getFallbackOrder().stream()
                .filter(provider -> provider != selected)
                .filter(scores::containsKey)
                .findFirst()
                .orElse(null);
    }

    /**
     * 为单个模型打分 —— 路由决策的核心算法。
     *
     * 打分维度（累加制）：
     * 1. 基础分 = 50 × 模型权重（weight 在 application.yml 中配置）
     * 2. 场景匹配：
     *    - QA 场景 + 模型有 "qa" 能力标签 → +10
     *    - FLOW 场景 + 模型有 "process" 或 "planning" 能力标签 → +24
     * 3. 长上下文：内容长度超过阈值 + 阿里百炼 → +28
     * 4. 关键词匹配：
     *    - 含流程关键词 + 阿里百炼 → +18
     *    - 含推理关键词或精确模式 + DeepSeek → +22
     * 5. 默认模型偏好 → +2
     *
     * 示例：用户问"帮我写一个 Java 接口"
     * - DeepSeek: 50×1.0(基础) + 22(推理关键词) + 2(默认模型) = 74
     * - 阿里百炼: 50×1.0(基础) = 50
     * → 选 DeepSeek
     */
    private double score(ModelProvider provider, ModelRouteRequest request) {
        AiGatewayProperties.ModelConfig config = registry.config(provider);
        double score = 50.0 * config.getWeight();
        String content = normalize(request.content());
        if (request.scenario() == ScenarioType.QA && hasCapability(config, "qa")) {
            score += 10;
        }
        if (request.scenario() == ScenarioType.FLOW && hasCapability(config, "process", "planning")) {
            score += 24;
        }
        if (content.length() >= properties.getRouting().getLongContextThreshold() && provider == ModelProvider.ALIBABA_BAILIAN) {
            score += 28;
        }
        if (containsAny(content, PROCESS_KEYWORDS) && provider == ModelProvider.ALIBABA_BAILIAN) {
            score += 18;
        }
        if ((containsAny(content, REASONING_KEYWORDS) || request.preciseMode()) && provider == ModelProvider.DEEPSEEK) {
            score += 22;
        }
        if (provider == properties.getRouting().getDefaultModel()) {
            score += 2;
        }
        return score;
    }

    /** 判断模型是否具备所需能力（忽略大小写匹配） */
    private boolean hasCapability(AiGatewayProperties.ModelConfig config, String... names) {
        for (String capability : config.getCapabilities()) {
            for (String name : names) {
                if (capability.equalsIgnoreCase(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 判断内容是否包含任一关键词 */
    private boolean containsAny(String content, Set<String> keywords) {
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 内容规范化为小写 —— 保证关键词匹配不区分大小写 */
    private String normalize(String content) {
        return StringUtils.hasText(content) ? content.toLowerCase(Locale.ROOT) : "";
    }

    /**
     * 生成路由决策说明 —— 告诉客户端"为什么选了这个模型"，方便调试和理解。
     */
    private String reason(ModelProvider selected, ModelRouteRequest request) {
        if (request.preferredModel() == selected) {
            return "请求指定了首选模型，且该模型当前可用";
        }
        if (request.scenario() == ScenarioType.FLOW && selected == ModelProvider.ALIBABA_BAILIAN) {
            return "流程助手场景优先选择阿里百炼通义千问的长上下文与规划能力";
        }
        if (selected == ModelProvider.DEEPSEEK) {
            return "问答、代码、推理或精确回答场景优先选择 DeepSeek";
        }
        return "请求内容更适合长上下文理解与任务规划模型";
    }
}
