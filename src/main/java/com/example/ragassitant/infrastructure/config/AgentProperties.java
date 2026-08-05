package com.example.ragassitant.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 链路的可调参数。
 *
 * <p>【为什么要有这个类】改造前这些值分成两拨，两拨都是坏的：
 * <ul>
 *   <li>写在 yml 里但<b>没有任何代码读取</b>：{@code agent.default-max-iterations}、
 *       {@code agent.default-memory-size}、{@code prompt.agent.max-history}
 *       —— 改它们不产生任何效果（和 {@code rag.similarity-threshold} 当初一样）；</li>
 *   <li>硬编码成 {@code private static final}：{@code ENABLE_HEURISTIC_ROUTE}、
 *       {@code HEURISTIC_STRATEGY}、{@code MAX_TOOL_CALLS}、{@code MAX_HISTORY_PER_SESSION}
 *       —— 而前两个恰恰是最需要线上灰度和快速回滚的开关，改一次要重新编译。</li>
 * </ul>
 * 现在统一收在这里：强类型绑定、有默认值、启动时打印生效值。
 * {@code prompt.agent.max-history} 与 {@code agent.default-memory-size} 语义重复，已合并为
 * {@link #memorySize}。
 */
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private static final Logger log = LoggerFactory.getLogger(AgentProperties.class);

    /** LLM 自动 Tool Calling 的最大轮次，超过即强制出最终回答。 */
    private int maxToolCalls = 5;

    /** 每个会话最多带入多少轮历史对话。 */
    private int memorySize = 20;

    private final Heuristic heuristic = new Heuristic();

    /** 启发式路由：用正则先判定用户意图，命中则直接执行工具，省掉一次 LLM 决策往返。 */
    public static class Heuristic {

        /** 关掉后完全交给 Spring AI 原生 Tool Calling 决定调不调工具。 */
        private boolean enabled = true;

        private Strategy strategy = Strategy.FORCE_EXEC;

        /**
         * FORCE_EXEC：命中即执行工具并跳过向量检索，最快，但错判时用户完全没有纠正机会。<br>
         * RECOMMEND_LLM：把命中结果作为「建议」写进提示词，最终由 LLM 决定，容错好但多一次往返。
         */
        public enum Strategy { FORCE_EXEC, RECOMMEND_LLM }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Strategy getStrategy() {
            return strategy;
        }

        public void setStrategy(Strategy strategy) {
            this.strategy = strategy;
        }
    }

    @PostConstruct
    public void logEffectiveValues() {
        log.info("🔧 Agent 配置: maxToolCalls={}, memorySize={}, 启发式路由={}, 策略={}",
                maxToolCalls, memorySize, heuristic.isEnabled(), heuristic.getStrategy());
    }

    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    public void setMaxToolCalls(int maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }

    public int getMemorySize() {
        return memorySize;
    }

    public void setMemorySize(int memorySize) {
        this.memorySize = memorySize;
    }

    public Heuristic getHeuristic() {
        return heuristic;
    }
}
