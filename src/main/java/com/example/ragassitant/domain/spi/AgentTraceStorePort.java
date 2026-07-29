package com.example.ragassitant.domain.spi;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface AgentTraceStorePort {

    void recordDecision(String sessionId, int roundNo, String stage,
                        String toolName, Map<String, Object> toolArguments, String toolResult,
                        String llmInput, String llmOutput,
                        boolean heuristicHit, String heuristicStrategy,
                        Long durationMs, String status, String errorMessage);

    List<Map<String, Object>> queryBySession(String sessionId);

    List<Map<String, Object>> queryRecent(int limit);

    long countSince(LocalDateTime since);
}