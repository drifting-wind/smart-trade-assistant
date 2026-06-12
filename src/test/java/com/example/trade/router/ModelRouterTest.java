package com.example.trade.router;

import com.example.trade.config.AiGatewayProperties;
import com.example.trade.enums.ModelProvider;
import com.example.trade.enums.ScenarioType;
import com.example.trade.model.ModelRegistry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRouterTest {

    @Test
    void shouldPreferDeepSeekForPreciseReasoningQuestion() {
        ModelRouter router = router();

        RouteDecision decision = router.route(new ModelRouteRequest(
                ScenarioType.QA,
                "请分析这段 Java 代码里的异常并给出精确修复方案",
                null,
                true
        ));

        assertThat(decision.selectedModel()).isEqualTo(ModelProvider.DEEPSEEK);
        assertThat(decision.fallbackModel()).isEqualTo(ModelProvider.ALIBABA_BAILIAN);
    }

    @Test
    void shouldPreferBailianForFlowPlanning() {
        ModelRouter router = router();

        RouteDecision decision = router.route(new ModelRouteRequest(
                ScenarioType.FLOW,
                "请把采购审批流程拆成任务、负责人、监控指标和自动化步骤",
                null,
                false
        ));

        assertThat(decision.selectedModel()).isEqualTo(ModelProvider.ALIBABA_BAILIAN);
    }

    @Test
    void shouldHonorPreferredModelWhenAvailable() {
        ModelRouter router = router();

        RouteDecision decision = router.route(new ModelRouteRequest(
                ScenarioType.QA,
                "普通企业知识问答",
                ModelProvider.ALIBABA_BAILIAN,
                false
        ));

        assertThat(decision.selectedModel()).isEqualTo(ModelProvider.ALIBABA_BAILIAN);
    }

    private ModelRouter router() {
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.getRouting().setFallbackOrder(List.of(ModelProvider.DEEPSEEK, ModelProvider.ALIBABA_BAILIAN));
        LinkedHashMap<String, AiGatewayProperties.ModelConfig> models = new LinkedHashMap<>();
        models.put("deepseek", model(ModelProvider.DEEPSEEK, List.of("qa", "reasoning", "code", "analysis")));
        models.put("bailian", model(ModelProvider.ALIBABA_BAILIAN, List.of("qa", "long_context", "process", "planning", "writing")));
        properties.setModels(models);
        ModelRegistry registry = new ModelRegistry(properties);
        return new ModelRouter(properties, registry);
    }

    private AiGatewayProperties.ModelConfig model(ModelProvider provider, List<String> capabilities) {
        AiGatewayProperties.ModelConfig config = new AiGatewayProperties.ModelConfig();
        config.setProvider(provider);
        config.setEnabled(true);
        config.setBaseUrl("http://localhost");
        config.setPath("/chat/completions");
        config.setApiKey("test");
        config.setModel(provider.name().toLowerCase());
        config.setCapabilities(capabilities);
        return config;
    }
}
