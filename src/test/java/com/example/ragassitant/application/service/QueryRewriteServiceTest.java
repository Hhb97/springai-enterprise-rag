package com.example.ragassitant.application.service;

import com.example.ragassitant.domain.service.PromptBuilder;
import com.example.ragassitant.domain.spi.AgentTraceStorePort;
import com.example.ragassitant.domain.spi.LlmPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class QueryRewriteServiceTest {

    // ==================== 桩 ====================

    /** 可编程的 LlmPort 桩：由 responder 决定返回什么/是否抛异常 */
    static class StubLlm implements LlmPort {
        private final Function<List<MessageDTO>, String> responder;
        int calls = 0;

        StubLlm(Function<List<MessageDTO>, String> responder) {
            this.responder = responder;
        }

        static StubLlm returning(String fixed) {
            return new StubLlm(m -> fixed);
        }

        @Override
        public String chat(List<String> s, List<String> hu, List<String> ha, String cu) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String chatWithRawMessages(List<MessageDTO> messages) {
            calls++;
            return responder.apply(messages);
        }

        @Override
        public String chatWithAutoToolCalling(List<MessageDTO> m, int max, List<Object> tools) {
            throw new UnsupportedOperationException();
        }

        // LlmPort 新增流式方法后本桩编译失败，整个测试文件曾因此被逐行注释掉（17 个测试静默失效）
        @Override
        public reactor.core.publisher.Flux<String> chatStreamWithRawMessages(List<MessageDTO> messages) {
            throw new UnsupportedOperationException();
        }
    }

    static class RecordingTraceStore implements AgentTraceStorePort {
        final List<Map<String, Object>> records = new ArrayList<>();

        @Override
        public void recordDecision(String sessionId, int roundNo, String stage,
                                   String toolName, Map<String, Object> toolArguments, String toolResult,
                                   String llmInput, String llmOutput,
                                   boolean heuristicHit, String heuristicStrategy,
                                   Long durationMs, String status, String errorMessage) {
            records.add(Map.of(
                    "stage", String.valueOf(stage),
                    "in", String.valueOf(llmInput),
                    "out", String.valueOf(llmOutput),
                    "strategy", String.valueOf(heuristicStrategy),
                    "status", String.valueOf(status)));
        }

        @Override
        public List<Map<String, Object>> queryBySession(String sessionId) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> queryRecent(int limit) {
            return List.of();
        }

        @Override
        public long countSince(LocalDateTime since) {
            return 0;
        }
    }

    private static List<Map.Entry<String, String>> history(String... pairs) {
        List<Map.Entry<String, String>> h = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            h.add(new AbstractMap.SimpleEntry<>(pairs[i], pairs[i + 1]));
        }
        return h;
    }

    private QueryRewriteService service(LlmPort llm, RecordingTraceStore trace) {
        return service(llm, trace, true, 3000);
    }

    private QueryRewriteService service(LlmPort llm, RecordingTraceStore trace, boolean enabled, long timeoutMs) {
        return new QueryRewriteService(llm, new PromptBuilder(), trace, enabled, timeoutMs, 3, 3.0);
    }

    // ==================== 门禁 ====================

    @Test
    @DisplayName("短问句、指代词、省略式收尾触发改写")
    void detectsContextDependentQuestions() {
        assertTrue(QueryRewriteService.needsRewrite("那结算周期呢"));
        assertTrue(QueryRewriteService.needsRewrite("它的能耗怎么样"));
        assertTrue(QueryRewriteService.needsRewrite("换成按月"));
        assertTrue(QueryRewriteService.needsRewrite("多少"));
    }

    @Test
    @DisplayName("自包含的完整问句不触发改写")
    void skipsSelfContainedQuestions() {
        assertFalse(QueryRewriteService.needsRewrite(
                "请详细介绍一下计费管理模块的账单生成流程和结算规则"));
        assertFalse(QueryRewriteService.needsRewrite(null));
        assertFalse(QueryRewriteService.needsRewrite("   "));
    }

    @Test
    @DisplayName("带完整 SN 的问句信息已足够，不触发改写")
    void skipsQuestionsCarryingSn() {
        assertFalse(QueryRewriteService.needsRewrite("mv-008 呢"));
        assertFalse(QueryRewriteService.needsRewrite("wzh_001近30天能耗"));
    }

    @Test
    @DisplayName("闲聊/问候不触发改写")
    void skipsSmallTalk() {
        // 「你好」只有 2 个字，必然命中「短问句」门禁；一旦被改写，模型只要输出「设备」
        // 就会命中 list_devices 并 FORCE_EXEC —— 用户说句问候，系统甩回一张设备表。
        assertFalse(QueryRewriteService.needsRewrite("你好"));
        assertFalse(QueryRewriteService.needsRewrite("谢谢"));
        assertFalse(QueryRewriteService.needsRewrite("你是什么大模型"));
    }

    @Test
    @DisplayName("闲聊有历史时也不调 LLM 改写")
    void smallTalkNeverReachesLlm() {
        StubLlm llm = StubLlm.returning("设备");
        QueryRewriteService svc = service(llm, new RecordingTraceStore());

        assertEquals(List.of("你好"), svc.rewrite("你好", history("列出所有设备", "共 8 台设备…"), "s1"));
        assertEquals(0, llm.calls, "闲聊不应产生改写调用");
    }

    @Test
    @DisplayName("首轮无历史时完全不调 LLM")
    void skipsWhenNoHistory() {
        StubLlm llm = StubLlm.returning("不该被调用");
        QueryRewriteService svc = service(llm, new RecordingTraceStore());

        assertEquals(List.of("那个呢"), svc.rewrite("那个呢", List.of(), "s1"));
        assertEquals(0, llm.calls, "无历史时不应产生 LLM 调用");
    }

    @Test
    @DisplayName("门禁不通过时不调 LLM（零成本路径）")
    void skipsLlmWhenSelfContained() {
        StubLlm llm = StubLlm.returning("不该被调用");
        QueryRewriteService svc = service(llm, new RecordingTraceStore());
        String q = "请详细介绍一下计费管理模块的账单生成流程和结算规则";

        assertEquals(List.of(q), svc.rewrite(q, history("你好", "你好"), "s1"));
        assertEquals(0, llm.calls);
    }

    @Test
    @DisplayName("开关关闭时直接返回原问题")
    void disabledSwitchShortCircuits() {
        StubLlm llm = StubLlm.returning("不该被调用");
        QueryRewriteService svc = service(llm, new RecordingTraceStore(), false, 3000);

        assertEquals(List.of("那个呢"), svc.rewrite("那个呢", history("计费流程", "分三步"), "s1"));
        assertEquals(0, llm.calls);
    }

    // ==================== 改写与后处理 ====================

    @Test
    @DisplayName("正常改写生效")
    void rewritesSuccessfully() {
        QueryRewriteService svc = service(StubLlm.returning("计费的结算周期是怎样的"), new RecordingTraceStore());
        assertEquals(List.of("计费的结算周期是怎样的"),
                svc.rewrite("那结算周期呢", history("计费流程是怎样的", "计费流程分为三步"), "s1"));
    }

    @Test
    @DisplayName("剥除模型加的前缀、引号和多余换行")
    void sanitizesModelDecoration() {
        QueryRewriteService svc = service(
                StubLlm.returning("改写结果：「计费的结算周期是怎样的」\n（说明：补全了主语）"),
                new RecordingTraceStore());

        assertEquals(List.of("计费的结算周期是怎样的"),
                svc.rewrite("那结算周期呢", history("计费流程", "分三步"), "s1"));
    }

    @Test
    @DisplayName("改写结果过长判定跑飞，回退原问题")
    void rejectsOverlongRewrite() {
        String runaway = "计费的结算周期是这样的：首先系统会在每月月初生成账单，然后按照配置的费率结构进行计算，"
                + "接着应用税费和附加费，最后生成 PDF 账单并发送给相关人员。";
        QueryRewriteService svc = service(StubLlm.returning(runaway), new RecordingTraceStore());

        assertEquals(List.of("那结算周期呢"),
                svc.rewrite("那结算周期呢", history("计费流程", "分三步"), "s1"));
    }

    @Test
    @DisplayName("模型返回空白时回退原问题")
    void fallsBackOnBlankOutput() {
        QueryRewriteService svc = service(StubLlm.returning("   "), new RecordingTraceStore());
        assertEquals(List.of("那结算周期呢"),
                svc.rewrite("那结算周期呢", history("计费流程", "分三步"), "s1"));
    }

    // ==================== 降级 ====================

    @Test
    @DisplayName("LLM 抛异常时静默回退，不向上传播")
    void swallowsLlmException() {
        StubLlm llm = new StubLlm(m -> {
            throw new RuntimeException("connection refused");
        });
        RecordingTraceStore trace = new RecordingTraceStore();
        QueryRewriteService svc = service(llm, trace);

        assertEquals(List.of("那结算周期呢"),
                assertDoesNotThrow(() -> svc.rewrite("那结算周期呢", history("计费流程", "分三步"), "s1")));
        assertEquals("ERROR", trace.records.get(0).get("status"));
    }

    @Test
    @DisplayName("LLM 卡住时超时回退，不拖住主流程")
    void timesOutSlowLlm() {
        StubLlm llm = new StubLlm(m -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "太慢了";
        });
        RecordingTraceStore trace = new RecordingTraceStore();
        QueryRewriteService svc = service(llm, trace, true, 200);

        long start = System.currentTimeMillis();
        List<String> result = svc.rewrite("那结算周期呢", history("计费流程", "分三步"), "s1");
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(List.of("那结算周期呢"), result);
        assertTrue(elapsed < 2000, "超时未生效，实际耗时 " + elapsed + "ms");
        assertEquals("ERROR", trace.records.get(0).get("status"));
        assertTrue(String.valueOf(trace.records.get(0).get("out")).contains("那结算周期呢"));
    }

    // ==================== 可观测 ====================

    @Test
    @DisplayName("改写前后写入 AgentTrace，可在 trace 页面回放")
    void recordsTrace() {
        RecordingTraceStore trace = new RecordingTraceStore();
        QueryRewriteService svc = service(StubLlm.returning("计费的结算周期是怎样的"), trace);
        svc.rewrite("那结算周期呢", history("计费流程", "分三步"), "s1");

        assertEquals(1, trace.records.size());
        Map<String, Object> rec = trace.records.get(0);
        assertEquals("QUERY_REWRITE", rec.get("stage"));
        assertEquals("那结算周期呢", rec.get("in"));
        assertEquals("计费的结算周期是怎样的", rec.get("out"));
        assertEquals("REWRITTEN", rec.get("strategy"));
        assertEquals("OK", rec.get("status"));
    }

    @Test
    @DisplayName("改写无变化时标记 UNCHANGED，便于评估改写命中率")
    void marksUnchangedRewrite() {
        RecordingTraceStore trace = new RecordingTraceStore();
        QueryRewriteService svc = service(StubLlm.returning("那结算周期呢"), trace);
        svc.rewrite("那结算周期呢", history("计费流程", "分三步"), "s1");

        assertEquals("UNCHANGED", trace.records.get(0).get("strategy"));
    }

    @Test
    @DisplayName("只把最近 N 轮历史喂给模型")
    void limitsHistoryWindow() {
        List<String> captured = new ArrayList<>();
        StubLlm llm = new StubLlm(messages -> {
            captured.add(messages.get(0).content);
            return "改写后的问题";
        });
        QueryRewriteService svc = service(llm, new RecordingTraceStore());

        svc.rewrite("那个呢", history(
                "第一轮问题", "第一轮回答",
                "第二轮问题", "第二轮回答",
                "第三轮问题", "第三轮回答",
                "第四轮问题", "第四轮回答"), "s1");

        String prompt = captured.get(0);
        assertFalse(prompt.contains("第一轮问题"), "超出窗口的历史不该出现在 prompt 里");
        assertTrue(prompt.contains("第四轮问题"), "最近一轮历史必须在 prompt 里");
    }
}
