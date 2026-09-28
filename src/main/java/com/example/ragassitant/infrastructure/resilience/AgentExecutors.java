package com.example.ragassitant.infrastructure.resilience;

import com.example.ragassitant.infrastructure.observability.AgentMetrics;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 链路统一的有界线程池。
 *
 * <p>【改造前】ReAct / Plan / Graph 各自 {@code static newCachedThreadPool()} 起 SSE worker，
 * QueryRewrite 也是 cached pool —— 全部无界。100 个并发在等一个变慢的 LLM，就是 100+ 条线程；
 * 再多直接 OOM。{@code static} 字段还意味着 Spring 关闭时没人 shutdown 它们。
 *
 * <p>三个池按「满了之后怎么办」区分，而不是按服务区分：
 * <ul>
 *   <li><b>stream</b>：满了<b>拒绝</b>，由 {@code GlobalExceptionHandler} 转成 429。
 *       排长队没有意义 —— 用户早就放弃等了，还白占着内存；</li>
 *   <li><b>tool</b>：满了<b>由调用线程自己执行</b>，退化成串行但不失败 ——
 *       工具并行只是优化，不能因为优化资源不够就让整个请求失败；</li>
 *   <li><b>aux</b>：辅助任务（改写超时控制、影子路由），满了拒绝、调用方静默跳过。</li>
 * </ul>
 */
@Component
public class AgentExecutors {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutors.class);

    private final ThreadPoolExecutor stream;
    private final ThreadPoolExecutor tool;
    private final ThreadPoolExecutor aux;
    private final AgentMetrics metrics;

    public AgentExecutors(ResilienceProperties props, AgentMetrics metrics) {
        this.metrics = metrics;
        ResilienceProperties.Executors cfg = props.getExecutors();
        this.stream = build("agent-stream", cfg.getStream(), (r, ex) -> {
            metrics.rejected("pool_full");
            throw new RejectedExecutionException("服务繁忙：当前并发问答数已达上限，请稍后重试");
        });
        this.tool = build("agent-tool", cfg.getTool(), new ThreadPoolExecutor.CallerRunsPolicy());
        this.aux = build("agent-aux", cfg.getAux(), new ThreadPoolExecutor.AbortPolicy());
        log.info("🧵 Agent 线程池: stream={}~{}/q{}, tool={}~{}/q{}, aux={}~{}/q{}",
                cfg.getStream().getCoreSize(), cfg.getStream().getMaxSize(), cfg.getStream().getQueueCapacity(),
                cfg.getTool().getCoreSize(), cfg.getTool().getMaxSize(), cfg.getTool().getQueueCapacity(),
                cfg.getAux().getCoreSize(), cfg.getAux().getMaxSize(), cfg.getAux().getQueueCapacity());
    }

    /** 测试用：小容量、不依赖 Spring。 */
    public static AgentExecutors forTest() {
        return new AgentExecutors(new ResilienceProperties(), AgentMetrics.noop());
    }

    /** 与线程池一起下发给各 Agent 服务，省得每个服务的构造器再多一个参数。 */
    public AgentMetrics metrics() {
        return metrics;
    }

    /** SSE 流式 worker。满了抛 {@link RejectedExecutionException}。 */
    public ExecutorService stream() {
        return stream;
    }

    /** 工具并行执行。满了由调用线程执行。 */
    public ExecutorService tool() {
        return tool;
    }

    /** 辅助任务。满了抛 {@link RejectedExecutionException}，调用方应静默降级。 */
    public ExecutorService aux() {
        return aux;
    }

    /**
     * 把提交线程的 MDC（traceId 等）带进 worker 线程。
     *
     * <p>改造前 SSE worker 里只手动放了 sessionId，traceId 是丢的 —— 同一次请求在请求线程和
     * worker 线程打的日志 traceId 不一样（worker 里为空），按 traceId 串日志时后半段全断了。
     */
    public static Runnable withMdc(Runnable task) {
        java.util.Map<String, String> context = org.slf4j.MDC.getCopyOfContextMap();
        return () -> {
            java.util.Map<String, String> previous = org.slf4j.MDC.getCopyOfContextMap();
            if (context != null) org.slf4j.MDC.setContextMap(context);
            try {
                task.run();
            } finally {
                if (previous != null) org.slf4j.MDC.setContextMap(previous);
                else org.slf4j.MDC.clear();
            }
        };
    }

    public static <T> java.util.function.Supplier<T> withMdc(java.util.function.Supplier<T> task) {
        java.util.Map<String, String> context = org.slf4j.MDC.getCopyOfContextMap();
        return () -> {
            java.util.Map<String, String> previous = org.slf4j.MDC.getCopyOfContextMap();
            if (context != null) org.slf4j.MDC.setContextMap(context);
            try {
                return task.get();
            } finally {
                if (previous != null) org.slf4j.MDC.setContextMap(previous);
                else org.slf4j.MDC.clear();
            }
        };
    }

    /**
     * 通用的有界线程池构造，也给不经 Spring 注入的类用（避免它们再写一份 cached pool）。
     */
    public static ThreadPoolExecutor build(String name, ResilienceProperties.Pool cfg, RejectedExecutionHandler policy) {
        int core = Math.max(1, cfg.getCoreSize());
        int max = Math.max(core, cfg.getMaxSize());
        AtomicInteger seq = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                core, max, Math.max(1, cfg.getKeepAliveSeconds()), TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, cfg.getQueueCapacity())),
                r -> {
                    Thread t = new Thread(r, name + "-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                policy);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /** 优雅停机：给在途请求 10 秒收尾，而不是 JVM 退出时直接掐断。 */
    @PreDestroy
    public void shutdown() {
        for (ThreadPoolExecutor e : new ThreadPoolExecutor[]{stream, tool, aux}) {
            e.shutdown();
        }
        try {
            if (!stream.awaitTermination(10, TimeUnit.SECONDS)) stream.shutdownNow();
            tool.awaitTermination(2, TimeUnit.SECONDS);
            aux.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
