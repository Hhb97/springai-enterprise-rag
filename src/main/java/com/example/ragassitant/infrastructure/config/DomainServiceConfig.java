package com.example.ragassitant.infrastructure.config;

import com.example.ragassitant.domain.service.EnergyAggregator;
import com.example.ragassitant.domain.service.HeuristicRouter;
import com.example.ragassitant.domain.service.PromptBuilder;
import com.example.ragassitant.domain.service.ToolCallParser;
import com.example.ragassitant.domain.service.ToolCallValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DomainServiceConfig {

    @Bean
    public HeuristicRouter heuristicRouter() {
        return new HeuristicRouter();
    }

    @Bean
    public ToolCallParser toolCallParser() {
        return new ToolCallParser();
    }

    @Bean
    public ToolCallValidator toolCallValidator() {
        return new ToolCallValidator();
    }

    @Bean
    public PromptBuilder promptBuilder() {
        return new PromptBuilder();
    }

    @Bean
    public EnergyAggregator energyAggregator() {
        return new EnergyAggregator();
    }
}