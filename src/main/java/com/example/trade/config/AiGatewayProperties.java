package com.example.trade.config;

import com.example.trade.enums.ModelProvider;
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

    /** RAG 知识库配置，控制文档摄入和向量检索 */
    @Valid
    private Rag rag = new Rag();

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

    public Rag getRag() {
        return rag;
    }

    public void setRag(Rag rag) {
        this.rag = rag;
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

    /**
     * RAG 知识库子配置 —— 控制文档摄入、分块、Embedding 和向量检索。
     * 对应 YAML 路径: ai.gateway.rag.*
     *
     * 核心流程：
     * 1. 文档上传 → LangChain4j 解析 PDF/DOCX → 纯文本
     * 2. 文本分块（Chunking）→ 每块约 500 字符，重叠 50 字符
     * 3. 调用 Embedding 模型 → 文本转为 1024 维向量
     * 4. 写入 Milvus 向量数据库 → 建立 IVF_FLAT 索引
     * 5. 检索时：问题 → Embedding → Milvus 相似度搜索 → Top-K 相关片段
     */
    public static class Rag {
        /** 向量存储类型，目前仅支持 milvus */
        private String vectorStoreType = "milvus";

        /** Milvus 集合（Collection）名称，相当于关系型数据库的表名 */
        private String collectionName = "trade_knowledge";

        /** Embedding 向量维度，需与 Embedding 模型输出维度一致。
         *  DashScope text-embedding-v3 默认 1024，OpenAI text-embedding-3-small 默认 1536 */
        @Min(1)
        @Max(8192)
        private int dimension = 1024;

        /** 单次检索返回的最大文档片段数（Top-K） */
        @Min(1)
        @Max(100)
        private int maxResults = 10;

        /** 相似度阈值，低于此分数的结果被过滤。范围 0~1，越大越严格 */
        @Min(0)
        @Max(1)
        private double similarityThreshold = 0.7;

        /** 文本分块大小（字符数），每块约 500 字符 */
        @Min(100)
        @Max(5000)
        private int chunkSize = 500;

        /** 分块重叠字符数，保证上下文连贯性 */
        @Min(0)
        @Max(1000)
        private int chunkOverlap = 50;

        /** Embedding 模型配置 —— 主模型 + 降级模型 */
        @Valid
        private Embedding embedding = new Embedding();

        /** 降级 Embedding 配置 —— 主模型不可用时自动切换 */
        @Valid
        private Embedding fallbackEmbedding = new Embedding();

        /** 健康检查配置 */
        @Valid
        private HealthCheck healthCheck = new HealthCheck();

        /** 成本监控配置 */
        @Valid
        private CostMonitor costMonitor = new CostMonitor();

        /** Milvus 连接配置 */
        @Valid
        private Milvus milvus = new Milvus();

        // ==================== getter / setter ====================

        public String getVectorStoreType() {
            return vectorStoreType;
        }

        public void setVectorStoreType(String vectorStoreType) {
            this.vectorStoreType = vectorStoreType;
        }

        public String getCollectionName() {
            return collectionName;
        }

        public void setCollectionName(String collectionName) {
            this.collectionName = collectionName;
        }

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }

        public int getMaxResults() {
            return maxResults;
        }

        public void setMaxResults(int maxResults) {
            this.maxResults = maxResults;
        }

        public double getSimilarityThreshold() {
            return similarityThreshold;
        }

        public void setSimilarityThreshold(double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
        }

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        public int getChunkOverlap() {
            return chunkOverlap;
        }

        public void setChunkOverlap(int chunkOverlap) {
            this.chunkOverlap = chunkOverlap;
        }

        public Embedding getEmbedding() {
            return embedding;
        }

        public void setEmbedding(Embedding embedding) {
            this.embedding = embedding;
        }

        public Milvus getMilvus() {
            return milvus;
        }

        public void setMilvus(Milvus milvus) {
            this.milvus = milvus;
        }

        public HealthCheck getHealthCheck() {
            return healthCheck;
        }

        public void setHealthCheck(HealthCheck healthCheck) {
            this.healthCheck = healthCheck;
        }

        public CostMonitor getCostMonitor() {
            return costMonitor;
        }

        public void setCostMonitor(CostMonitor costMonitor) {
            this.costMonitor = costMonitor;
        }

        public Embedding getFallbackEmbedding() {
            return fallbackEmbedding;
        }

        public void setFallbackEmbedding(Embedding fallbackEmbedding) {
            this.fallbackEmbedding = fallbackEmbedding;
        }

        /**
         * Embedding 模型配置 —— 将文本转为向量的模型参数。
         * 支持 OpenAI 兼容的 /v1/embeddings 接口（DeepSeek、DashScope 均支持）。
         */
        public static class Embedding {
            /** Embedding 模型名称。
             *  DashScope: text-embedding-v3（1024 维，中文效果好）
             *  OpenAI: text-embedding-3-small（1536 维）
             *  Mock: mock-embedding（用于测试） */
            @NotBlank(message = "rag.embedding.model 不能为空")
            private String model = "text-embedding-v3";

            /** Embedding API 地址，兼容 OpenAI /v1/embeddings 协议 */
            @NotBlank(message = "rag.embedding.baseUrl 不能为空")
            private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

            /** Embedding API 路径 */
            private String path = "/embeddings";

            /** Embedding API Key */
            private String apiKey;

            /** 编码格式，固定为 float */
            private String encodingFormat = "float";

            /** 请求超时时间 */
            private Duration timeout = Duration.ofSeconds(30);

            /** 重试次数 */
            @Min(0)
            @Max(5)
            private int maxRetries = 2;

            /** 单价（元/百万 token），用于成本监控 */
            @Min(0)
            private double pricePerMillionTokens = 0.0;

            /** 月度预算（元），超过时触发告警 */
            @Min(0)
            private double monthlyBudget = 1000.0;

            // ==================== getter / setter ====================

            public String getModel() {
                return model;
            }

            public void setModel(String model) {
                this.model = model;
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

            public String getEncodingFormat() {
                return encodingFormat;
            }

            public void setEncodingFormat(String encodingFormat) {
                this.encodingFormat = encodingFormat;
            }

            public Duration getTimeout() {
                return timeout;
            }

            public void setTimeout(Duration timeout) {
                this.timeout = timeout;
            }

            public int getMaxRetries() {
                return maxRetries;
            }

            public void setMaxRetries(int maxRetries) {
                this.maxRetries = maxRetries;
            }

            public double getPricePerMillionTokens() {
                return pricePerMillionTokens;
            }

            public void setPricePerMillionTokens(double pricePerMillionTokens) {
                this.pricePerMillionTokens = pricePerMillionTokens;
            }

            public double getMonthlyBudget() {
                return monthlyBudget;
            }

            public void setMonthlyBudget(double monthlyBudget) {
                this.monthlyBudget = monthlyBudget;
            }

            public String getFullUrl() {
                String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
                String p = path.startsWith("/") ? path : "/" + path;
                return base + p;
            }
        }

        /**
         * Milvus 向量数据库连接配置。
         * 对应 YAML 路径: ai.gateway.rag.milvus.*
         *
         * 注意：
         * - 19530 是 gRPC 端口（Milvus SDK 使用）
         * - 9091 是 HTTP/REST 端口
         * 本项目使用 Milvus Java SDK，默认端口为 19530
         */
        public static class Milvus {
            /** Milvus 服务地址 */
            @NotBlank(message = "rag.milvus.host 不能为空")
            private String host = "localhost";

            /** Milvus gRPC 端口。默认 19530 */
            @Min(1)
            @Max(65535)
            private int port = 19530;

            /** 用户名（可选，本地部署通常不需要） */
            private String username;

            /** 密码（可选，本地部署通常不需要） */
            private String password;

            /** 连接超时时间 */
            private Duration timeout = Duration.ofSeconds(10);

            // ==================== getter / setter ====================

            public String getHost() {
                return host;
            }

            public void setHost(String host) {
                this.host = host;
            }

            public int getPort() {
                return port;
            }

            public void setPort(int port) {
                this.port = port;
            }

            public String getUsername() {
                return username;
            }

            public void setUsername(String username) {
                this.username = username;
            }

            public String getPassword() {
                return password;
            }

            public void setPassword(String password) {
                this.password = password;
            }

            public Duration getTimeout() {
                return timeout;
            }

            public void setTimeout(Duration timeout) {
                this.timeout = timeout;
            }

            /** 拼接 Milvus 连接地址（gRPC） */
            public String getUri() {
                return host + ":" + port;
            }
        }

        /**
         * 健康检查配置 —— 定期检查 Embedding 服务可用性。
         * 对应 YAML 路径: ai.gateway.rag.healthCheck.*
         */
        public static class HealthCheck {
            /** 是否启用健康检查 */
            private boolean enabled = true;

            /** 健康检查间隔（秒） */
            @Min(10)
            @Max(300)
            private int intervalSeconds = 60;

            /** 超时时间（秒），超过此时间认为服务不可用 */
            @Min(1)
            @Max(30)
            private int timeoutSeconds = 5;

            /** 连续失败多少次触发告警 */
            @Min(1)
            @Max(10)
            private int failureThreshold = 3;

            // ==================== getter / setter ====================

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public int getIntervalSeconds() {
                return intervalSeconds;
            }

            public void setIntervalSeconds(int intervalSeconds) {
                this.intervalSeconds = intervalSeconds;
            }

            public int getTimeoutSeconds() {
                return timeoutSeconds;
            }

            public void setTimeoutSeconds(int timeoutSeconds) {
                this.timeoutSeconds = timeoutSeconds;
            }

            public int getFailureThreshold() {
                return failureThreshold;
            }

            public void setFailureThreshold(int failureThreshold) {
                this.failureThreshold = failureThreshold;
            }
        }

        /**
         * 成本监控配置 —— 跟踪 Embedding 调用的 token 消耗和成本。
         * 对应 YAML 路径: ai.gateway.rag.costMonitor.*
         */
        public static class CostMonitor {
            /** 是否启用成本监控 */
            private boolean enabled = true;

            /** 月度预算（元），超过时触发告警 */
            @Min(0)
            private double monthlyBudget = 1000.0;

            /** 预算告警阈值（百分比），达到 80% 时开始告警 */
            @Min(0)
            @Max(100)
            private int budgetAlertThreshold = 80;

            /** 是否记录每次调用的详细日志 */
            private boolean logDetailedUsage = false;

            // ==================== getter / setter ====================

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public double getMonthlyBudget() {
                return monthlyBudget;
            }

            public void setMonthlyBudget(double monthlyBudget) {
                this.monthlyBudget = monthlyBudget;
            }

            public int getBudgetAlertThreshold() {
                return budgetAlertThreshold;
            }

            public void setBudgetAlertThreshold(int budgetAlertThreshold) {
                this.budgetAlertThreshold = budgetAlertThreshold;
            }

            public boolean isLogDetailedUsage() {
                return logDetailedUsage;
            }

            public void setLogDetailedUsage(boolean logDetailedUsage) {
                this.logDetailedUsage = logDetailedUsage;
            }
        }
    }
}
