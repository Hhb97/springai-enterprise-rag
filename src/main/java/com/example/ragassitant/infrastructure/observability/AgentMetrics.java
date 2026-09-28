package com.example.ragassitant.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Agent 链路的聚合指标（Micrometer），暴露在 {@code /actuator/metrics}。
 *
 * <p>【为什么需要】AgentTrace 有逐请求明细，但没有聚合视角：7.6 秒的冗余总结轮是我们手工对着
 * 日志时间戳算出来的；模型换版本后原生 tool_calls 通道失效，是用户截图才发现的。
 * 这些都应该是一眼能看到的曲线。
 *
 * <p>指标命名统一 {@code agent.*} 前缀，标签控制在低基数（阶段名、模式、工具名、结果），
 * 绝不放 sessionId / 问题文本这类高基数值 —— 那会把指标系统撑爆。
 */
@Component
public class AgentMetrics {

    private final MeterRegistry registry;

    @Autowired
    public AgentMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this(registryProvider.getIfAvailable(SimpleMeterRegistry::new));
    }

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 测试/无容器场景用。 */
    public static AgentMetrics noop() {
        return new AgentMetrics(new SimpleMeterRegistry());
    }

    public MeterRegistry registry() {
        return registry;
    }

    // ==================== 延迟 ====================

    /** 各阶段耗时：rewrite / retrieval / planner / tool / solver / llm / total。 */
    public void recordStage(String stage, String mode, long millis) {
        if (millis < 0) return;
        Timer.builder("agent.stage.duration")
                .description("Agent 各阶段耗时")
                .tag("stage", safe(stage))
                .tag("mode", safe(mode))
                .publishPercentiles(0.5, 0.95)
                .register(registry)
                .record(Duration.ofMillis(millis));
    }

    public <T> T timeStage(String stage, String mode, Supplier<T> body) {
        long start = System.currentTimeMillis();
        try {
            return body.get();
        } finally {
            recordStage(stage, mode, System.currentTimeMillis() - start);
        }
    }

    // ==================== LLM ====================

    /** LLM 调用结果：outcome = success / failure / fallback / rejected(熔断拒绝)。 */
    public void llmCall(String port, String outcome) {
        counter("agent.llm.calls", "port", port, "outcome", outcome).increment();
    }

    public void tokens(String model, String type, long count) {
        if (count <= 0) return;
        DistributionSummary.builder("agent.llm.tokens")
                .description("每次 LLM 调用的 token 数")
                .tag("model", safe(model))
                .tag("type", safe(type))
                .register(registry)
                .record(count);
    }

    // ==================== 工具 ====================

    /** source = native / forced / text_fallback / plan / react；outcome = ok / error / denied / pending_approval。 */
    public void toolCall(String tool, String source, String outcome) {
        counter("agent.tool.calls", "tool", tool, "source", source, "outcome", outcome).increment();
    }

    // ==================== 质量信号 ====================

    /** 兜底触发：text_tool_call（模型把调用写成文本）/ meaningless_answer / llm_fallback 等。 */
    public void fallbackTriggered(String kind) {
        counter("agent.fallback.triggered", "kind", kind).increment();
    }

    /** 拒答（知识库无相关信息）。 */
    public void refusal(String mode) {
        counter("agent.answer.refusal", "mode", mode).increment();
    }

    /** 检索空结果（相似度全部低于阈值）。召回问题的早期信号。 */
    public void retrievalEmpty(String mode) {
        counter("agent.retrieval.empty", "mode", mode).increment();
    }

    public void retrievalChunks(String mode, int count) {
        DistributionSummary.builder("agent.retrieval.chunks")
                .tag("mode", safe(mode))
                .register(registry)
                .record(count);
    }

    /** 请求被拒：rate_limited / pool_full。 */
    public void rejected(String reason) {
        counter("agent.request.rejected", "reason", reason).increment();
    }

    /** 用户反馈：up / down。 */
    public void feedback(String rating) {
        counter("agent.feedback", "rating", rating).increment();
    }

    public void traceDropped() {
        counter("agent.trace.dropped").increment();
    }

    // ==================== 内部 ====================

    private Counter counter(String name, String... tags) {
        String[] safeTags = new String[tags.length];
        for (int i = 0; i < tags.length; i++) safeTags[i] = safe(tags[i]);
        return Counter.builder(name).tags(safeTags).register(registry);
    }

    private static String safe(String v) {
        return v == null || v.isBlank() ? "unknown" : v;
    }
}
