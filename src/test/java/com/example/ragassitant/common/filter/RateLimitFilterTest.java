package com.example.ragassitant.common.filter;

import com.example.ragassitant.infrastructure.observability.AgentMetrics;
import com.example.ragassitant.infrastructure.resilience.ResilienceProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitFilterTest {

    private final AtomicLong now = new AtomicLong(0);

    private RateLimitFilter filter(int perMinute, int burst) {
        ResilienceProperties.RateLimit cfg = new ResilienceProperties.RateLimit();
        cfg.setPerUserPerMinute(perMinute);
        cfg.setBurst(burst);
        return new RateLimitFilter(cfg, AgentMetrics.noop(), now::get);
    }

    @Test
    @DisplayName("突发量内放行，超出即拒绝")
    void allowsBurstThenRejects() {
        RateLimitFilter f = filter(60, 3);
        assertTrue(f.tryAcquire("u"));
        assertTrue(f.tryAcquire("u"));
        assertTrue(f.tryAcquire("u"));
        assertFalse(f.tryAcquire("u"), "超出突发量仍在放行");
    }

    @Test
    @DisplayName("令牌按时间补充：60 次/分钟 → 每秒补 1 个")
    void refillsOverTime() {
        RateLimitFilter f = filter(60, 1);
        assertTrue(f.tryAcquire("u"));
        assertFalse(f.tryAcquire("u"));
        now.addAndGet(1000);
        assertTrue(f.tryAcquire("u"));
    }

    @Test
    @DisplayName("不同用户互不影响")
    void bucketsArePerUser() {
        RateLimitFilter f = filter(60, 1);
        assertTrue(f.tryAcquire("a"));
        assertFalse(f.tryAcquire("a"));
        assertTrue(f.tryAcquire("b"));
    }

    @Test
    @DisplayName("只限流问答接口，健康检查和静态资源不限")
    void onlyLimitsConfiguredPaths() {
        RateLimitFilter f = filter(60, 1);
        assertTrue(f.isLimited("/ai/agent-chat"));
        assertTrue(f.isLimited("/ai/react-agent-chat-stream"));
        assertFalse(f.isLimited("/ai/health"));
        assertFalse(f.isLimited("/index.html"));
        assertFalse(f.isLimited("/api/agent/traces/recent"));
    }
}
