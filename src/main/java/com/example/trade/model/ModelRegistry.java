package com.example.trade.model;

import com.example.trade.config.AiGatewayProperties;
import com.example.trade.enums.ModelProvider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 模型注册中心 —— 管理所有已配置模型的客户端实例。
 *
 * 构造函数中遍历 AiGatewayProperties 的 models 配置，
 * 为每个有 provider 的模型创建 LangChain4jChatModel 实例。
 *
 * 核心方法：
 * - require(provider)：获取指定模型客户端，不存在则抛异常
 * - available(provider)：获取指定模型，过滤掉不可用的（apiKey 为空等）
 * - availableModels()：列出所有可用模型
 * - config(provider)：获取模型的配置参数（超时、temperature 等）
 */
@Component
public class ModelRegistry {

    private final Map<ModelProvider, AiChatModel> models = new EnumMap<>(ModelProvider.class);
    private final AiGatewayProperties properties;

    public ModelRegistry(AiGatewayProperties properties) {
        this.properties = properties;
        properties.getModels().values().forEach(config -> {
            if (config.getProvider() != null) {
                models.put(config.getProvider(), new LangChain4jChatModel(config.getProvider(), config));
            }
        });
    }
    // require(provider)：获取指定模型客户端，不存在则抛异常
    public AiChatModel require(ModelProvider provider) {
        return Optional.ofNullable(models.get(provider))
                .orElseThrow(() -> new IllegalArgumentException("未配置模型: " + provider));
    }
   //available(provider)：获取指定模型，过滤掉不可用的（apiKey 为空等）
    public Optional<AiChatModel> available(ModelProvider provider) {
        return Optional.ofNullable(models.get(provider)).filter(AiChatModel::available);
    }
    //availableModels()：列出所有可用模型
    public List<AiChatModel> availableModels() {
        return models.values().stream().filter(AiChatModel::available).toList();
    }
    //config(provider)：获取模型的配置参数（超时、temperature 等）
    public AiGatewayProperties.ModelConfig config(ModelProvider provider) {
        return properties.getModels().values().stream()
                .filter(config -> provider == config.getProvider())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未配置模型参数: " + provider));
    }
}
