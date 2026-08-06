package com.example.ragassitant.domain.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * Prompt Injection（提示词注入）检测器。
 *
 * <p>检测用户输入中是否包含试图绕过系统提示词、角色限制或安全策略的指令。
 * 这是防御层，不依赖大模型自身的判断，而是通过代码层面的模式匹配来拦截攻击。
 */
@Slf4j
@Service
public class PromptInjectionDetector {

    /**
     * 检测是否包含 Prompt Injection 攻击模式。
     *
     * @param userInput 用户输入
     * @return 如果检测到攻击模式，返回 true
     */
    public boolean isPromptInjection(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }

        String lower = userInput.toLowerCase().trim();

        // 1. 检测"忽略/绕过系统提示词"的攻击
        if (containsIgnoreInstructions(lower)) {
            log.warn("🛡️ 检测到 Prompt Injection 攻击（忽略指令）: {}", truncate(userInput, 100));
            return true;
        }

        // 2. 检测"改变角色"的攻击
        if (containsRoleChange(lower)) {
            log.warn("🛡️ 检测到 Prompt Injection 攻击（改变角色）: {}", truncate(userInput, 100));
            return true;
        }

        // 3. 检测"绕过规则"的攻击
        if (containsRuleBypass(lower)) {
            log.warn("🛡️ 检测到 Prompt Injection 攻击（绕过规则）: {}", truncate(userInput, 100));
            return true;
        }

        return false;
    }

    /**
     * 获取攻击类型描述。
     */
    public String getAttackType(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return null;
        }

        String lower = userInput.toLowerCase().trim();

        if (containsIgnoreInstructions(lower)) {
            return "忽略系统指令攻击";
        }
        if (containsRoleChange(lower)) {
            return "角色篡改攻击";
        }
        if (containsRuleBypass(lower)) {
            return "规则绕过攻击";
        }

        return null;
    }

    /**
     * 检测是否包含"忽略/绕过系统提示词"的模式。
     */
    private boolean containsIgnoreInstructions(String lower) {
        List<String> patterns = Arrays.asList(
                "忽略所有", "忽略系统", "忽略prompt", "忽略提示",
                "ignore all", "ignore system", "ignore prompt", "ignore instruction",
                " disregard", "forget all", "forget previous",
                "绕过系统", "绕过prompt", "绕过提示词",
                "bypass system", "bypass prompt",
                "覆盖系统", "覆盖prompt", "覆盖提示",
                "override system", "override prompt"
        );

        return patterns.stream().anyMatch(lower::contains);
    }

    /**
     * 检测是否包含"改变角色"的模式。
     */
    private boolean containsRoleChange(String lower) {
        List<String> patterns = Arrays.asList(
                "你是一个新的", "你是一个不同的", "你现在是",
                "you are now a", "you are a new", "act as a new",
                "扮演一个", "假装你是", "模拟一个",
                "pretend to be", "simulate a", "roleplay as",
                "改变你的角色", "change your role", "switch role"
        );

        return patterns.stream().anyMatch(lower::contains);
    }

    /**
     * 检测是否包含"绕过规则"的模式。
     */
    private boolean containsRuleBypass(String lower) {
        List<String> patterns = Arrays.asList(
                "不要遵守", "不要遵循", "不要执行", "不要按照",
                "don't follow", "do not follow", "ignore the rules",
                "违反规则", "打破规则", "无视规则",
                "break the rules", "violate the rules",
                "解除限制", "解除约束", "解除安全",
                "remove restrictions", "remove constraints", "remove safety",
                "关闭安全", "关闭过滤", "关闭拦截",
                "disable safety", "disable filter", "disable security"
        );

        return patterns.stream().anyMatch(lower::contains);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}