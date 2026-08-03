package com.example.ragassitant.infrastructure.config;

import com.example.ragassitant.domain.service.EnergyAggregator;
import com.example.ragassitant.domain.service.HeuristicRouter;
import com.example.ragassitant.domain.service.PromptBuilder;
import com.example.ragassitant.domain.service.TextToolCallParser;
import com.example.ragassitant.domain.service.ToolCallValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * domain 层的无状态服务注册成 bean。
 *
 * <p>domain 不依赖 Spring 注解（保持可单测、可脱离容器），所以由 infrastructure 这层显式装配。
 *
 * <p>{@code ToolCallParser} 的 bean 已随类一起删除：它负责从 LLM 文本里正则抠出
 * {@code [{"name":...,"arguments":...}]}，是手写 Agent 循环时代的产物。
 * 迁到 Spring AI 2.0 原生 Tool Calling（结构化 tool_calls 字段）之后再无调用方，
 * 且它的 {@code \{[^}]*\}} 正则本身也处理不了嵌套参数对象。
 */
@Configuration
public class DomainServiceConfig {

    @Bean
    public HeuristicRouter heuristicRouter() {
        return new HeuristicRouter();
    }

    /** 由 {@code SpringAiToolRegistry#invoke} 使用（FORCE_EXEC 路径的参数校验）。 */
    @Bean
    public ToolCallValidator toolCallValidator() {
        return new ToolCallValidator();
    }

    /**
     * 弱模型兜底：识别「模型把工具调用写成文本」的情况。
     *
     * <p>注意它和被删掉的 {@code ToolCallParser} 定位不同 —— 那个是手写 Agent 循环的<b>主路径</b>，
     * 这个只在原生 Tool Calling 失效时兜底，见 {@link TextToolCallParser} 的类注释。
     */
    @Bean
    public TextToolCallParser textToolCallParser() {
        return new TextToolCallParser();
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