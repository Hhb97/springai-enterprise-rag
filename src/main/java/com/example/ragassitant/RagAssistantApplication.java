package com.example.ragassitant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @see org.springframework.boot.context.properties.ConfigurationPropertiesScan 注册 {@code *Properties}
 *      强类型配置类，替代散落各处的 {@code @Value}
 * @see org.springframework.scheduling.annotation.EnableScheduling 会话过期清理需要它，
 *      否则 {@code ConversationStorePort#clearExpired} 永远不会被触发（改造前就是这个状态）
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class RagAssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(RagAssistantApplication.class, args);
    }
}
