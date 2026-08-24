package com.example.ragassitant.interfaces.rest;

import com.example.ragassitant.application.service.AgentAppService;
import com.example.ragassitant.application.service.IngestAppService;
import com.example.ragassitant.application.service.PlanThenExecuteAppService;
import com.example.ragassitant.application.service.RagAppService;
import com.example.ragassitant.application.service.ReActAgentAppService;
import com.example.ragassitant.interfaces.dto.request.ChatRequestDTO;
import com.example.ragassitant.interfaces.dto.response.ChatResponseDTO;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/ai")
public class ChatController {

    private final RagAppService ragAppService;
    private final IngestAppService ingestAppService;
    private final AgentAppService agentAppService;
    private final ReActAgentAppService reActAgentAppService;
    private final PlanThenExecuteAppService planThenExecuteAppService;

    public ChatController(RagAppService ragAppService,
                          IngestAppService ingestAppService,
                          AgentAppService agentAppService,
                          ReActAgentAppService reActAgentAppService,
                          PlanThenExecuteAppService planThenExecuteAppService) {
        this.ragAppService = ragAppService;
        this.ingestAppService = ingestAppService;
        this.agentAppService = agentAppService;
        this.reActAgentAppService = reActAgentAppService;
        this.planThenExecuteAppService = planThenExecuteAppService;
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponseDTO> chat(@RequestBody ChatRequestDTO request) {
        try {
            Map<String, Object> result = ragAppService.askWithOptions(
                    request.getQuestion(),
                    request.getEnableHybridSearch(),
                    request.getTopK(),
                    request.getSessionId()
            );
            String answer = (String) result.get("answer");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sourcesData = (List<Map<String, Object>>) result.get("sources");
            List<ChatResponseDTO.SourceDoc> sources = sourcesData.stream()
                    .map(s -> new ChatResponseDTO.SourceDoc(
                            (String) s.get("content"),
                            s.get("metadata").toString(),
                            s.get("score") != null ? ((Number) s.get("score")).doubleValue() : 0.0
                    ))
                    .collect(Collectors.toList());
            return ResponseEntity.ok(new ChatResponseDTO(answer, sources));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new ChatResponseDTO("❌ 问答失败: " + e.getMessage(), List.of()));
        }
    }

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest() {
        try {
            int count = ingestAppService.ingestAll();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "count", count,
                    "message", "知识库已重新构建，共 " + count + " 条向量"
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/ingestAll")
    public ResponseEntity<?> ingestAll() {
        return ingest();
    }

    @PostMapping("/ingest/{versionId}")
    public ResponseEntity<Map<String, String>> ingestDocument(@PathVariable Long versionId) {
        try {
            int count = ingestAppService.ingestDocument(versionId);
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "message", "文档版本已入库，共 " + count + " 条向量"
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    @PostMapping("/agent-chat")
    public ResponseEntity<ChatResponseDTO> agentChat(@RequestBody ChatRequestDTO request) {
        try {
            String sessionId = request.getSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = "session-" + System.currentTimeMillis();
            }
            boolean enableHybrid = request.getEnableHybridSearch() != null ? request.getEnableHybridSearch() : true;
            AgentAppService.ChatResponse resp = agentAppService.chat(
                    request.getQuestion(), sessionId, true, enableHybrid, request.getTopK());

            List<ChatResponseDTO.SourceDoc> sources = List.of();
            if (resp.sources != null) {
                sources = resp.sources.stream()
                        .map(s -> new ChatResponseDTO.SourceDoc(s.content, s.metadata, s.score))
                        .collect(Collectors.toList());
            }
            return ResponseEntity.ok(new ChatResponseDTO(resp.answer, sources));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new ChatResponseDTO("❌ Agent 处理失败: " + e.getMessage(), List.of()));
        }
    }

    @PostMapping("/react-agent-chat")
    public ResponseEntity<Map<String, Object>> reactAgentChat(@RequestBody ChatRequestDTO request) {
        try {
            String sessionId = request.getSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = "react-session-" + System.currentTimeMillis();
            }
            boolean enableHybrid = request.getEnableHybridSearch() != null ? request.getEnableHybridSearch() : true;
            Map<String, Object> result = reActAgentAppService.chat(
                    request.getQuestion(), sessionId, true, enableHybrid, request.getTopK());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> err = new java.util.HashMap<>();
            err.put("answer", "❌ ReAct Agent 处理失败: " + e.getMessage());
            err.put("sources", List.of());
            err.put("mode", "react");
            err.put("steps", List.of());
            err.put("stepCount", 0);
            return ResponseEntity.internalServerError().body(err);
        }
    }

    /**
     * Plan-then-Execute 模式：Planner 一次规划 → Executor 并行执行 → Solver 一次综合。
     * 无依赖的工具步骤并行执行，Observation 一次性交给 Solver，比 ReAct 更省 token 和延迟。
     */
    @PostMapping("/plan-chat")
    public ResponseEntity<Map<String, Object>> planChat(@RequestBody ChatRequestDTO request) {
        try {
            String sessionId = request.getSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = "plan-session-" + System.currentTimeMillis();
            }
            boolean enableHybrid = request.getEnableHybridSearch() != null ? request.getEnableHybridSearch() : true;
            Map<String, Object> result = planThenExecuteAppService.chat(
                    request.getQuestion(), sessionId, true, enableHybrid, request.getTopK());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> err = new java.util.HashMap<>();
            err.put("answer", "❌ Plan Agent 处理失败: " + e.getMessage());
            err.put("sources", List.of());
            err.put("mode", "plan");
            err.put("steps", List.of());
            err.put("stepCount", 0);
            return ResponseEntity.internalServerError().body(err);
        }
    }

