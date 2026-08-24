package com.example.ragassitant.interfaces.rest;

import com.example.ragassitant.domain.spi.AgentTraceStorePort;
import com.example.ragassitant.domain.spi.ConversationStorePort;
import com.example.ragassitant.interfaces.dto.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@RestController
@RequestMapping("/api/agent/traces")
public class AgentTraceController {

    private final AgentTraceStorePort traceStore;
    private final ConversationStorePort conversationStore;

    @Value("${agent.memory-size:20}")
    private int memorySize;

    public AgentTraceController(AgentTraceStorePort traceStore, ConversationStorePort conversationStore) {
        this.traceStore = traceStore;
        this.conversationStore = conversationStore;
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

    /**
     * 会话滚动摘要查询：验证「窗口裁剪的旧轮次被压成要点后，模型仍能看到早期内容」。
     *
     * <p>返回每个会话当前累积的摘要（sessionId → 内容）与窗口配置。触发方式：
     * 同一会话连续问答超过 {@code agent.memory-size} 轮（默认 20），窗口满员时
     * 最旧轮次会被自动压缩进这里。
     */
    @GetMapping("/memory-summaries")
    public ApiResponse<Map<String, Object>> memorySummaries() {
        Map<String, String> summaries = conversationStore.getSummaries();
        // 按会话 id 排序输出，页面展示稳定
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", summaries.size());
        data.put("windowTurns", memorySize);
        data.put("summaries", new TreeMap<>(summaries));
        return ApiResponse.ok("会话滚动摘要", data);
    }

    /**
     * 所有存活会话的窗口内历史：每个会话当前实际存着的完整问答对。
     * 和 {@code /memory-summaries} 配合看——历史窗口里有什么、摘要里补了什么，一目了然。
     */
    @GetMapping("/memory-sessions")
    public ApiResponse<Map<String, Object>> memorySessions() {
        Map<String, List<Map.Entry<String, String>>> histories = conversationStore.getAllSessionHistories();
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (Map.Entry<String, List<Map.Entry<String, String>>> e : histories.entrySet()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("sessionId", e.getKey());
            s.put("turnCount", e.getValue().size());
            String summary = conversationStore.getSummaries().get(e.getKey());
            boolean hasSummary = summary != null && !summary.isBlank();
            s.put("hasSummary", hasSummary);
            if (hasSummary) s.put("summary", summary);
            // 【坑】Map.Entry 会被 Jackson 序列化成单键对象 {"问题":"回答"}，键是动态的用户提问文本，
            // 前端没法按 t[0]/t[1] 取值。这里显式转成 [user, assistant] 二元数组。
            s.put("turns", e.getValue().stream()
                    .map(p -> List.of(p.getKey() == null ? "" : p.getKey(),
                            p.getValue() == null ? "" : p.getValue()))
                    .toList());
            sessions.add(s);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", sessions.size());
        data.put("windowTurns", memorySize);
        data.put("sessions", sessions);
        return ApiResponse.ok("会话历史窗口", data);
    }
}