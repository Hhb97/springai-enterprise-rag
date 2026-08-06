package com.example.ragassitant.application.service;

import com.example.ragassitant.domain.model.Device;
import com.example.ragassitant.domain.model.EnergyRecord;
import com.example.ragassitant.domain.model.ToolCall;
import com.example.ragassitant.domain.service.EnergyAggregator;
import com.example.ragassitant.domain.service.PromptBuilder;
import com.example.ragassitant.domain.spi.AgentTraceStorePort;
import com.example.ragassitant.domain.spi.LlmPort;
import com.example.ragassitant.domain.spi.TimeSeriesDbPort;
import com.example.ragassitant.infrastructure.agent.DeviceTools;
import com.example.ragassitant.infrastructure.agent.ToolCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

import static com.example.ragassitant.application.service.ShadowToolRouter.Verdict.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 影子路由的判定与健壮性。
 *
 * <p>影子路由的产出是「一致率」这个数字，而这个数字会被用来决定要不要把启发式正则路由
 * 换成 LLM 自主调用 —— 所以判定逻辑本身必须可靠：把「LLM 少填一个可选参数」算成分歧，
 * 或者把「解析失败」算进分母，得出的结论都会把架构决策带偏。
 */
class ShadowToolRouterTest {

    // ==================== 桩 ====================

    static class StubLlm implements LlmPort {
        private final Function<List<MessageDTO>, String> responder;
        final ConcurrentLinkedQueue<List<MessageDTO>> calls = new ConcurrentLinkedQueue<>();

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
            calls.add(messages);
            return responder.apply(messages);
        }

        @Override
        public Flux<String> chatStreamWithRawMessages(List<MessageDTO> messages) {
            return null;
        }

