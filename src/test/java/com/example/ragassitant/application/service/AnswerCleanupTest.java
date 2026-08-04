package com.example.ragassitant.application.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回答清洗后的「残渣」判定。
 *
 * <p>线上现场：{@code qwen2.5:1.5b} 把工具调用写成文本
 * {@code [{"name":"list_devices","arguments":{"keyword":""}}]}（52 字符），
 * 被 {@code cleanupAnswer} 的历史正则洗成 3 个字符 {@code [}]} 之后<b>原样展示给了用户</b>
 * （日志：最终回答长度=52 → 最终回答长度=3）。
 *
 * <p>原来的守卫只判 {@code isEmpty()}，而残渣非空，所以没拦住。
 */
class AnswerCleanupTest {

    @Test
    @DisplayName("纯符号残渣判定为无效回答")
    void detectsPunctuationOnlyResidue() {
        // 用户界面上实际看到的那 3 个字符
        assertTrue(AgentAppService.isMeaningless("[}]"), "「[}]」被当成了正常回答，会原样展示给用户");
        assertTrue(AgentAppService.isMeaningless("[]"));
        assertTrue(AgentAppService.isMeaningless("{}"));
        assertTrue(AgentAppService.isMeaningless("[{}]"));
        assertTrue(AgentAppService.isMeaningless("\"\":,"));
    }

    @Test
    @DisplayName("空、空白、null 判定为无效回答")
    void detectsBlank() {
        assertTrue(AgentAppService.isMeaningless(null));
        assertTrue(AgentAppService.isMeaningless(""));
        assertTrue(AgentAppService.isMeaningless("   \n\t "));
    }

    @Test
    @DisplayName("中文全角标点残渣同样判定为无效")
    void detectsFullWidthPunctuation() {
        assertTrue(AgentAppService.isMeaningless("：，。！"));
        assertTrue(AgentAppService.isMeaningless("（）【】"));
    }

    @Test
    @DisplayName("正常回答不被误判 —— 包含标点的表格和短句都必须放行")
    void doesNotRejectRealAnswers() {
        assertFalse(AgentAppService.isMeaningless("共 8 台设备。"));
        assertFalse(AgentAppService.isMeaningless("| ID | 设备名称 |\n|---|---|\n| 1 | 一号设备 |"));
        assertFalse(AgentAppService.isMeaningless("90%~110%"));
        assertFalse(AgentAppService.isMeaningless("OK"));
        assertFalse(AgentAppService.isMeaningless("0"));
    }
}
