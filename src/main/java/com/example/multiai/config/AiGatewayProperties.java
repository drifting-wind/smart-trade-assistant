package com.example.multiai.config;

import com.example.multiai.enums.ModelProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 网关配置属性类 —— 从 application.yml 的 ai.gateway 前缀读取配置。
 *
 * Spring Boot 会自动将 YAML 中的键映射到对应的 getter/setter 上，
 * 例如 ai.gateway.cache-ttl → setCacheTtl()，ai.gateway.models.deepseek → models.put("deepseek", ...)
 *
 * 核心职责：
 * 1. 集中管理所有 AI 模型相关的配置（超时、缓存、认证、路由策略等）
 * 2. 通过 @Validated 开启 JSR-303 校验，启动时拦截非法配置
 * 3. 提供便捷查询方法，避免调用方直接操作内部 Map
 */
@Validated
@ConfigurationProperties(prefix = "ai.gateway")
public class AiGatewayProperties {

    /** 响应缓存有效期，默认 10 分钟 —— 相同请求直接从缓存返回，不调用 AI 模型 */
    private Duration cacheTtl = Duration.ofMinutes(10);

    /** 会话（conversation）存活时间，默认 2 小时 —— 超时后历史对话自动过期 */
    private Duration conversationTtl = Duration.ofHours(2);

    /** 每个会话保留的最大历史消息数，超过时截掉最旧的 */
    private int maxHistoryMessages = 16;

    /** 安全认证配置，内部类，支持开关 + 令牌白名单 */
    @Valid
    private Security security = new Security();

    /** 路由策略配置，决定请求发给哪个 AI 模型 */
    @Valid
    private Routing routing = new Routing();

    /**
     * 模型注册表 —— key 是自定义别名（如 "deepseek"、"bailian"），
     * value 是该模型的完整配置。使用 LinkedHashMap 保证 YAML 中的声明顺序。
     */
    @Valid
    private Map<String, ModelConfig> models = new LinkedHashMap<>();

    // ==================== 顶层 getter / setter ====================

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public Duration getConversationTtl() {
        return conversationTtl;
    }

    public void setConversationTtl(Duration conversationTtl) {
        this.conversationTtl = conversationTtl;
    }

    public int getMaxHistoryMessages() {
        return maxHistoryMessages;
    }

