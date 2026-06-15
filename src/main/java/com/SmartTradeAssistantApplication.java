package com;

import com.trade.config.AiGatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableCaching
@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties(AiGatewayProperties.class)
public class SmartTradeAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartTradeAssistantApplication.class, args);
    }
}
