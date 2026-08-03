package com.example.ragassitant.interfaces.rest;

import com.example.ragassitant.domain.spi.AgentTraceStorePort;
import com.example.ragassitant.interfaces.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent/traces")
public class AgentTraceController {

    private final AgentTraceStorePort traceStore;

    public AgentTraceController(AgentTraceStorePort traceStore) {
        this.traceStore = traceStore;
    }

    @GetMapping("/session/{sessionId}")
    public ApiResponse<List<Map<String, Object>>> queryBySession(@PathVariable String sessionId) {
        return ApiResponse.ok("决策链路按会话查询", traceStore.queryBySession(sessionId));
    }

    @GetMapping("/recent")
    public ApiResponse<List<Map<String, Object>>> queryRecent(
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return ApiResponse.ok("最近决策链路", traceStore.queryRecent(limit));
    }

    /**
     * 影子路由比对结果：启发式正则路由 vs LLM 自主选工具的一致率与分歧样本。
     *
     * <p>这是决定「能否把 HeuristicRouter 换成 LLM 自调用」的依据。看两个数：
     * {@code agreeRate}（一致率，分母已排除解析失败）和 {@code byVerdict} 里的分歧构成 ——
     * {@code HEURISTIC_ONLY} 多说明正则在劫持（假阳性），{@code LLM_ONLY} 多说明正则在漏判。
     */
    @GetMapping("/shadow-stats")
    public ApiResponse<Map<String, Object>> shadowStats(
            @RequestParam(value = "days", defaultValue = "7") int days,
            @RequestParam(value = "samples", defaultValue = "20") int samples) {
        return ApiResponse.ok("影子路由比对统计",
                traceStore.shadowRouteStats(LocalDateTime.now().minusDays(Math.max(1, days)), samples));
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        Map<String, Object> data = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        data.put("last24hCount", traceStore.countSince(now.minusHours(24)));
        data.put("last7dCount", traceStore.countSince(now.minusDays(7)));
        data.put("generatedAt", now.toString());
        return ApiResponse.ok("决策链路统计", data);
    }
}