package com.example.ragassitant.infrastructure.resilience;

import java.util.function.LongSupplier;

/**
 * 基于滑动窗口失败率的熔断器（CLOSED → OPEN → HALF_OPEN → CLOSED）。
 *
 * <p>为什么手写而不是 Resilience4j：本项目离线构建，本地仓库只有 resilience4j 的 BOM、没有实现 jar。
 * 这里只实现本项目需要的最小语义，接口刻意与 Resilience4j 的概念对齐，
 * 以后能联网时替换成它不影响调用方。
 *
 * <p>语义：
 * <ul>
 *   <li>CLOSED：正常放行，记录最近 {@code windowSize} 次结果；样本数 ≥ {@code minimumCalls}
 *       且失败率 ≥ 阈值时转 OPEN；</li>
 *   <li>OPEN：直接拒绝（调用方走降级），持续 {@code openDurationMs}；</li>
 *   <li>HALF_OPEN：只放<b>一个</b>试探请求，成功回 CLOSED 并清空窗口，失败回 OPEN 重新计时。</li>
 * </ul>
 */
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final boolean enabled;
    private final int windowSize;
    private final int minimumCalls;
    private final int failureRateThreshold;
    private final long openDurationMs;
    private final LongSupplier clock;

    /** 环形缓冲：true = 失败。 */
    private final boolean[] window;
    private int windowPos;
    private int windowCount;
    private int failures;

    private State state = State.CLOSED;
    private long openedAt;
    private boolean halfOpenTrialInFlight;

    public CircuitBreaker(String name, ResilienceProperties.CircuitBreaker cfg) {
        this(name, cfg, System::currentTimeMillis);
    }

    public CircuitBreaker(String name, ResilienceProperties.CircuitBreaker cfg, LongSupplier clock) {
        this.name = name;
        this.enabled = cfg.isEnabled();
        this.windowSize = Math.max(1, cfg.getWindowSize());
        this.minimumCalls = Math.max(1, Math.min(cfg.getMinimumCalls(), this.windowSize));
        this.failureRateThreshold = Math.max(1, Math.min(100, cfg.getFailureRateThreshold()));
        this.openDurationMs = Math.max(0, cfg.getOpenDurationMs());
        this.clock = clock;
        this.window = new boolean[this.windowSize];
    }

    public String name() {
        return name;
    }

    /** 是否允许本次调用。返回 false 时调用方应直接走降级，不要再打后端。 */
    public synchronized boolean tryAcquire() {
        if (!enabled) return true;
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (clock.getAsLong() - openedAt >= openDurationMs) {
                    state = State.HALF_OPEN;
                    halfOpenTrialInFlight = true;
                    return true;
                }
                return false;
            case HALF_OPEN:
            default:
                if (halfOpenTrialInFlight) return false;
                halfOpenTrialInFlight = true;
                return true;
        }
    }

    public synchronized void onSuccess() {
        if (!enabled) return;
        if (state == State.HALF_OPEN) {
            state = State.CLOSED;
            halfOpenTrialInFlight = false;
            resetWindow();
            return;
        }
        record(false);
    }

    public synchronized void onFailure() {
        if (!enabled) return;
        if (state == State.HALF_OPEN) {
            trip();
            return;
        }
        record(true);
        if (state == State.CLOSED && windowCount >= minimumCalls
                && failures * 100 >= failureRateThreshold * windowCount) {
            trip();
        }
    }

    public synchronized State state() {
        // OPEN 到期但还没有请求触发状态迁移时，对外如实报告仍是 OPEN
        return state;
    }

    private void trip() {
        state = State.OPEN;
        openedAt = clock.getAsLong();
        halfOpenTrialInFlight = false;
    }

    private void record(boolean failed) {
        if (windowCount == windowSize) {
            if (window[windowPos]) failures--;
        } else {
            windowCount++;
        }
        window[windowPos] = failed;
        if (failed) failures++;
        windowPos = (windowPos + 1) % windowSize;
    }

    private void resetWindow() {
        windowPos = 0;
        windowCount = 0;
        failures = 0;
        java.util.Arrays.fill(window, false);
    }
}
