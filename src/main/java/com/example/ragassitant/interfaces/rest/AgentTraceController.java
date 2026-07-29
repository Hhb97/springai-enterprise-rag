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