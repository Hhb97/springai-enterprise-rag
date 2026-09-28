package com.example.ragassitant.common.filter;

import com.example.ragassitant.infrastructure.observability.AgentMetrics;
import com.example.ragassitant.infrastructure.resilience.ResilienceProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 按用户的令牌桶限流，只作用于问答类接口（{@code resilience.rate-limit.paths}）。
 *
 * <p>每次问答都是若干次 LLM 调用（改写、影子路由、规划、总结），单个用户脚本刷接口就能把
 * 模型配额和线程池一起打满。限流按<b>登录用户名</b>计，未登录按客户端 IP。
 *
 * <p>全局并发上限不在这里做：SSE 请求在过滤器返回后仍在后台 worker 里跑，
 * 过滤器层的信号量拦不住，真正的并发边界是 {@code AgentExecutors.stream()} 的有界线程池。
 *
 * <p>排在 {@link BasicAuthFilter}（Order 1）之后，这样能拿到已认证的用户名。
 */
@Component
@Order(2)
public class RateLimitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION_USER_KEY = "authenticated_user";
    /** 超过这个时长没请求的桶会被清理，防止按 IP 计的桶无限增长。 */
    private static final long IDLE_EVICT_MS = 10 * 60_000L;

    private final ResilienceProperties.RateLimit cfg;
    private final AgentMetrics metrics;
    private final LongSupplier clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong requestCounter = new AtomicLong();

    @Autowired
    public RateLimitFilter(ResilienceProperties props, AgentMetrics metrics) {
        this(props.getRateLimit(), metrics, System::currentTimeMillis);
    }

    RateLimitFilter(ResilienceProperties.RateLimit cfg, AgentMetrics metrics, LongSupplier clock) {
        this.cfg = cfg;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        if (!cfg.isEnabled() || !isLimited(req.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String key = clientKey(req);
        if (tryAcquire(key)) {
            chain.doFilter(request, response);
            return;
        }

        metrics.rejected("rate_limited");
        log.warn("🚦 限流拦截: key={}, path={}", key, req.getRequestURI());
        HttpServletResponse resp = (HttpServletResponse) response;
        resp.setStatus(429);
        resp.setHeader("Retry-After", String.valueOf(retryAfterSeconds()));
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getWriter().write(JSON.writeValueAsString(Map.of(
                "success", false,
                "code", "RATE_LIMITED",
                "message", "请求过于频繁，请稍后再试")));
    }

    boolean isLimited(String path) {
        if (path == null) return false;
        for (String ex : cfg.getExcludePaths()) {
            if (path.startsWith(ex)) return false;
        }
        for (String p : cfg.getPaths()) {
            if (path.startsWith(p)) return true;
        }
        return false;
    }

    boolean tryAcquire(String key) {
        long now = clock.getAsLong();
        if (requestCounter.incrementAndGet() % 1000 == 0) evictIdle(now);
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity(), now));
        return bucket.tryTake(now, capacity(), refillPerMs());
    }

    private String clientKey(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session != null) {
            Object user = session.getAttribute(SESSION_USER_KEY);
            if (user != null) return "user:" + user;
        }
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return "ip:" + forwarded.split(",")[0].trim();
        }
        return "ip:" + req.getRemoteAddr();
    }

    private int capacity() {
        return Math.max(1, cfg.getBurst());
    }

    private double refillPerMs() {
        return Math.max(1, cfg.getPerUserPerMinute()) / 60_000.0;
    }

    private long retryAfterSeconds() {
        return Math.max(1, (long) Math.ceil(1 / (refillPerMs() * 1000)));
    }

    private void evictIdle(long now) {
        buckets.entrySet().removeIf(e -> now - e.getValue().lastAccess > IDLE_EVICT_MS);
    }

    /** 令牌桶：按经过时间连续补充，容量上限即突发量。 */
    private static final class Bucket {
        private double tokens;
        private long lastRefill;
        private volatile long lastAccess;

        Bucket(int capacity, long now) {
            this.tokens = capacity;
            this.lastRefill = now;
            this.lastAccess = now;
        }

        synchronized boolean tryTake(long now, int capacity, double refillPerMs) {
            lastAccess = now;
            long elapsed = Math.max(0, now - lastRefill);
            tokens = Math.min(capacity, tokens + elapsed * refillPerMs);
            lastRefill = now;
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
    }
}
