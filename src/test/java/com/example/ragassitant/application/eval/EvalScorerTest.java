package com.example.ragassitant.application.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvalScorerTest {

    private static GoldenSet.Case caseWith(GoldenSet.Answer a) {
        return new GoldenSet.Case("t-1", "knowledge", "rag", "q", "none", null, a);
    }

    @Test
    void containsIsCaseInsensitiveAndRequiresAll() {
        var c = caseWith(new GoldenSet.Answer(List.of("Acuvim III", "8%"), null, null, null, null, null));
        assertTrue(EvalScorer.score(c, "仅 acuvim iii 支持，限值 8%", List.of(), 1).passed());
        var r = EvalScorer.score(c, "仅 Acuvim III 支持", List.of(), 1);
        assertFalse(r.passed());
        assertTrue(r.failures().get(0).contains("8%"));
    }

    @Test
    void containsAnyRequiresOneHitPerGroup() {
        var c = caseWith(new GoldenSet.Answer(null, List.of(List.of("没有", "无阈值"), List.of("诊断")), null, null, null, null));
        assertTrue(EvalScorer.score(c, "无阈值，仅作诊断参考", List.of(), 1).passed());
        assertFalse(EvalScorer.score(c, "没有限值", List.of(), 1).passed(), "第二组未命中");
    }

    @Test
    void toolAssertions() {
        var c = caseWith(new GoldenSet.Answer(null, null, null, List.of("list_devices"), List.of("get_weather"), null));
        assertTrue(EvalScorer.score(c, "ok", List.of("list_devices"), 1).passed());
        assertFalse(EvalScorer.score(c, "ok", List.of(), 1).passed(), "未调用期望工具");
        assertFalse(EvalScorer.score(c, "ok", List.of("list_devices", "get_weather"), 1).passed(), "调用了禁止工具");
    }

    @Test
    void refusalDetection() {
        var expectRefusal = caseWith(new GoldenSet.Answer(null, null, null, null, null, true));
        assertTrue(EvalScorer.score(expectRefusal, "抱歉，知识库中没有找到与该问题相关的信息。", List.of(), 1).passed());
        assertFalse(EvalScorer.score(expectRefusal, "创建用户请点击右上角…", List.of(), 1).passed());
    }

    @Test
    void blankAnswerAlwaysFails() {
        var c = caseWith(new GoldenSet.Answer(null, null, List.of("x"), null, null, null));
        assertFalse(EvalScorer.score(c, "", List.of(), 1).passed());
    }

    @Test
    void summaryCountsByCategory() {
        var ok = new EvalScorer.CaseResult("a", "kb", true, List.of(), "", List.of(), 10);
        var bad = new EvalScorer.CaseResult("b", "kb", false, List.of("x"), "", List.of(), 30);
        Map<String, Object> s = EvalRunner.summarize("set", "run", List.of(ok, bad));
        assertEquals(2, s.get("total"));
        assertEquals(0.5, s.get("passRate"));
        assertEquals(20L, s.get("avgLatencyMs"));
    }
}
