package com.trade.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI (Swagger) 配置类
 *
 * 配置 Swagger UI 和 API 文档的元信息。
 *
 * 访问地址：
 * - Swagger UI: http://localhost:8080/swagger-ui.html
 * - API Docs: http://localhost:8080/v3/api-docs
 *
 * 技术栈：
 * - SpringDoc OpenAPI 2.8.9（兼容 Spring Boot 3.x）
 * - OpenAPI 3.0 规范
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                // API 基本信息
                .info(new Info()
                        .title("智能外贸助手 API")
                        .version("1.0.0")
                        .description("""
                                ## 智能外贸助手 REST API 文档

                                本系统提供以下核心功能：

                                ### 1. 智能问答 (`/api/v1/chat`)
                                - 同步问答和流式问答（SSE）
                                - 支持 RAG 检索增强（知识库）
                                - 自动模型路由和降级

                                ### 2. 流程规划 (`/api/v1/flows`)
                                - 业务流程自动拆解
                                - 任务计划生成
                                - 风险识别和监控

                                ### 3. 知识库管理 (`/api/v1/knowledge`)
                                - 文档上传和摄入
                                - 语义搜索
                                - RAG 问答

                                ### 4. 外贸销售 (`/api/v1/trade`)
                                - 商机分析
                                - 销售计划
                                - 客户回复生成

                                ### 认证方式
                                所有 API 需要在请求头中携带 Token：
                                ```
                                Authorization: Bearer dev-token
                                ```

                                ### 响应格式
                                - 同步接口：返回 JSON 对象
                                - 流式接口：SSE (Server-Sent Events)
                                """)
                        .contact(new Contact()
                                .name("技术支持")
                                .email("support@example.com")
                                .url("https://example.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                // 服务器配置
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8080")
                                .description("本地开发环境"),
                        new Server()
                                .url("https://api.example.com")
                                .description("生产环境")
                ));
    }
}
