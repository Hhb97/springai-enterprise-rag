package com.example.ragassitant.interfaces.rest;

import com.example.ragassitant.application.service.AgentAppService;
import com.example.ragassitant.application.service.IngestAppService;
import com.example.ragassitant.application.service.RagAppService;
import com.example.ragassitant.application.service.ReActAgentAppService;
import com.example.ragassitant.interfaces.dto.request.ChatRequestDTO;
import com.example.ragassitant.interfaces.dto.response.ChatResponseDTO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/ai")
public class ChatController {

    private final RagAppService ragAppService;
    private final IngestAppService ingestAppService;
    private final AgentAppService agentAppService;
    private final ReActAgentAppService reActAgentAppService;

    public ChatController(RagAppService ragAppService,
                          IngestAppService ingestAppService,
                          AgentAppService agentAppService,
                          ReActAgentAppService reActAgentAppService) {
        this.ragAppService = ragAppService;
        this.ingestAppService = ingestAppService;
        this.agentAppService = agentAppService;
        this.reActAgentAppService = reActAgentAppService;
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