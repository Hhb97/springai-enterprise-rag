package com.example.ragassitant.infrastructure.resilience;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 韧性相关配置：线程池边界、限流、LLM 熔断与降级。对应 {@code application-resilience.yml}。
 *
 * <p>【为什么需要】改造前四个服务各自 {@code Executors.newCachedThreadPool()}（无界），
 * 每个 SSE 请求占一条线程，LLM 调用单步最多等 180s。后端一慢，线程数随并发线性增长直到 OOM；
 * 同时没有任何限流，也没有熔断 —— 后端持续故障时每个请求都会等满超时。
 */
@Data
@ConfigurationProperties(prefix = "resilience")
public class ResilienceProperties {

    private Executors executors = new Executors();
    private RateLimit rateLimit = new RateLimit();
    private Llm llm = new Llm();

    @Data
    public static class Executors {
        /** 流式请求 worker：一个 SSE 请求占一条。满了直接拒绝（→ 429），不排长队。 */
        private Pool stream = new Pool(4, 32, 16);
        /** 工具并行执行（Plan / Graph 波内并行）。满了由调用线程自己执行（降级为串行），不拒绝请求。 */
        private Pool tool = new Pool(4, 16, 64);
        /** 辅助任务：Query 改写超时、影子路由。满了丢弃，辅助任务不能挤占主流程。 */
        private Pool aux = new Pool(2, 8, 32);
    }

    @Data
    public static class Pool {
        private int coreSize;
        private int maxSize;
        private int queueCapacity;
        private long keepAliveSeconds = 60;

        public Pool() {}

        public Pool(int coreSize, int maxSize, int queueCapacity) {
            this.coreSize = coreSize;
            this.maxSize = maxSize;
            this.queueCapacity = queueCapacity;
        }
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;
        /** 单用户（登录用户名，未登录按 IP）每分钟允许的问答请求数。 */
        private int perUserPerMinute = 30;
        /** 令牌桶容量，允许的瞬时突发。 */
        private int burst = 10;
        /** 受限流保护的路径前缀（只限问答类接口，不限静态资源与管理查询）。 */
        private List<String> paths = new ArrayList<>(List.of("/ai/"));
        /** 豁免路径（健康检查等）。 */
        private List<String> excludePaths = new ArrayList<>(List.of("/ai/health"));
    }

    @Data
    public static class Llm {
        private CircuitBreaker circuitBreaker = new CircuitBreaker();
        private Fallback fallback = new Fallback();
    }

    @Data
    public static class CircuitBreaker {
        private boolean enabled = true;
        /** 统计最近多少次调用。 */
        private int windowSize = 20;
        /** 窗口内至少多少次调用才开始计算失败率（样本太少时不熔断）。 */
        private int minimumCalls = 5;
        /** 失败率达到多少（百分比）即熔断。 */
        private int failureRateThreshold = 50;
        /** 熔断后多久进入半开，放一次试探请求。 */
        private long openDurationMs = 30_000;
    }

    @Data
    public static class Fallback {
        /** 主模型失败或熔断时，是否降级到另一个后端（dashscope ↔ ollama）。 */
        private boolean enabled = true;
        /**
         * 工具调用链路是否允许降级。默认关闭：本地小模型（如 qwen2.5:1.5b）基本不支持原生 tool calling，
         * 降级过去只会得到「把工具调用写成文本」的错答，不如明确失败。
         */
        private boolean forToolCalling = false;
    }
}
