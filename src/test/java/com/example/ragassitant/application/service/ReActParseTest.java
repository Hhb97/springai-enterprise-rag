package com.example.ragassitant.application.service;

import com.example.ragassitant.application.service.ReActAgentAppService.StepDecision;
import com.example.ragassitant.domain.service.TextToolCallParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** ReAct 单步输出的三条解析路径：文本工具调用 → 结构化 JSON → 纯文本兜底。 */
class ReActParseTest {

    private final TextToolCallParser parser = new TextToolCallParser();

    private StepDecision parse(String raw) {
        return ReActAgentAppService.parseReActResponse(raw, parser);
    }

    @Test
    @DisplayName("空输出视为结束，给出明确提示而不是空答案")
    void blankIsFinal() {
        StepDecision d = parse("  ");
        assertTrue(d.isFinalAnswer());
        assertFalse(d.answer().isBlank());
    }

    @Test
    @DisplayName("文本形式的工具调用数组 → 工具步骤，前缀文字作为 thought")
    void textToolCallArray() {
        StepDecision d = parse("先看看设备清单\n[{\"name\":\"list_devices\",\"arguments\":{\"keyword\":\"\"}}]");
        assertFalse(d.isFinalAnswer());
        assertEquals("list_devices", d.toolCall().name);
        assertTrue(d.thought().contains("设备清单"));
    }

    @Test
    @DisplayName("结构化 JSON 带 tool → 工具步骤，参数原样透传")
    void structuredTool() {
        StepDecision d = parse("```json\n{\"thought\":\"查能耗\",\"tool\":\"query_energy_by_days\",\"arguments\":{\"sn\":\"mv-003\",\"days\":7}}\n```");
        assertFalse(d.isFinalAnswer());
        assertEquals("query_energy_by_days", d.toolCall().name);
        assertEquals("mv-003", d.toolCall().arguments.get("sn"));
        assertEquals(7, d.toolCall().arguments.get("days"));
    }

    @Test
    @DisplayName("结构化 JSON 中 tool 为字符串 \"null\" → 按最终回答处理")
    void structuredNullToolWithAnswer() {
        StepDecision d = parse("{\"thought\":\"无需工具\",\"tool\":\"null\",\"answer\":\"电压上限为额定电压的110%\"}");
        assertTrue(d.isFinalAnswer());
        assertEquals("电压上限为额定电压的110%", d.answer());
    }

    @Test
    @DisplayName("只有 thought 没有 answer → 用 thought 兜底为回答")
    void thoughtOnly() {
        StepDecision d = parse("{\"thought\":\"我已经掌握全部信息\"}");
        assertTrue(d.isFinalAnswer());
        assertEquals("我已经掌握全部信息", d.answer());
    }

    @Test
    @DisplayName("非 JSON 纯文本 → 直接作为最终回答")
    void plainText() {
        StepDecision d = parse("频率的合规范围为 58 ~ 62.5 Hz。");
        assertTrue(d.isFinalAnswer());
        assertTrue(d.answer().contains("62.5"));
    }
}
