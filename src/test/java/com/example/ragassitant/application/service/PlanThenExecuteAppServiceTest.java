package com.example.ragassitant.application.service;

import com.example.ragassitant.domain.model.Device;
import com.example.ragassitant.domain.model.EnergyRecord;
import com.example.ragassitant.domain.model.PlanStep;
import com.example.ragassitant.domain.model.ToolCall;
import com.example.ragassitant.domain.service.EnergyAggregator;
import com.example.ragassitant.domain.spi.AgentTraceStorePort;
import com.example.ragassitant.domain.spi.ConversationStorePort;
import com.example.ragassitant.domain.spi.LlmPort;
import com.example.ragassitant.domain.spi.TimeSeriesDbPort;
import com.example.ragassitant.domain.spi.ToolRegistryPort;
import com.example.ragassitant.domain.service.PromptBuilder;
import com.example.ragassitant.domain.service.PromptInjectionDetector;
import com.example.ragassitant.infrastructure.agent.DeviceTools;
import com.example.ragassitant.infrastructure.agent.ToolCatalog;
import com.example.ragassitant.infrastructure.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plan-then-Execute 规划解析的回归测试。
 *
 * <p>重点盯 Planner 输出的防御性解析：大模型可能给 code-fence、未知工具、
 * 没写 tool 的占位步骤、乱序 step 号、甚至垃圾文本 —— 全部要安全降级而不是抛异常。
 */
class PlanThenExecuteAppServiceTest {

    private PlanThenExecuteAppService service;

    @BeforeEach
    void setUp() {
        DeviceTools deviceTools = new DeviceTools(new StubTimeSeriesDb(), new EnergyAggregator());
        ToolCatalog toolCatalog = new ToolCatalog(deviceTools, new NoopTraceStore());
        service = new PlanThenExecuteAppService(
                new NoopLlm(), new NoopConversationStore(), new NoopToolRegistry(),
                new PromptBuilder(), new NoopTraceStore(), null, null,
                toolCatalog, new PromptInjectionDetector(), new AgentProperties());
    }

    // ==================== 桩 ====================

    static class StubTimeSeriesDb implements TimeSeriesDbPort {
        @Override public List<Device> listBuiltinDevices(String keyword) { return List.of(); }
        @Override public Device findDeviceBySn(String sn) { return null; }
        @Override public List<EnergyRecord> queryEnergyByDays(String sn, int days) { return List.of(); }
        @Override public List<EnergyRecord> queryEnergyByDateRange(String sn, LocalDate startDate, LocalDate endDate) { return List.of(); }
        @Override public Map<String, Double> aggregate(List<EnergyRecord> records, String granularity) { return Map.of(); }
    }

    static class NoopTraceStore implements AgentTraceStorePort {
        @Override public void recordDecision(String sessionId, int roundNo, String stage, String toolName,
                                             Map<String, Object> toolArguments, String toolResult,
                                             String llmInput, String llmOutput, boolean heuristicHit,
                                             String heuristicStrategy, Long durationMs, String status,
                                             String errorMessage) { }
        @Override public List<Map<String, Object>> queryBySession(String sessionId) { return List.of(); }
        @Override public List<Map<String, Object>> queryRecent(int limit) { return List.of(); }
        @Override public long countSince(LocalDateTime since) { return 0; }
    }

    static class NoopLlm implements LlmPort {
        @Override public String chat(List<String> system, List<String> history, List<String> assistant, String user) { return ""; }
        @Override public String chatWithRawMessages(List<MessageDTO> messages) { return ""; }
        @Override public Flux<String> chatStreamWithRawMessages(List<MessageDTO> messages) { return Flux.empty(); }
        @Override public String chatWithAutoToolCalling(List<MessageDTO> messages, int maxIterations, List<Object> tools) { return ""; }
    }