    /**
     * ReAct 流式端点（SSE）。
     * 返回 text/event-stream，事件序列：
     *   step_start → thought_delta*N → thought_end
     *     → (如果调工具) action_start → action_end → 下一轮 step
     *     → (如果给出答案) final_start → final_delta*N → done
     * 任何环节出错会额外发 error 事件，然后一定发 done 事件并关闭流。
     */
    @PostMapping(value = "/react-agent-chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> reactAgentChatStream(@RequestBody ChatRequestDTO request) {
        String sessionId = (request.getSessionId() == null || request.getSessionId().isBlank())
                ? "react-stream-" + System.currentTimeMillis()
                : request.getSessionId();
        boolean enableHybrid = request.getEnableHybridSearch() != null ? request.getEnableHybridSearch() : true;
        return reActAgentAppService.chatStream(
                request.getQuestion(), sessionId, true, enableHybrid, request.getTopK());
    }

    /**
     * Plan-then-Execute 流式端点（SSE）。
     * 事件序列与 ReAct 流式对齐，前端可复用同一套渲染：
     *   rag_ready → step_start → thought_end → action_start → action_end
     *     （步骤间按依赖波次推进，无依赖步骤并行执行）
     *     → solve_start → final_delta*N → done
     */
    @PostMapping(value = "/plan-chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> planChatStream(@RequestBody ChatRequestDTO request) {
        String sessionId = (request.getSessionId() == null || request.getSessionId().isBlank())
                ? "plan-stream-" + System.currentTimeMillis()
                : request.getSessionId();
        boolean enableHybrid = request.getEnableHybridSearch() != null ? request.getEnableHybridSearch() : true;
        return planThenExecuteAppService.chatStream(
                request.getQuestion(), sessionId, true, enableHybrid, request.getTopK());
    }

    @PostMapping("/compare-chat")
    public ResponseEntity<Map<String, Object>> compareChat(@RequestBody ChatRequestDTO request) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new java.util.HashMap<>();
        String sessionId = request.getSessionId() != null ? request.getSessionId() : "cmp-" + System.currentTimeMillis();
        boolean enableHybrid = request.getEnableHybridSearch() != null ? request.getEnableHybridSearch() : true;

        Map<String, Object> agentResult = new java.util.HashMap<>();
        long agentStart = System.currentTimeMillis();
        try {
            AgentAppService.ChatResponse resp = agentAppService.chat(
                    request.getQuestion(), sessionId + "-A", true, enableHybrid, request.getTopK());
            List<Map<String, Object>> sources = List.of();
            if (resp.sources != null) {
                sources = resp.sources.stream()
                        .map(s -> Map.<String, Object>of("content", s.content, "metadata", s.metadata, "score", s.score))
                        .toList();
            }
            agentResult.put("answer", resp.answer);
            agentResult.put("sources", sources);
            agentResult.put("success", true);
            agentResult.put("elapsedMs", System.currentTimeMillis() - agentStart);
        } catch (Exception e) {
            agentResult.put("answer", "❌ " + e.getMessage());
            agentResult.put("sources", List.of());
            agentResult.put("success", false);
            agentResult.put("elapsedMs", System.currentTimeMillis() - agentStart);
            agentResult.put("error", e.getMessage());
        }

        Map<String, Object> reactResult = new java.util.HashMap<>();
        long reactStart = System.currentTimeMillis();
        try {
            Map<String, Object> rr = reActAgentAppService.chat(
                    request.getQuestion(), sessionId + "-R", true, enableHybrid, request.getTopK());
            reactResult.put("answer", rr.get("answer"));
            reactResult.put("sources", rr.get("sources"));
            reactResult.put("steps", rr.get("steps"));
            reactResult.put("stepCount", rr.get("stepCount"));
            reactResult.put("success", true);
            reactResult.put("elapsedMs", System.currentTimeMillis() - reactStart);
        } catch (Exception e) {
            reactResult.put("answer", "❌ " + e.getMessage());
            reactResult.put("sources", List.of());
            reactResult.put("steps", List.of());
            reactResult.put("stepCount", 0);
            reactResult.put("success", false);
            reactResult.put("elapsedMs", System.currentTimeMillis() - reactStart);
            reactResult.put("error", e.getMessage());
        }

        result.put("question", request.getQuestion());
        result.put("sessionId", sessionId);
        result.put("totalMs", System.currentTimeMillis() - start);
        result.put("agent", agentResult);
        result.put("react", reactResult);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "model", "spring-ai-2.0.0 + ollama + rag + agent + ddd-4layer + bm25-hybrid",
                "features", List.of("rag", "hybrid-search", "doc-version", "agent", "react-agent", "compare-mode", "influxdb", "ddd-layers")
        );
    }
}