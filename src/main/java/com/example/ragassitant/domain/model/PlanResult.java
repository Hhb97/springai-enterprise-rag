package com.example.ragassitant.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Planner 的结构化输出契约，交给 {@code LlmPort#chatStructured} 生成 JSON Schema 格式约束。
 *
 * <p>字段必须和 {@code PromptBuilder#buildPlanSystemPrompt} 里示例的键名一致（尤其是 {@code depends_on}），
 * 否则 schema 和提示词会互相打架。真正的解析仍走各服务的 {@code parsePlan(raw)} ——
 * 它对「depends_on 写成字符串」「tool 写成 "null"」这类弱模型常见偏差更宽容。
 */
public record PlanResult(List<Step> steps) {

    public record Step(Integer step,
                       String tool,
                       Map<String, Object> arguments,
                       @JsonProperty("depends_on") List<Integer> dependsOn) {
    }
}
