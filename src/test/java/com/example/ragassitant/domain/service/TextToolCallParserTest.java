package com.example.ragassitant.domain.service;

import com.example.ragassitant.domain.model.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 弱模型兜底：识别「模型把工具调用写成文本」。
 *
 * <p>线上现场（关掉启发式路由、chat-backend=ollama/qwen2.5:1.5b）：
 * 用户问「列出所有设备」，模型没走原生 tool_calls 通道，而是返回 52 个字符的
 * {@code [{"name":"list_devices","arguments":{"keyword":""}}]}。框架看不到 tool_calls，
 * 把它当最终答案；再经 {@code cleanupAnswer} 的历史正则清洗，剩下 3 个字符 {@code [}]}
 * 直接展示给用户（日志里就是「最终回答长度=52」→「最终回答长度=3」）。
 */
class TextToolCallParserTest {

    private final TextToolCallParser parser = new TextToolCallParser();

    /** 线上实际观测到的那 52 个字符 */
    private static final String REAL_CASE =
            "[{\"name\":\"list_devices\",\"arguments\":{\"keyword\":\"\"}}]";

    @Test
    @DisplayName("识别线上实际出现的那一条文本工具调用")
    void parsesTheRealWorldCase() {
        assertEquals(52, REAL_CASE.length(), "样本和线上日志里的长度不一致，用例失去意义");

        List<ToolCall> calls = parser.parse(REAL_CASE);

        assertEquals(1, calls.size());
        assertEquals("list_devices", calls.get(0).name);
        assertEquals("", calls.get(0).arguments.get("keyword"));
    }

    @Test
    @DisplayName("嵌套参数对象能正确解析（旧 ToolCallParser 的正则做不到）")
    void parsesNestedArguments() {
        List<ToolCall> calls = parser.parse(
                "[{\"name\":\"query_energy_by_date_range\",\"arguments\":{\"sn\":\"mv-003\","
                + "\"startDate\":\"2026-06-01\",\"endDate\":\"2026-06-30\"}}]");

        assertEquals(1, calls.size());
        ToolCall c = calls.get(0);
        assertEquals("query_energy_by_date_range", c.name);
        assertEquals("mv-003", c.arguments.get("sn"));
        assertEquals("2026-06-01", c.arguments.get("startDate"));
        assertEquals("2026-06-30", c.arguments.get("endDate"));
    }

    @Test
    @DisplayName("数字参数保持数值类型，不退化成字符串")
    void keepsNumericArguments() {
        List<ToolCall> calls = parser.parse(
                "[{\"name\":\"query_energy_by_days\",\"arguments\":{\"sn\":\"mv-003\",\"days\":30}}]");

        assertEquals(30, ((Number) calls.get(0).arguments.get("days")).intValue());
    }

    @Test
    @DisplayName("代码块围栏、前后解释文字都能容忍")
    void toleratesDecoration() {
        List<ToolCall> calls = parser.parse("""
                我需要调用工具来查询：
                ```json
                [{"name":"list_devices","arguments":{}}]
                ```
                稍等。
                """);

        assertEquals(1, calls.size());
        assertEquals("list_devices", calls.get(0).name);
    }

    @Test
    @DisplayName("单个对象（不带数组外壳）也能识别")
    void parsesBareObject() {
        List<ToolCall> calls = parser.parse("{\"name\":\"get_device_detail\",\"arguments\":{\"sn\":\"mv-007\"}}");

        assertEquals(1, calls.size());
        assertEquals("get_device_detail", calls.get(0).name);
        assertEquals("mv-007", calls.get(0).arguments.get("sn"));
    }

    @Test
    @DisplayName("正常的自然语言回答不能被误认成工具调用")
    void doesNotMisfireOnNormalAnswers() {
        assertTrue(parser.parse("目前系统中共有 8 台设备，其中 7 台在线。").isEmpty());
        assertTrue(parser.parse("电压合规范围是额定电压的 90%~110%。").isEmpty());
        // 回答里恰好含 JSON，但没有 name 字段 → 不是工具调用
        assertTrue(parser.parse("配置示例：{\"topK\": 3, \"threshold\": 0.4}").isEmpty());
        assertTrue(parser.parse(null).isEmpty());
        assertTrue(parser.parse("  ").isEmpty());
    }

    @Test
    @DisplayName("残缺 JSON 不抛异常，当普通文本处理")
    void toleratesBrokenJson() {
        assertDoesNotThrow(() -> parser.parse("[{\"name\":\"list_devices\",\"arguments\":"));
        assertTrue(parser.parse("[{\"name\":\"list_devices\",\"arguments\":").isEmpty());
    }
}
