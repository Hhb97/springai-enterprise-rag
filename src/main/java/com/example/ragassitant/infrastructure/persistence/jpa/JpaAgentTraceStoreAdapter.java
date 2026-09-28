package com.example.ragassitant.infrastructure.persistence.jpa;

import com.example.ragassitant.domain.spi.AgentTraceStorePort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.ragassitant.infrastructure.observability.AgentMetrics;
import com.example.ragassitant.infrastructure.observability.ObservabilityProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

@Component
public class JpaAgentTraceStoreAdapter implements AgentTraceStorePort {

    private static final Logger log = LoggerFactory.getLogger(JpaAgentTraceStoreAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一致率可解读的经验样本下限（不是统计学阈值，只是防止拿 n=1 下结论）。 */
    private static final int MIN_SAMPLES_FOR_CONCLUSION = 30;

    private final AgentTraceJpaRepository repository;
    private final ObservabilityProperties.Trace cfg;
    private final AgentMetrics metrics;

    /**
     * 异步写入队列。改造前每条 trace 在请求线程上同步 INSERT，一次问答 3~6 条；
     * 现在请求线程只负责入队（O(1)），由单个后台线程批量落库。队列满了丢弃并计数，绝不反压请求。
     */
    private final BlockingQueue<AgentTraceJpaEntity> queue;
    private final Thread writer;
    private volatile boolean running = true;

    public JpaAgentTraceStoreAdapter(AgentTraceJpaRepository repository,
                                     ObservabilityProperties props,
                                     AgentMetrics metrics) {
        this.repository = repository;
        this.cfg = props.getTrace();
        this.metrics = metrics;
        this.queue = new ArrayBlockingQueue<>(Math.max(100, cfg.getQueueCapacity()));
        if (cfg.isAsync()) {
            this.writer = new Thread(this::writeLoop, "agent-trace-writer");
            this.writer.setDaemon(true);
            this.writer.start();
        } else {
            this.writer = null;
        }
        log.info("✅ JpaAgentTraceStoreAdapter 初始化（决策链路持久化，异步={}，保留 {} 天）",
                cfg.isAsync(), cfg.getRetentionDays());
    }

    @Override
    public void recordDecision(String sessionId, int roundNo, String stage,
                               String toolName, Map<String, Object> toolArguments, String toolResult,
                               String llmInput, String llmOutput,
                               boolean heuristicHit, String heuristicStrategy,
                               Long durationMs, String status, String errorMessage) {
        try {
            AgentTraceJpaEntity e = new AgentTraceJpaEntity();
            e.setSessionId(sessionId);
            e.setTraceId(currentTraceId());
            e.setRoundNo(roundNo);
            e.setStage(stage);
            e.setToolName(toolName);
            e.setToolArguments(toJson(toolArguments));
            e.setToolResult(truncate(toolResult, 10000));
            e.setLlmInput(truncate(llmInput, 20000));
            e.setLlmOutput(truncate(llmOutput, 10000));
            e.setHeuristicHit(heuristicHit);
            e.setHeuristicStrategy(heuristicStrategy);
            e.setDurationMs(durationMs);
            e.setStatus(status);
            e.setErrorMessage(errorMessage);
            // 在事件发生时记录时间，保证同一请求内各阶段的先后顺序不被批量写入抹平
            e.setCreatedAt(LocalDateTime.now());
            if (writer == null) {
                repository.save(e);
            } else if (!queue.offer(e)) {
                metrics.traceDropped();
                log.debug("trace 写入队列已满，丢弃一条: stage={}", stage);
            }
        } catch (Exception ex) {
            log.warn("⚠️ 保存决策链路失败，不影响主流程: {}", ex.getMessage());
        }
    }

    @Override
    public List<Map<String, Object>> queryBySession(String sessionId) {
        List<AgentTraceJpaEntity> list = repository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        return convert(list);
    }

    @Override
    public List<Map<String, Object>> queryRecent(int limit) {
        List<AgentTraceJpaEntity> list = repository.findByCreatedAtAfterOrderByCreatedAtDesc(
                LocalDateTime.now().minusDays(7), PageRequest.of(0, Math.max(1, limit)));
        return convert(list);
    }

    @Override
    public long countSince(LocalDateTime since) {
        return repository.countByCreatedAtAfter(since);
    }

    /**
     * 影子路由一致率。
     *
     * <p>分母刻意<b>排除 UNKNOWN</b>（模型输出无法解析 / 调用失败）—— 把解析失败算进分母，
     * 一致率会随影子链路自身的稳定性波动，看不出「LLM 选工具准不准」这个真正的问题。
     * UNKNOWN 的绝对条数单独给出，它本身是影子链路健康度的指标。
     */
    @Override
    public Map<String, Object> shadowRouteStats(LocalDateTime since, int disagreeLimit) {
        Map<String, Long> byVerdict = new LinkedHashMap<>();
        for (Object[] row : repository.countByStatusForStage("SHADOW_ROUTE", since)) {
            byVerdict.put(row[0] == null ? "NULL" : row[0].toString(), ((Number) row[1]).longValue());
        }

        long unknown = byVerdict.getOrDefault("UNKNOWN", 0L);
        long comparable = byVerdict.values().stream().mapToLong(Long::longValue).sum() - unknown;
        long agree = byVerdict.getOrDefault("AGREE", 0L) + byVerdict.getOrDefault("BOTH_NONE", 0L);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("since", since.toString());
        result.put("comparableCount", comparable);
        result.put("agreeCount", agree);
        result.put("agreeRate", comparable == 0 ? null : Math.round(agree * 1000.0 / comparable) / 10.0);
        result.put("unknownCount", unknown);
        // 样本量提示：n=1 时 agreeRate=100% 看起来很漂亮，但它不能支撑任何架构决策。
        // 把「够不够」直接写在返回里，避免把一个偶然值当成结论。30 是经验下限，不是统计学阈值。
        result.put("sampleSufficient", comparable >= MIN_SAMPLES_FOR_CONCLUSION);
        result.put("note", comparable >= MIN_SAMPLES_FOR_CONCLUSION
                ? "样本量足够，可据此判断是否切换路由策略"
                : "样本量不足（当前 " + comparable + " 条，建议至少 " + MIN_SAMPLES_FOR_CONCLUSION
                  + " 条），一致率暂无参考意义；请继续产生真实提问");
        result.put("byVerdict", byVerdict);
        result.put("disagreements", disagreementSamples(disagreeLimit));
        return result;
    }

    /** 分歧样本：既用于人工核查，也是「问句 → 期望工具」评测集最真实的素材来源。 */
    private List<Map<String, Object>> disagreementSamples(int limit) {
        List<AgentTraceJpaEntity> rows = repository.findByStageAndStatusNotInOrderByCreatedAtDesc(
                "SHADOW_ROUTE", List.of("AGREE", "BOTH_NONE", "UNKNOWN"),
                PageRequest.of(0, Math.max(1, limit)));
        List<Map<String, Object>> samples = new ArrayList<>(rows.size());
        for (AgentTraceJpaEntity e : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("verdict", e.getStatus());
            m.put("question", e.getLlmInput());
            m.put("detail", e.getLlmOutput());
            m.put("llmTool", e.getToolName());
            m.put("heuristicArgs", e.getToolArguments());
            m.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
            samples.add(m);
        }
        return samples;
    }

    private List<Map<String, Object>> convert(List<AgentTraceJpaEntity> list) {
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (AgentTraceJpaEntity e : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("sessionId", e.getSessionId());
            m.put("traceId", e.getTraceId());
            m.put("roundNo", e.getRoundNo());
            m.put("stage", e.getStage());
            m.put("toolName", e.getToolName());
            m.put("toolArguments", e.getToolArguments());
            m.put("toolResult", e.getToolResult());
            m.put("llmInput", e.getLlmInput());
            m.put("llmOutput", e.getLlmOutput());
            m.put("heuristicHit", e.getHeuristicHit());
            m.put("heuristicStrategy", e.getHeuristicStrategy());
            m.put("durationMs", e.getDurationMs());
            m.put("status", e.getStatus());
            m.put("errorMessage", e.getErrorMessage());
            m.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
            result.add(m);
        }
        return result;
    }

    private static String currentTraceId() {
        try {
            String tid = org.slf4j.MDC.get("traceId");
            return tid != null ? tid : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        } catch (Exception e) {
            return null;
        }
    }

    private static String toJson(Object obj) {
        if (obj == null) return null;
        try { return MAPPER.writeValueAsString(obj); }
        catch (JsonProcessingException e) { return obj.toString(); }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "...(truncated)" : s;
    }

    // ==================== 异步写入 / 保留策略 ====================

    private void writeLoop() {
        List<AgentTraceJpaEntity> batch = new ArrayList<>(cfg.getBatchSize());
        while (running || !queue.isEmpty()) {
            try {
                AgentTraceJpaEntity first = queue.poll(cfg.getFlushIntervalMs(), TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, Math.max(1, cfg.getBatchSize()) - 1);
                flush(batch);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                log.warn("⚠️ trace 批量写入失败（丢弃本批 {} 条）: {}", batch.size(), ex.getMessage());
            } finally {
                batch.clear();
            }
        }
    }

    private void flush(List<AgentTraceJpaEntity> batch) {
        if (!batch.isEmpty()) repository.saveAll(batch);
    }

    /** 停机时把队列里剩下的写完，避免最后几秒的 trace 丢失。 */
    @PreDestroy
    public void shutdown() {
        running = false;
        if (writer == null) return;
        try {
            writer.join(5_000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        List<AgentTraceJpaEntity> rest = new ArrayList<>();
        queue.drainTo(rest);
        try {
            flush(rest);
        } catch (Exception ex) {
            log.warn("⚠️ 停机时写入剩余 trace 失败: {}", ex.getMessage());
        }
    }

    /** 保留策略：删除超过 retention-days 的记录。 */
    @Scheduled(cron = "${observability.trace.cleanup-cron:0 30 3 * * *}")
    public void purgeExpired() {
        if (cfg.getRetentionDays() <= 0) return;
        try {
            int n = repository.deleteOlderThan(LocalDateTime.now().minusDays(cfg.getRetentionDays()));
            if (n > 0) log.info("🧹 已清理 {} 条超过 {} 天的决策链路记录", n, cfg.getRetentionDays());
        } catch (Exception ex) {
            log.warn("⚠️ 清理过期 trace 失败: {}", ex.getMessage());
        }
    }
}