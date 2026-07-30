package com.example.ragassitant.interfaces.rest;

import com.example.ragassitant.application.service.AgentAppService;
import com.example.ragassitant.application.service.IngestAppService;
import com.example.ragassitant.application.service.RagAppService;
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

    public ChatController(RagAppService ragAppService,
                          IngestAppService ingestAppService,
                          AgentAppService agentAppService) {
        this.ragAppService = ragAppService;
        this.ingestAppService = ingestAppService;
        this.agentAppService = agentAppService;
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponseDTO> chat(@RequestBody ChatRequestDTO request) {
        try {
            Map<String, Object> result = ragAppService.askWithOptions(
                    request.getQuestion(),
                    request.getEnableHybridSearch(),
                    request.getTopK()
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
            boolean useRag = request.getEnableHybridSearch() != null ? request.getEnableHybridSearch() : true;
            AgentAppService.ChatResponse resp = agentAppService.chat(request.getQuestion(), sessionId, useRag);

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

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "model", "spring-ai-2.0.0 + ollama + rag + agent + ddd-4layer + bm25-hybrid",
                "features", List.of("rag", "hybrid-search", "doc-version", "agent", "influxdb", "ddd-layers")
        );
    }
}