        @Override
        public String chatWithAutoToolCalling(List<MessageDTO> m, int max, List<Object> tools) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String chat(String systemPrompt, String userQuestion) {
            return LlmPort.super.chat(systemPrompt, userQuestion);
        }
    }

    static class RecordingTraceStore implements AgentTraceStorePort {
        final ConcurrentLinkedQueue<Map<String, Object>> records = new ConcurrentLinkedQueue<>();

        @Override
        public void recordDecision(String sessionId, int roundNo, String stage,
                                   String toolName, Map<String, Object> toolArguments, String toolResult,
                                   String llmInput, String llmOutput,
                                   boolean heuristicHit, String heuristicStrategy,
                                   Long durationMs, String status, String errorMessage) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stage", String.valueOf(stage));
            m.put("status", String.valueOf(status));
            m.put("llmTool", String.valueOf(toolName));
            m.put("detail", String.valueOf(llmOutput));
            records.add(m);
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

    static class StubTsdb implements TimeSeriesDbPort {
        @Override
        public List<Device> listBuiltinDevices(String keyword) {
            return List.of();
        }

        @Override
        public Device findDeviceBySn(String sn) {
            return null;
        }

        @Override
        public List<EnergyRecord> queryEnergyByDays(String sn, int days) {
            return List.of();
        }

        @Override
        public List<EnergyRecord> queryEnergyByDateRange(String sn, LocalDate s, LocalDate e) {
            return List.of();
        }

        @Override
        public Map<String, Double> aggregate(List<EnergyRecord> records, String granularity) {
            return Map.of();
        }
    }

    // ==================== 装配 ====================

    private final ToolCatalog catalog = new ToolCatalog(
            new DeviceTools(new StubTsdb(), new EnergyAggregator()), new RecordingTraceStore());

    private ShadowToolRouter router(LlmPort llm, AgentTraceStorePort trace) {
        return new ShadowToolRouter(llm, new PromptBuilder(), catalog, trace, true, 1, 3);
    }

    private static ToolCall call(String name, Object... kv) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) args.put(String.valueOf(kv[i]), kv[i + 1]);
        return new ToolCall(name, args);
    }

    /** 等异步比对落到 trace（影子调用在守护线程里跑） */
    private static void awaitRecords(RecordingTraceStore trace, int expected) throws InterruptedException {
        for (int i = 0; i < 100 && trace.records.size() < expected; i++) {
            Thread.sleep(20);
        }
        assertEquals(expected, trace.records.size(), "异步比对未在预期时间内写入 trace");
    }

    // ==================== 工具目录 ====================

    @Test
    @DisplayName("工具目录从 @Tool 注解反查，不需要另外手写一份清单")
    void catalogIsDerivedFromAnnotations() {
        List<String> names = catalog.definitions().stream().map(d -> d.name()).toList();

        assertTrue(names.contains("list_devices"), "实际: " + names);
        assertTrue(names.contains("query_energy_by_days"));
        assertTrue(names.contains("aggregate_energy"));
        assertTrue(catalog.contains("get_device_detail"));
        assertFalse(catalog.contains("no_such_tool"));
    }

    @Test
    @DisplayName("渲染给提示词时带上参数 schema，避免模型猜键名")
    void renderIncludesParameterSchema() {
        String rendered = catalog.renderForPrompt();

        assertTrue(rendered.contains("query_energy_by_date_range"));
        // 只给工具名和描述，模型会在 date / startDate 之间猜，而 FORCE_EXEC 那条路不做模糊匹配
        assertTrue(rendered.contains("startDate"), "参数 schema 未出现在提示词里: " + rendered);
    }

    // ==================== 判定逻辑 ====================

    @Test
    @DisplayName("工具与参数都相同判 AGREE")
    void agreeWhenIdentical() {
        ShadowToolRouter r = router(StubLlm.returning(""), new RecordingTraceStore());

        assertEquals(AGREE, r.judge(
                call("query_energy_by_days", "sn", "mv-003", "days", 30),
                call("query_energy_by_days", "sn", "mv-003", "days", 30)));
    }

    @Test
    @DisplayName("SN 大小写、数字字符串与数值差异不算分歧")
    void normalizesArgumentValues() {
        ShadowToolRouter r = router(StubLlm.returning(""), new RecordingTraceStore());

        assertEquals(AGREE, r.judge(
                call("query_energy_by_days", "sn", "mv-003", "days", 30),
                call("query_energy_by_days", "sn", "MV-003", "days", "30")));
    }

    @Test
    @DisplayName("一方省略了可选参数不算分歧（否则一致率毫无参考价值）")
    void missingOptionalArgumentIsNotDisagreement() {
        ShadowToolRouter r = router(StubLlm.returning(""), new RecordingTraceStore());

        assertEquals(AGREE, r.judge(
                call("query_energy_by_days", "sn", "mv-003", "days", 30),
                call("query_energy_by_days", "sn", "mv-003")));
    }

    @Test
    @DisplayName("选了不同工具判 TOOL_DIFF")
    void toolDiff() {
        ShadowToolRouter r = router(StubLlm.returning(""), new RecordingTraceStore());

        assertEquals(TOOL_DIFF, r.judge(
                call("query_energy_by_days", "sn", "mv-003", "days", 30),
                call("aggregate_energy", "sn", "mv-003", "granularity", "month")));
    }

    @Test
    @DisplayName("同工具不同参数判 ARGS_DIFF")
    void argsDiff() {
        ShadowToolRouter r = router(StubLlm.returning(""), new RecordingTraceStore());

        assertEquals(ARGS_DIFF, r.judge(
                call("query_energy_by_days", "sn", "mv-003", "days", 30),
                call("query_energy_by_days", "sn", "mv-008", "days", 30)));
    }

    @Test
    @DisplayName("启发式命中而 LLM 认为不该调工具 → HEURISTIC_ONLY（疑似正则劫持）")
    void heuristicOnlySignalsFalsePositive() {
        ShadowToolRouter r = router(StubLlm.returning(""), new RecordingTraceStore());

        assertEquals(HEURISTIC_ONLY, r.judge(call("query_energy_by_days", "sn", "mv-005", "days", 30), null));
    }

    @Test
    @DisplayName("启发式未命中而 LLM 认为该调工具 → LLM_ONLY（疑似正则漏判）")
    void llmOnlySignalsFalseNegative() {
        ShadowToolRouter r = router(StubLlm.returning(""), new RecordingTraceStore());

        assertEquals(LLM_ONLY, r.judge(null, call("list_devices")));
    }

    @Test
    @DisplayName("两边都不调工具判 BOTH_NONE")
    void bothNone() {
        ShadowToolRouter r = router(StubLlm.returning(""), new RecordingTraceStore());

        assertEquals(BOTH_NONE, r.judge(null, null));
    }

    // ==================== 输出解析 ====================

    @Test
    @DisplayName("能从代码块围栏和多余解释里抠出 JSON")
    void parsesJsonWithDecoration() {
        ShadowToolRouter r = router(StubLlm.returning(""), new RecordingTraceStore());

        ToolCall parsed = r.parse("""
                好的，我的判断如下：
                ```json
                {"tool": "query_energy_by_days", "arguments": {"sn": "mv-003", "days": 30}, "reason": "用户问近30天能耗"}
                ```
                """);

        assertNotNull(parsed);
        assertEquals("query_energy_by_days", parsed.name);
        assertEquals("mv-003", parsed.arguments.get("sn"));
        assertEquals(30, ((Number) parsed.arguments.get("days")).intValue());
    }

    @Test
    @DisplayName("tool 为 null 时解析成「不调工具」")
    void parsesExplicitNoTool() {
        ShadowToolRouter r = router(StubLlm.returning(""), new RecordingTraceStore());

        assertNull(r.parse("{\"tool\": null, \"arguments\": {}, \"reason\": \"闲聊\"}"));
        assertNull(r.parse("{\"tool\": \"null\", \"arguments\": {}}"));
    }

    @Test
    @DisplayName("模型编造不存在的工具名时按未命中处理，不污染比对")
    void rejectsHallucinatedToolName() {
        ShadowToolRouter r = router(StubLlm.returning(""), new RecordingTraceStore());

        assertNull(r.parse("{\"tool\": \"query_temperature\", \"arguments\": {\"sn\": \"mv-003\"}}"));
    }

    @Test
    @DisplayName("完全不是 JSON 时不抛异常")
    void toleratesNonJsonOutput() {
        ShadowToolRouter r = router(StubLlm.returning(""), new RecordingTraceStore());

        assertDoesNotThrow(() -> r.parse("我不确定该调用哪个工具。"));
        assertNull(r.parse("我不确定该调用哪个工具。"));
        assertNull(r.parse(null));
        assertNull(r.parse("   "));
    }

    // ==================== 绝不影响主流程 ====================

    @Test
    @DisplayName("比对结果写进 SHADOW_ROUTE 阶段，status 即判定结果")
    void recordsVerdictAsTraceStatus() throws InterruptedException {
        RecordingTraceStore trace = new RecordingTraceStore();
        ShadowToolRouter r = router(
                StubLlm.returning("{\"tool\": \"list_devices\", \"arguments\": {}}"), trace);

        r.compareAsync("列出所有设备", List.of(), "s1", call("list_devices", "keyword", ""));
        awaitRecords(trace, 1);

        Map<String, Object> rec = trace.records.peek();
        assertEquals("SHADOW_ROUTE", rec.get("stage"));
        assertEquals("AGREE", rec.get("status"));
        assertEquals("list_devices", rec.get("llmTool"));
    }

    @Test
    @DisplayName("LLM 抛异常时判 UNKNOWN，且不向调用方传播")
    void swallowsLlmFailure() throws InterruptedException {
        RecordingTraceStore trace = new RecordingTraceStore();
        ShadowToolRouter r = router(new StubLlm(m -> {
            throw new RuntimeException("connection refused");
        }), trace);

        assertDoesNotThrow(() -> r.compareAsync("列出所有设备", List.of(), "s1", call("list_devices")));
        awaitRecords(trace, 1);

        assertEquals("UNKNOWN", trace.records.peek().get("status"));
    }

    @Test
    @DisplayName("输出无法解析时判 UNKNOWN，不能混进「不需要工具」的统计")
    void unparseableOutputIsUnknownNotBothNone() throws InterruptedException {
        RecordingTraceStore trace = new RecordingTraceStore();
        ShadowToolRouter r = router(StubLlm.returning("模型今天不太想配合"), trace);

        r.compareAsync("今天天气怎么样", List.of(), "s1", null);
        awaitRecords(trace, 1);

        assertEquals("UNKNOWN", trace.records.peek().get("status"),
                "解析失败被算成 BOTH_NONE 会虚高一致率");
    }

    @Test
    @DisplayName("关闭开关后一次 LLM 调用都不产生")
    void disabledMakesNoLlmCall() throws InterruptedException {
        StubLlm llm = StubLlm.returning("{\"tool\": null}");
        ShadowToolRouter r = new ShadowToolRouter(
                llm, new PromptBuilder(), catalog, new RecordingTraceStore(), false, 1, 3);

        r.compareAsync("列出所有设备", List.of(), "s1", call("list_devices"));
        Thread.sleep(100);

        assertTrue(llm.calls.isEmpty(), "开关关闭后仍在调 LLM，等于白付一倍成本");
    }

    @Test
    @DisplayName("抽样生效：sample-every=3 时每 3 次请求只比对一次")
    void samplingLimitsCost() throws InterruptedException {
        RecordingTraceStore trace = new RecordingTraceStore();
        StubLlm llm = StubLlm.returning("{\"tool\": null}");
        ShadowToolRouter r = new ShadowToolRouter(llm, new PromptBuilder(), catalog, trace, true, 3, 3);

        for (int i = 0; i < 6; i++) {
            r.compareAsync("问题" + i, List.of(), "s1", null);
        }
        awaitRecords(trace, 2);

        assertEquals(2, llm.calls.size(), "抽样未生效");
    }

    @Test
    @DisplayName("历史里的长回答被截断后再喂给影子模型")
    void truncatesLongHistoryAnswers() throws InterruptedException {
        RecordingTraceStore trace = new RecordingTraceStore();
        StubLlm llm = StubLlm.returning("{\"tool\": null}");
        ShadowToolRouter r = router(llm, trace);
        String hugeTable = "|日期|用电量|\n".repeat(200);

        r.compareAsync("那近7天呢", List.of(Map.entry("mv-003近30天能耗", hugeTable)), "s1", null);
        awaitRecords(trace, 1);

        List<LlmPort.MessageDTO> sent = new ArrayList<>(llm.calls.peek());
        String assistantMsg = sent.stream()
                .filter(m -> m.role == LlmPort.MessageDTO.Role.ASSISTANT)
                .map(m -> m.content).findFirst().orElse("");
        assertTrue(assistantMsg.length() < 300, "历史里的大表未截断，影子调用会又贵又被淹没");
    }
}
