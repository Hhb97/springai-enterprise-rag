package com.example.ragassitant.infrastructure.persistence.jpa;

import com.example.ragassitant.domain.spi.AgentTraceStorePort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

@Component
public class JpaAgentTraceStoreAdapter implements AgentTraceStorePort {

    private static final Logger log = LoggerFactory.getLogger(JpaAgentTraceStoreAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentTraceJpaRepository repository;

    public JpaAgentTraceStoreAdapter(AgentTraceJpaRepository repository) {
        this.repository = repository;
        log.info("✅ JpaAgentTraceStoreAdapter 初始化（决策链路持久化）");
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
            repository.save(e);
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
}