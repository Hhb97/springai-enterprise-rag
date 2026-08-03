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

    /**
     * 影子路由比对结果统计。
     *
     * <p>{@code SHADOW_ROUTE} 阶段的 status 存的就是比对判定，聚合后即可得到
     * 「启发式正则路由 vs LLM 自主选工具」的一致率与分歧分布。
     *
     * @param since         统计起点
     * @param disagreeLimit 附带多少条分歧样本（用于人工核查，也是评测集的素材）
     */
    default Map<String, Object> shadowRouteStats(LocalDateTime since, int disagreeLimit) {
        return Map.of();
    }
}