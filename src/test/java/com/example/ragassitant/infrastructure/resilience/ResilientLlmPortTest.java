package com.example.ragassitant.infrastructure.resilience;

import com.example.ragassitant.domain.spi.LlmPort;
import com.example.ragassitant.infrastructure.agent.ToolBudgetExceededException;
import com.example.ragassitant.infrastructure.observability.AgentMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 熔断 + 降级装饰器：业务层拿到的所有 LlmPort 都是它。
 */
class ResilientLlmPortTest {

    /** 可编程桩：raw 调用行为由 supplier 决定，并统计调用次数。 */
    static class ScriptedLlm implements LlmPort {
        final AtomicInteger calls = new AtomicInteger();
        Supplier<String> raw = () -> "ok";
        Supplier<Flux<String>> stream = () -> Flux.just("a", "b");

        @Override
        public String chat(List<String> s, List<String> hu, List<String> ha, String cu) {
            return chatWithRawMessages(List.of());
        }

        @Override
        public String chatWithRawMessages(List<MessageDTO> messages) {
            calls.incrementAndGet();
            return raw.get();
        }

        @Override
        public Flux<String> chatStreamWithRawMessages(List<MessageDTO> messages) {
            calls.incrementAndGet();
            return stream.get();
        }

        @Override
        public String chatWithAutoToolCalling(List<MessageDTO> m, int max, List<Object> tools) {
            calls.incrementAndGet();
            return raw.get();
        }
    }

    private static CircuitBreaker breaker() {
        ResilienceProperties.CircuitBreaker cfg = new ResilienceProperties.CircuitBreaker();
        cfg.setWindowSize(4);
        cfg.setMinimumCalls(2);
        cfg.setFailureRateThreshold(50);
        cfg.setOpenDurationMs(60_000);
        return new CircuitBreaker("t", cfg);
    }

    private static final List<LlmPort.MessageDTO> MSGS = List.of(
            new LlmPort.MessageDTO(LlmPort.MessageDTO.Role.USER, "hi"));

    @Test
    @DisplayName("主后端正常时直接返回，不碰备用后端")
    void primarySuccess() {
        ScriptedLlm primary = new ScriptedLlm();
        ScriptedLlm fallback = new ScriptedLlm();
        ResilientLlmPort port = new ResilientLlmPort("p", primary, fallback, false, breaker(), AgentMetrics.noop());

        assertEquals("ok", port.chatWithRawMessages(MSGS));
        assertEquals(0, fallback.calls.get());
    }

    @Test
    @DisplayName("主后端抛异常 → 降级到备用后端，对业务层不抛异常")
    void fallsBackOnFailure() {
        ScriptedLlm primary = new ScriptedLlm();
        primary.raw = () -> { throw new RuntimeException("503"); };
        ScriptedLlm fallback = new ScriptedLlm();
        fallback.raw = () -> "from-fallback";
        ResilientLlmPort port = new ResilientLlmPort("p", primary, fallback, false, breaker(), AgentMetrics.noop());

        assertEquals("from-fallback", assertDoesNotThrow(() -> port.chatWithRawMessages(MSGS)));
    }

    @Test
    @DisplayName("没有备用后端时最终失败返回 null（保持 LlmPort 原有约定）")
    void returnsNullWithoutFallback() {
        ScriptedLlm primary = new ScriptedLlm();
        primary.raw = () -> { throw new RuntimeException("timeout"); };
        ResilientLlmPort port = new ResilientLlmPort("p", primary, null, false, breaker(), AgentMetrics.noop());

        assertNull(port.chatWithRawMessages(MSGS));
    }

    @Test
    @DisplayName("熔断打开后不再打主后端，直接走降级")
    void openBreakerSkipsPrimary() {
        ScriptedLlm primary = new ScriptedLlm();
        primary.raw = () -> { throw new RuntimeException("down"); };
        ScriptedLlm fallback = new ScriptedLlm();
        fallback.raw = () -> "fb";
        ResilientLlmPort port = new ResilientLlmPort("p", primary, fallback, false, breaker(), AgentMetrics.noop());

        port.chatWithRawMessages(MSGS);
        port.chatWithRawMessages(MSGS);   // 2 次失败 → 熔断
        assertEquals(CircuitBreaker.State.OPEN, port.breakerState());

        int before = primary.calls.get();
        assertEquals("fb", port.chatWithRawMessages(MSGS));
        assertEquals(before, primary.calls.get(), "熔断期间仍在调用主后端");
    }

    @Test
    @DisplayName("工具预算耗尽是业务控制流：不计入熔断、不降级")
    void toolBudgetDoesNotTripBreaker() {
        ScriptedLlm primary = new ScriptedLlm();
        primary.raw = () -> { throw new ToolBudgetExceededException("budget"); };
        ScriptedLlm fallback = new ScriptedLlm();
        ResilientLlmPort port = new ResilientLlmPort("p", primary, fallback, true, breaker(), AgentMetrics.noop());

        for (int i = 0; i < 5; i++) port.chatWithAutoToolCalling(MSGS, 3, List.of());

        assertEquals(CircuitBreaker.State.CLOSED, port.breakerState(),
                "一个需要很多次工具调用的问题把熔断器打开了，后续所有人都会走降级");
        assertEquals(0, fallback.calls.get());
    }

    @Test
    @DisplayName("工具调用链路默认不降级（本地小模型不支持原生 tool calling）")
    void toolCallingDoesNotFallBackByDefault() {
        ScriptedLlm primary = new ScriptedLlm();
        primary.raw = () -> { throw new RuntimeException("503"); };
        ScriptedLlm fallback = new ScriptedLlm();
        ResilientLlmPort port = new ResilientLlmPort("p", primary, fallback, false, breaker(), AgentMetrics.noop());

        assertNull(port.chatWithAutoToolCalling(MSGS, 3, List.of()));
        assertEquals(0, fallback.calls.get());
    }

    @Test
    @DisplayName("流式：尚未输出就失败 → 切到备用后端")
    void streamFallsBackBeforeFirstToken() {
        ScriptedLlm primary = new ScriptedLlm();
        primary.stream = () -> Flux.error(new RuntimeException("connect refused"));
        ScriptedLlm fallback = new ScriptedLlm();
        fallback.stream = () -> Flux.just("x", "y");
        ResilientLlmPort port = new ResilientLlmPort("p", primary, fallback, false, breaker(), AgentMetrics.noop());

        assertEquals(List.of("x", "y"), port.chatStreamWithRawMessages(MSGS).collectList().block());
    }

    @Test
    @DisplayName("流式：已经输出过内容再失败 → 不切换（否则用户看到两个模型拼起来的回答）")
    void streamDoesNotSwitchAfterEmitting() {
        ScriptedLlm primary = new ScriptedLlm();
        primary.stream = () -> Flux.concat(Flux.just("half"), Flux.error(new RuntimeException("reset")));
        ScriptedLlm fallback = new ScriptedLlm();
        fallback.stream = () -> Flux.just("other");
        ResilientLlmPort port = new ResilientLlmPort("p", primary, fallback, false, breaker(), AgentMetrics.noop());

        assertThrows(RuntimeException.class, () -> port.chatStreamWithRawMessages(MSGS).collectList().block());
        assertEquals(0, fallback.calls.get());
    }
}