    static class NoopConversationStore implements ConversationStorePort {
        @Override public void saveUser(String sessionId, String userMessage) { }
        @Override public void saveAssistant(String sessionId, String assistantMessage) { }
        @Override public List<Map.Entry<String, String>> getHistory(String sessionId, int maxPairs) { return List.of(); }
        @Override public void clearExpired() { }
    }

    static class NoopToolRegistry implements ToolRegistryPort {
        @Override public String invoke(ToolCall toolCall) { return ""; }
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("解析合法计划：step/tool/arguments/depends_on 全部正确")
    void parsesValidPlan() {
        String raw = """
                {"steps":[
                  {"step":1,"tool":"list_devices","arguments":{"keyword":""},"depends_on":[]},
                  {"step":2,"tool":"get_device_detail","arguments":{"sn":"$1"},"depends_on":[1]}
                ]}""";
        List<PlanStep> plan = service.parsePlan(raw, "s");
        assertEquals(2, plan.size());
        assertEquals(1, plan.get(0).step);
        assertEquals("list_devices", plan.get(0).tool);
        assertEquals(List.of(), plan.get(0).dependsOn);
        assertEquals(2, plan.get(1).step);
        assertEquals("get_device_detail", plan.get(1).tool);
        assertEquals(List.of(1), plan.get(1).dependsOn);
        assertEquals("$1", plan.get(1).arguments.get("sn"));
    }

    @Test
    @DisplayName("Planner 输出裹了 markdown code-fence 也能解析")
    void parsesCodeFencedJson() {
        String raw = """
                ```json
                {"steps":[{"step":1,"tool":"query_energy_by_days","arguments":{"sn":"SN-001","days":7}}]}
                ```
                """;
        List<PlanStep> plan = service.parsePlan(raw, "s");
        assertEquals(1, plan.size());
        assertEquals("query_energy_by_days", plan.get(0).tool);
    }

    @Test
    @DisplayName("未知工具与空 tool 的占位步骤被剔除，其余保留")
    void dropsUnknownAndPlaceholderSteps() {
        String raw = """
                {"steps":[
                  {"step":1,"tool":"list_devices","arguments":{}},
                  {"step":2,"tool":"no_such_tool","arguments":{}},
                  {"step":3,"tool":null,"arguments":{}},
                  {"step":4,"tool":"get_weather","arguments":{"city":"北京"}}
                ]}""";
        List<PlanStep> plan = service.parsePlan(raw, "s");
        assertEquals(2, plan.size());
        assertEquals("list_devices", plan.get(0).tool);
        assertEquals("get_weather", plan.get(1).tool);
    }

    @Test
    @DisplayName("数字形式 depends_on 与字符串形式都解析")
    void parsesBothDepForms() {
        String raw = """
                {"steps":[
                  {"step":1,"tool":"list_devices"},
                  {"step":2,"tool":"get_device_detail","depends_on":[1,"1"]}
                ]}""";
        List<PlanStep> plan = service.parsePlan(raw, "s");
        assertEquals(List.of(1, 1), plan.get(1).dependsOn);
    }

    @Test
    @DisplayName("垃圾输出/空输出/非对象根 都降级为空计划，不抛异常")
    void degradesGracefullyOnGarbage() {
        assertTrue(service.parsePlan("今天天气不错", "s").isEmpty());
        assertTrue(service.parsePlan("   ", "s").isEmpty());
        assertTrue(service.parsePlan(null, "s").isEmpty());
        assertTrue(service.parsePlan("[1,2,3]", "s").isEmpty());
        assertTrue(service.parsePlan("{\"plan\":\"没有 steps 字段\"}", "s").isEmpty());
    }

    @Test
    @DisplayName("依赖解析忽略非法值（0、负数、非数字）")
    void ignoresInvalidDeps() {
        String raw = """
                {"steps":[
                  {"step":1,"tool":"list_devices"},
                  {"step":2,"tool":"get_device_detail","depends_on":[0,-1,"abc","3"]}
                ]}""";
        List<PlanStep> plan = service.parsePlan(raw, "s");
        assertEquals(List.of(3), plan.get(1).dependsOn);
    }
}
