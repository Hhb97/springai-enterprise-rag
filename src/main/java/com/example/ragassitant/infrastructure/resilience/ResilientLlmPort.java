package com.example.ragassitant.infrastructure.resilience;

import com.example.ragassitant.domain.spi.LlmPort;
import com.example.ragassitant.infrastructure.agent.ToolBudgetExceededException;
import com.example.ragassitant.infrastructure.observability.AgentMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 给 {@link LlmPort} 套上熔断 + 降级的装饰器。所有注入给业务层的 LlmPort 都是它。
 *
 * <p>分层职责（刻意只在一层做一件事，避免重试次数相乘）：
 * <ul>
 *   <li><b>HTTP 超时</b>：{@code spring.http.clients.*}，由底层客户端真正断开连接；</li>
 *   <li><b>重试</b>：{@code spring.ai.retry.*}，Spring AI 自带，只对 5xx/429 等瞬时错误生效；</li>
 *   <li><b>熔断 + 降级</b>：本类。持续故障时快速失败，并切到备用后端。</li>
 * </ul>
 *
 * <p>失败判定：只有「后端故障」计入熔断失败率。工具预算耗尽（{@link ToolBudgetExceededException}）
 * 和调用方主动取消（中断）是业务控制流，不能把熔断器打开 —— 否则一个需要很多次工具调用的问题
 * 就能让所有人都走降级。
 *
 * <p>对外保持 {@link LlmPort} 的约定：同步方法最终失败返回 null，不抛异常。
 */
public class ResilientLlmPort implements LlmPort {

    private static final Logger log = LoggerFactory.getLogger(ResilientLlmPort.class);

    private final String name;
    private final LlmPort delegate;
    private final LlmPort fallback;
    private final boolean fallbackForToolCalling;
    private final CircuitBreaker breaker;
    private final AgentMetrics metrics;

    /**
     * @param fallback 备用后端，可为 null（不降级）
     */
    public ResilientLlmPort(String name, LlmPort delegate, LlmPort fallback,
                            boolean fallbackForToolCalling, CircuitBreaker breaker, AgentMetrics metrics) {
        this.name = name;
        this.delegate = delegate;
        this.fallback = fallback;
        this.fallbackForToolCalling = fallbackForToolCalling;
        this.breaker = breaker;
        this.metrics = metrics;
    }

    public String name() {
        return name;
    }

    public CircuitBreaker.State breakerState() {
        return breaker.state();
    }

    public boolean hasFallback() {
        return fallback != null;
    }

    @Override
    public String chat(List<String> systemPrompts, List<String> historyUser, List<String> historyAssistant, String currentUser) {
        return call("chat",
                () -> delegate.chat(systemPrompts, historyUser, historyAssistant, currentUser),
                fallback == null ? null : () -> fallback.chat(systemPrompts, historyUser, historyAssistant, currentUser));
    }

    @Override
    public String chatWithRawMessages(List<MessageDTO> messages) {
        return call("raw",
                () -> delegate.chatWithRawMessages(messages),
                fallback == null ? null : () -> fallback.chatWithRawMessages(messages));
    }

    @Override
    public String chatWithAutoToolCalling(List<MessageDTO> messages, int maxToolCalls, List<Object> toolBeans) {
        Supplier<String> fb = (fallback != null && fallbackForToolCalling)
                ? () -> fallback.chatWithAutoToolCalling(messages, maxToolCalls, toolBeans)
                : null;
        return call("tool_calling", () -> delegate.chatWithAutoToolCalling(messages, maxToolCalls, toolBeans), fb);
    }

    @Override
    public <T> Structured<T> chatStructured(List<MessageDTO> messages, Class<T> type) {
        Structured<T> result = call("structured",
                () -> delegate.chatStructured(messages, type),
                fallback == null ? null : () -> fallback.chatStructured(messages, type));
        return result == null ? Structured.failed() : result;
    }

    @Override
    public Flux<String> chatStreamWithRawMessages(List<MessageDTO> messages) {
        if (!breaker.tryAcquire()) {
            metrics.llmCall(name, "rejected");
            log.warn("⚡ [{}] 熔断中，流式调用直接走降级", name);
            return fallback != null ? fallbackStream(messages) : Flux.error(openError());
        }
        AtomicBoolean emitted = new AtomicBoolean(false);
        return Flux.defer(() -> delegate.chatStreamWithRawMessages(messages))
                .doOnNext(t -> emitted.set(true))
                .doOnComplete(() -> {
                    breaker.onSuccess();
                    metrics.llmCall(name, "success");
                })
                .onErrorResume(e -> {
                    if (isBackendFailure(e)) breaker.onFailure();
                    metrics.llmCall(name, "failure");
                    // 已经吐出过内容就不能再切换 —— 前端会看到两个模型拼在一起的回答
                    if (!emitted.get() && fallback != null && isBackendFailure(e)) {
                        log.warn("⚡ [{}] 流式调用失败（尚未输出），降级到备用后端: {}", name, e.toString());
                        return fallbackStream(messages);
                    }
                    return Flux.error(e);
                });
    }

    // ==================== 内部 ====================

    private Flux<String> fallbackStream(List<MessageDTO> messages) {
        metrics.llmCall(name, "fallback");
        metrics.fallbackTriggered("llm_fallback");
        return Flux.defer(() -> fallback.chatStreamWithRawMessages(messages));
    }

    private <T> T call(String op, Supplier<T> primary, Supplier<T> fb) {
        if (!breaker.tryAcquire()) {
            metrics.llmCall(name, "rejected");
            log.warn("⚡ [{}] 熔断中（{}），跳过主后端", name, op);
            return runFallback(op, fb);
        }
        try {
            T result = primary.get();
            breaker.onSuccess();
            metrics.llmCall(name, "success");
            return result;
        } catch (ToolBudgetExceededException e) {
            // 业务控制流：不计入熔断，不降级
            breaker.onSuccess();
            log.warn("🛑 [{}] {}", name, e.getMessage());
            return null;
        } catch (RuntimeException e) {
            if (!isBackendFailure(e)) {
                // 调用方取消（如 Query 改写超时）：恢复中断标志，不计入熔断
                if (isInterrupt(e)) Thread.currentThread().interrupt();
                return null;
            }
            breaker.onFailure();
            metrics.llmCall(name, "failure");
            log.error("❌ [{}] LLM 调用失败（{}），熔断器状态={}: {}", name, op, breaker.state(), e.toString());
            return runFallback(op, fb);
        }
    }

    private <T> T runFallback(String op, Supplier<T> fb) {
        if (fb == null) return null;
        metrics.llmCall(name, "fallback");
        metrics.fallbackTriggered("llm_fallback");
        try {
            log.warn("↪️ [{}] 降级到备用后端（{}）", name, op);
            return fb.get();
        } catch (RuntimeException e) {
            log.error("❌ [{}] 备用后端也失败（{}）: {}", name, op, e.toString());
            return null;
        }
    }

    private IllegalStateException openError() {
        return new IllegalStateException("模型服务暂不可用（熔断中），请稍后重试");
    }

    static boolean isBackendFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ToolBudgetExceededException) return false;
            if (t instanceof InterruptedException) return false;
            if (t.getCause() == t) break;
        }
        return true;
    }

    private static boolean isInterrupt(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }
}
