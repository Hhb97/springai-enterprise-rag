package com.example.ragassitant.infrastructure.agent;

import com.example.ragassitant.domain.spi.AgentTraceStorePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「LLM 到底有没有自己调工具」必须有确定性判据。
 *
 * <p>改造前没有：
 * <ul>
 *   <li>{@code DeviceTools} 里的「🔧 调用工具」日志，原生通道和我们的反射通道
 *       （FORCE_EXEC / 文本兜底）<b>都会打</b>，分不出是谁决定的；</li>
 *   <li>适配器打的 {@code 本轮tool_calls} 只是最后一轮的值，健康流程下恒为 0，实际造成过误判；</li>
 *   <li>原生路径<b>一条 trace 都不写</b>，回放时看不到 LLM 调了什么工具、传了什么参数。</li>
 * </ul>
 * {@link TracingToolCallback} 只会在原生通道被真正走通时执行到，所以它写的
 * {@code NATIVE_TOOL_CALL} 记录就是那个判据。
 */
class TracingToolCallbackTest {

    static class RecordingTraceStore implements AgentTraceStorePort {
        final List<Map<String, Object>> records = new ArrayList<>();

        @Override
        public void recordDecision(String sessionId, int roundNo, String stage,
                                   String toolName, Map<String, Object> toolArguments, String toolResult,
                                   String llmInput, String llmOutput,
                                   boolean heuristicHit, String heuristicStrategy,
                                   Long durationMs, String status, String errorMessage) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stage", String.valueOf(stage));
            m.put("toolName", String.valueOf(toolName));
            m.put("args", String.valueOf(toolArguments));
            m.put("result", String.valueOf(toolResult));
            m.put("status", String.valueOf(status));
            m.put("error", String.valueOf(errorMessage));
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

    /** 最小 ToolCallback 桩，可控制返回值或抛异常 */
    static class StubCallback implements ToolCallback {
        private final String result;
        private final RuntimeException failure;
        int calls = 0;

        StubCallback(String result, RuntimeException failure) {
            this.result = result;
            this.failure = failure;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name("list_devices")
                    .description("查询设备列表")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"keyword\":{\"type\":\"string\"}}}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            calls++;
            if (failure != null) throw failure;
            return result;
        }
    }

    private final RecordingTraceStore trace = new RecordingTraceStore();

    @Test
    @DisplayName("原生通道调用工具时写入 NATIVE_TOOL_CALL 记录")
    void recordsNativeToolCall() {
        StubCallback stub = new StubCallback("## 设备清单（共 8 台）", null);
        ToolCallback wrapped = new TracingToolCallback(stub, trace);

        String result = wrapped.call("{\"keyword\":\"\"}");

        assertEquals("## 设备清单（共 8 台）", result, "装饰器改变了工具返回值");
        assertEquals(1, stub.calls, "工具没有被真正执行");
        assertEquals(1, trace.records.size());
        Map<String, Object> rec = trace.records.get(0);
        assertEquals("NATIVE_TOOL_CALL", rec.get("stage"),
                "缺少 NATIVE_TOOL_CALL 记录，就无法区分是 LLM 自主调用还是反射路径调用");
        assertEquals("list_devices", rec.get("toolName"));
        assertEquals("OK", rec.get("status"));
        assertTrue(String.valueOf(rec.get("args")).contains("keyword"), "原始入参没有留档");
    }

    @Test
    @DisplayName("工具定义透传，不影响框架生成的 schema")
    void delegatesToolDefinition() {
        ToolCallback wrapped = new TracingToolCallback(new StubCallback("ok", null), trace);

        assertEquals("list_devices", wrapped.getToolDefinition().name());
        assertEquals("查询设备列表", wrapped.getToolDefinition().description());
        assertTrue(wrapped.getToolDefinition().inputSchema().contains("keyword"));
    }

