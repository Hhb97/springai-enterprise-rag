package com.example.ragassitant.infrastructure.observability;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 可观测性配置，对应 {@code application-observability.yml}。 */
@Data
@ConfigurationProperties(prefix = "observability")
public class ObservabilityProperties {

    private Trace trace = new Trace();

    @Data
    public static class Trace {
        /**
         * 异步写入。改造前每条 trace 都在请求线程上同步 INSERT（一次问答 3~6 条），
         * 数据库一慢，用户的问答就跟着慢。
         */
        private boolean async = true;
        /** 写入队列容量。满了丢弃并计数（agent.trace.dropped），绝不反压到请求线程。 */
        private int queueCapacity = 10_000;
        /** 批量写入的最大条数。 */
        private int batchSize = 50;
        /** 最长攒批时间（毫秒）。 */
        private long flushIntervalMs = 200;
        /** 保留天数。改造前没有任何清理，H2 文件只增不减（单条 llmInput 最长 2 万字符）。 */
        private int retentionDays = 30;
        /** 清理任务的 cron（默认每天 03:30）。 */
        private String cleanupCron = "0 30 3 * * *";
    }
}