    public void setMaxHistoryMessages(int maxHistoryMessages) {
        this.maxHistoryMessages = maxHistoryMessages;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public Routing getRouting() {
        return routing;
    }

    public void setRouting(Routing routing) {
        this.routing = routing;
    }

    public Map<String, ModelConfig> getModels() {
        return models;
    }

    public void setModels(Map<String, ModelConfig> models) {
        this.models = models;
    }

    // ==================== 便捷查询方法（业务逻辑） ====================

    /**
     * 按别名获取模型配置，找不到返回 null。
     * 等价于 getModels().get(alias)，但对外隐藏了内部 Map。
     */
    public ModelConfig getModelConfig(String alias) {
        if (alias == null || alias.isBlank()) {
            return null;
        }
        return models.get(alias);
    }

    /**
     * 返回所有已启用模型的配置列表，按 YAML 声明顺序排列。
     * 路由组件只关心"能用"的模型，不需要知道被禁用的。
     */
    public List<ModelConfig> getEnabledModels() {
        return models.values().stream()
                .filter(ModelConfig::isEnabled)
                .collect(Collectors.toList());
    }

    /**
     * 按能力标签过滤已启用的模型。例如传 ["code", "reasoning"]
     * 返回同时具备这两种能力的模型。
     */
    public List<ModelConfig> getModelsByCapabilities(List<String> requiredCapabilities) {
        return models.values().stream()
                .filter(ModelConfig::isEnabled)
                .filter(mc -> mc.getCapabilities().containsAll(requiredCapabilities))
                .collect(Collectors.toList());
    }

    /**
     * 校验是否存在至少一个可用的模型。
     * 应用启动时可调用此方法做健康检查。
     */
    public boolean hasAnyEnabledModel() {
        return models.values().stream().anyMatch(ModelConfig::isEnabled);
    }

    // ==================== 内部配置类 ====================

    /**
     * 安全认证子配置 —— 控制网关是否需要 API Token 认证。
     * 对应 YAML 路径: ai.gateway.security.*
     */
    public static class Security {
        /** 是否开启 Token 认证，生产环境务必设为 true */
        private boolean enabled = true;
        /** 允许的 Token 列表，为空时拒绝所有请求 */
        private List<String> tokens = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getTokens() {
            return tokens;
        }

        public void setTokens(List<String> tokens) {
            this.tokens = tokens;
        }

        /** 校验 Token 是否有效 —— 白名单匹配 */
        public boolean isValidToken(String token) {
            return token != null && tokens.contains(token);
        }
    }

    /**
     * 路由策略子配置 —— 决定请求发给哪个模型。
     * 对应 YAML 路径: ai.gateway.routing.*
     *
     * 策略说明：
     * 1. 默认使用 defaultModel 指定的模型
     * 2. 如果默认模型不可用，按 fallbackOrder 依次降级
     * 3. 当 prompt 长度超过 longContextThreshold，自动切换到支持长上下文的模型
     */
    public static class Routing {
        /** 默认路由的目标模型，启动时必须至少有一个同名模型已启用 */
        @NotNull(message = "routing.defaultModel 不能为空")
        private ModelProvider defaultModel = ModelProvider.DEEPSEEK;

        /** 降级顺序列表 —— 当前一个模型不可用时，依次尝试后续模型 */
        private List<ModelProvider> fallbackOrder = List.of(ModelProvider.DEEPSEEK, ModelProvider.ALIBABA_BAILIAN);

        /**
         * 长上下文阈值（字符数）。
         * 超过此值时，路由组件会优先选择 capabilities 包含 "long_context" 的模型。
         */
        @Min(500)
        @Max(32000)
        private int longContextThreshold = 4000;

        public ModelProvider getDefaultModel() {
            return defaultModel;
        }

        public void setDefaultModel(ModelProvider defaultModel) {
            this.defaultModel = defaultModel;
        }

        public List<ModelProvider> getFallbackOrder() {
            return fallbackOrder;
        }

        public void setFallbackOrder(List<ModelProvider> fallbackOrder) {
            this.fallbackOrder = fallbackOrder;
        }

        public int getLongContextThreshold() {
            return longContextThreshold;
        }

        public void setLongContextThreshold(int longContextThreshold) {
            this.longContextThreshold = longContextThreshold;
        }

        /** 判断给定文本长度是否超过长上下文阈值 */
        public boolean isLongContext(String text) {
            return text != null && text.length() > longContextThreshold;
        }
    }

    /**
     * 单个 AI 模型的完整配置。
     * 对应 YAML 路径: ai.gateway.models.<alias>.*
     *
     * 每个模型可以独立配置：
     * - provider: 供应商枚举（决定用哪个适配器/客户端）
     * - baseUrl + path: HTTP 请求的目标地址（兼容 OpenAI 接口协议）
     * - apiKey: 认证密钥
     * - timeout: 单次请求超时时间
     * - maxTokens / temperature: 生成参数
     * - weight: 负载均衡权重（未来扩展）
     * - capabilities: 能力标签，路由时用于匹配需求
     */
    public static class ModelConfig {
        /** 模型供应商，决定使用哪个底层 API 协议 */
        @NotNull(message = "models.*.provider 不能为空")
        private ModelProvider provider;

        /** 是否启用此模型，可在运行时通过环境变量开关 */
        private boolean enabled = true;

        /** API 基础地址，例如 https://api.deepseek.com */
        @NotBlank(message = "models.*.baseUrl 不能为空")
        private String baseUrl;

        /** 请求路径，默认 /chat/completions 兼容 OpenAI 协议 */
        private String path = "/chat/completions";

        /** 认证 API Key */
        private String apiKey;

        /** 模型名称，如 deepseek-chat、qwen-plus */
        @NotBlank(message = "models.*.model 不能为空")
        private String model;

        /** 单次请求超时时间，超过此时间自动断开 */
        private Duration timeout = Duration.ofSeconds(60);

        /** 最大生成 token 数，控制输出长度上限 */
        @Min(1)
        @Max(16384)
        private int maxTokens = 4096;

        /** 温度参数，范围 0~1，
         * 越低输出越确定、越高越有创造性
         * 温度值越小回答的越严格
         * */

        @Min(0)
        @Max(2)
        private double temperature = 0.3;

        /** 负载均衡权重，数值越大被选中的概率越高（预留） */
        private double weight = 1.0;

        /** 能力标签列表，路由组件根据需求匹配 */
        private List<String> capabilities = new ArrayList<>();

        // ==================== getter / setter ====================

        public ModelProvider getProvider() {
            return provider;
        }

        public void setProvider(ModelProvider provider) {
            this.provider = provider;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public double getWeight() {
            return weight;
        }

        public void setWeight(double weight) {
            this.weight = weight;
        }

        public List<String> getCapabilities() {
            return capabilities;
        }

        public void setCapabilities(List<String> capabilities) {
            this.capabilities = capabilities;
        }

        /**
         * 拼接完整的请求 URL。
         * 例如 baseUrl="https://api.deepseek.com", path="/chat/completions"
         * → 返回 "https://api.deepseek.com/chat/completions"
         */
        public String getFullUrl() {
            String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            String p = path.startsWith("/") ? path : "/" + path;
            return base + p;
        }

        /** 判断该模型是否具备指定能力 */
        public boolean hasCapability(String capability) {
            return capabilities.contains(capability);
        }
    }
}