    @Test
    @DisplayName("工具抛异常时记 ERROR 并把异常继续向上抛（交给框架处理）")
    void recordsFailureAndRethrows() {
        StubCallback stub = new StubCallback(null, new IllegalStateException("InfluxDB 连接失败"));
        ToolCallback wrapped = new TracingToolCallback(stub, trace);

        assertThrows(IllegalStateException.class, () -> wrapped.call("{}"),
                "异常被吞掉了，框架就没机会把错误反馈给模型");
        assertEquals(1, trace.records.size());
        assertEquals("ERROR", trace.records.get(0).get("status"));
        assertTrue(String.valueOf(trace.records.get(0).get("error")).contains("InfluxDB"));
    }

    @Test
    @DisplayName("trace 写入失败不能影响工具调用本身")
    void traceFailureDoesNotBreakToolCall() {
        AgentTraceStorePort broken = new RecordingTraceStore() {
            @Override
            public void recordDecision(String s, int r, String stage, String tn, Map<String, Object> ta,
                                       String tr, String li, String lo, boolean hh, String hs,
                                       Long d, String st, String em) {
                throw new RuntimeException("H2 写入失败");
            }
        };
        ToolCallback wrapped = new TracingToolCallback(new StubCallback("ok", null), broken);

        assertEquals("ok", assertDoesNotThrow(() -> wrapped.call("{}")));
    }

    @Test
    @DisplayName("工具目录同时提供裸回调与带 trace 的回调，数量一致")
    void catalogExposesBothVariants() {
        ToolCatalog catalog = new ToolCatalog(
                new DeviceTools(new SpringAiToolRegistryTest.StubTsdb(),
                        new com.example.ragassitant.domain.service.EnergyAggregator()),
                trace);

        assertEquals(catalog.toolCallbacks().size(), catalog.tracingToolCallbacks().size());
        assertFalse(catalog.tracingToolCallbacks().isEmpty());
        assertTrue(catalog.tracingToolCallbacks().stream().allMatch(cb -> cb instanceof TracingToolCallback),
                "注册给 LLM 的回调没有被装饰，NATIVE_TOOL_CALL 判据就失效了");
    }
    private static TracingToolCallback withDirect(ToolCallback stub, AgentTraceStorePort store) {
        var props = new com.example.ragassitant.infrastructure.governance.GovernanceProperties();
        var policy = new com.example.ragassitant.infrastructure.governance.GovernanceProperties.ToolPolicy();
        policy.setReturnDirect(true);
        props.getTools().put("list_devices", policy);
        var governance = new com.example.ragassitant.infrastructure.governance.ToolGovernance(
                props, null, null, com.example.ragassitant.infrastructure.observability.AgentMetrics.noop(),
                System::currentTimeMillis);
        return new TracingToolCallback(stub, store, governance,
                com.example.ragassitant.infrastructure.observability.AgentMetrics.noop());
    }

    @Test
    @DisplayName("governance 配置直出时，metadata 标记 returnDirect，且还原被 JSON 编码的字符串结果")
    void returnDirectUnquotesStringResult() {
        String encoded = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree("## 设备清单").toString();
        TracingToolCallback wrapped = withDirect(new StubCallback(encoded, null), trace);

        assertTrue(wrapped.getToolMetadata().returnDirect());
        assertEquals("## 设备清单", wrapped.call("{}"));
    }

    @Test
    @DisplayName("未配置直出时不改写结果")
    void noReturnDirectByDefault() {
        String encoded = "\"abc\"";
        ToolCallback wrapped = new TracingToolCallback(new StubCallback(encoded, null), trace);

        assertFalse(wrapped.getToolMetadata().returnDirect());
        assertEquals(encoded, wrapped.call("{}"));
    }

    @Test
    @DisplayName("unquote 对非 JSON 字符串原样返回")
    void unquoteLeavesPlainTextAlone() {
        assertEquals("plain", TracingToolCallback.unquote("plain"));
        assertEquals("{\"a\":1}", TracingToolCallback.unquote("{\"a\":1}"));
        assertNull(TracingToolCallback.unquote(null));
    }
}
