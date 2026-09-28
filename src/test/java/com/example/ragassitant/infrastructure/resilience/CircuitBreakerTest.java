package com.example.ragassitant.infrastructure.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {

    private final AtomicLong now = new AtomicLong(0);

    private CircuitBreaker breaker(int window, int minCalls, int threshold, long openMs) {
        ResilienceProperties.CircuitBreaker cfg = new ResilienceProperties.CircuitBreaker();
        cfg.setWindowSize(window);
        cfg.setMinimumCalls(minCalls);
        cfg.setFailureRateThreshold(threshold);
        cfg.setOpenDurationMs(openMs);
        return new CircuitBreaker("test", cfg, now::get);
    }

    @Test
    @DisplayName("样本数不足 minimumCalls 时不熔断")
    void doesNotTripBelowMinimumCalls() {
        CircuitBreaker cb = breaker(10, 5, 50, 1000);
        for (int i = 0; i < 4; i++) cb.onFailure();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertTrue(cb.tryAcquire());
    }

    @Test
    @DisplayName("失败率达到阈值即熔断，熔断期间拒绝调用")
    void tripsAtThreshold() {
        CircuitBreaker cb = breaker(10, 4, 50, 1000);
        cb.onSuccess();
        cb.onSuccess();
        cb.onFailure();
        cb.onFailure();   // 4 次中 2 次失败 = 50%
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        assertFalse(cb.tryAcquire(), "熔断期间仍在放行，后端故障时每个请求都会等满超时");
    }

    @Test
    @DisplayName("熔断时长过后进入半开，只放一个试探请求")
    void halfOpenAllowsSingleTrial() {
        CircuitBreaker cb = breaker(4, 2, 50, 1000);
        cb.onFailure();
        cb.onFailure();
        now.set(1000);
        assertTrue(cb.tryAcquire(), "到期后应放行试探请求");
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
        assertFalse(cb.tryAcquire(), "半开期间只能有一个试探请求在途");
    }

    @Test
    @DisplayName("半开试探成功 → 关闭并清空窗口")
    void halfOpenSuccessCloses() {
        CircuitBreaker cb = breaker(4, 2, 50, 1000);
        cb.onFailure();
        cb.onFailure();
        now.set(1000);
        cb.tryAcquire();
        cb.onSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        cb.onFailure();   // 窗口已清空：单次失败不应立刻再次熔断
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    }

    @Test
    @DisplayName("半开试探失败 → 重新熔断并重新计时")
    void halfOpenFailureReopens() {
        CircuitBreaker cb = breaker(4, 2, 50, 1000);
        cb.onFailure();
        cb.onFailure();
        now.set(1000);
        cb.tryAcquire();
        cb.onFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        now.set(1500);
        assertFalse(cb.tryAcquire(), "重新熔断后应重新计时");
    }

    @Test
    @DisplayName("滑动窗口：旧失败被挤出后失败率随之下降")
    void slidingWindowEvictsOldResults() {
        CircuitBreaker cb = breaker(4, 4, 75, 1000);
        cb.onFailure();
        cb.onFailure();
        cb.onSuccess();
        cb.onSuccess();   // 2/4 = 50% < 75%
        cb.onSuccess();   // 挤掉第一个失败 → 1/4
        cb.onFailure();   // 2/4
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    }

    @Test
    @DisplayName("关闭开关后永远放行")
    void disabledAlwaysPermits() {
        ResilienceProperties.CircuitBreaker cfg = new ResilienceProperties.CircuitBreaker();
        cfg.setEnabled(false);
        CircuitBreaker cb = new CircuitBreaker("off", cfg, now::get);
        for (int i = 0; i < 50; i++) cb.onFailure();
        assertTrue(cb.tryAcquire());
    }
}
