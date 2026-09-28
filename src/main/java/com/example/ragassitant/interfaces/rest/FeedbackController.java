package com.example.ragassitant.interfaces.rest;

import com.example.ragassitant.common.trace.TraceIdFilter;
import com.example.ragassitant.infrastructure.governance.ToolGovernance;
import com.example.ragassitant.infrastructure.observability.AgentMetrics;
import com.example.ragassitant.infrastructure.persistence.jpa.AnswerFeedbackEntity;
import com.example.ragassitant.infrastructure.persistence.jpa.AnswerFeedbackRepository;
import com.example.ragassitant.interfaces.dto.ApiResponse;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回答反馈。
 *
 * <p>闭环：用户 👎 → {@code GET /api/feedback/eval-candidates} 导出为评测用例骨架 →
 * 人工补上预期断言（answer.contains / refusal / tools）→ 追加进 {@code eval/golden-vN.json}。
 * 线上真实踩过的坑进了评测集，以后任何改动再把它弄坏，评测都会拦下来。
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private static final int MAX_TEXT = 8000;

    private final AnswerFeedbackRepository repository;
    private final AgentMetrics metrics;

    public FeedbackController(AnswerFeedbackRepository repository, AgentMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    public record FeedbackRequest(String sessionId, String question, String answer,
                                  String rating, String comment, String mode) {
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> submit(@RequestBody FeedbackRequest req) {
        if (req == null || !("up".equals(req.rating()) || "down".equals(req.rating()))) {
            return ApiResponse.fail("INVALID_RATING", "rating 必须是 up 或 down");
        }
        AnswerFeedbackEntity e = new AnswerFeedbackEntity();
        e.setSessionId(clip(req.sessionId(), 128));
        e.setTraceId(MDC.get(TraceIdFilter.TRACE_ID_KEY));
        e.setUsername(ToolGovernance.currentUser());
        e.setRating(req.rating());
        e.setMode(clip(req.mode(), 32));
        e.setQuestion(clip(req.question(), MAX_TEXT));
        e.setAnswer(clip(req.answer(), MAX_TEXT));
        e.setComment(clip(req.comment(), MAX_TEXT));
        repository.save(e);
        metrics.feedback(req.rating());
        return ApiResponse.ok("反馈已记录", Map.of("id", e.getId()));
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(value = "rating", required = false) String rating) {
        List<AnswerFeedbackEntity> items = rating == null
                ? repository.findTop200ByOrderByCreatedAtDesc()
                : repository.findTop200ByRatingOrderByCreatedAtDesc(rating);
        long up = repository.countByRating("up");
        long down = repository.countByRating("down");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("up", up);
        data.put("down", down);
        data.put("satisfaction", up + down == 0 ? null : Math.round(up * 1000.0 / (up + down)) / 1000.0);
        data.put("items", items);
        return ApiResponse.ok("回答反馈", data);
    }

    /** 👎 样本导出为评测用例骨架（answer 断言待人工标注）。 */
    @GetMapping("/eval-candidates")
    public ApiResponse<List<Map<String, Object>>> evalCandidates() {
        List<Map<String, Object>> cases = new ArrayList<>();
        for (AnswerFeedbackEntity e : repository.findTop200ByRatingOrderByCreatedAtDesc("down")) {
            if (e.getQuestion() == null || e.getQuestion().isBlank()) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", "fb-" + e.getId());
            c.put("category", "feedback");
            c.put("mode", "rag".equals(e.getMode()) ? "rag" : "agent");
            c.put("question", e.getQuestion());
            c.put("route", "TODO");
            c.put("answer", Map.of());
            c.put("_badAnswer", e.getAnswer());
            c.put("_userComment", e.getComment());
            cases.add(c);
        }
        return ApiResponse.ok("评测候选（需人工标注 route 与 answer 断言）", cases);
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
