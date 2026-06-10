package com.example.multiai;

import com.example.multiai.config.AiGatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;

@EnableCaching
@SpringBootApplication
@EnableConfigurationProperties(AiGatewayProperties.class)
public class SmartTradeAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartTradeAssistantApplication.class, args);
    }
}
