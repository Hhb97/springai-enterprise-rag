package com.example.ragassitant.domain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent 系统提示词不能和原生 Tool Calling 对着干。
 *
 * <p>线上事故：提示词里残留着手写 Agent 循环时代的一条指令
 * 「需要调工具时，只输出 JSON 数组：[{"name":...,"arguments":...}]」。
 * qwen3.7-plus 完全照做，于是绕开了 {@code tool_calls} 通道 ——
 * 框架看不到工具调用，把那段 JSON 当最终答案，再被清洗成「[}]」展示给用户。
 * 当时先被误判成「模型不支持 function calling」，其实是提示词的问题。
 *
 * <p>这个测试是防止那条指令（或任何等价写法）被重新加回来。
 */
class AgentSystemPromptTest {

    private final String prompt = new PromptBuilder().buildAgentSystemPrompt();

    @Test
    @DisplayName("绝不能指示模型把工具调用输出成 JSON 文本")
    void neverInstructsJsonToolCallOutput() {
        assertFalse(prompt.contains("只输出 JSON"),
                "提示词又在要求模型输出 JSON 工具调用，会绕开原生 tool_calls 通道");
        assertFalse(prompt.contains("只输出 json"));
        // 「输出格式」章节里不能再出现 name/arguments 这种调用模板
        assertFalse(prompt.matches("(?s).*输出格式.*\"name\"\\s*:.*"),
                "输出格式章节里出现了工具调用模板");
    }

    @Test
    @DisplayName("必须明确禁止把调用写成文本")
    void explicitlyForbidsTextualToolCalls() {
        assertTrue(prompt.contains("严禁把工具调用写成文本"),
                "缺少显式禁止，模型仍可能自发把调用打印出来");
    }

    @Test
    @DisplayName("要引导模型使用自身的工具调用能力")
    void tellsModelToUseNativeToolCalling() {
        assertTrue(prompt.contains("工具调用能力"));
    }

    @Test
    @DisplayName("不再手写工具清单（避免和 @Tool 注解漂移）")
    void doesNotDuplicateToolSchemas() {
        // 参数签名类内容一律不该出现在提示词里，框架会自动注入 schema
        assertFalse(prompt.contains("query_energy_by_date_range(sn, startDate, endDate)"),
                "提示词里又抄了一份工具签名，改注解时会忘记同步");
        assertFalse(prompt.contains("## 工具清单"));
    }

    @Test
    @DisplayName("仍然保留反幻觉与格式约束")
    void keepsAntiHallucinationRules() {
        assertTrue(prompt.contains("禁止编造"));
        assertTrue(prompt.contains("Markdown 表格"));
    }

    @Test
    @DisplayName("仍然告知「已有工具结果时不要重复调用」——FORCE_EXEC 路径依赖这一条")
    void keepsNoRepeatCallRule() {
        assertTrue(prompt.contains("不要重复调用"));
    }
}
