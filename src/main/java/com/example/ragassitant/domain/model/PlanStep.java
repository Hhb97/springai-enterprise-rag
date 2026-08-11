package com.example.ragassitant.domain.model;

import java.util.List;
import java.util.Map;

/**
 * Plan-then-Execute 流程中的一条计划步骤（由 Planner 一次生成，供 Executor 执行）。
 *
 * <p>与 {@link ToolCall} 的区别：ToolCall 是一次"已确定的工具调用"，PlanStep 额外携带
 * <b>依赖关系</b>（{@code dependsOn}）—— Executor 据此做拓扑调度，无依赖的步骤可并行执行。
 *
 * <p>参数占位符：arguments 中的字符串值可引用前面步骤的产物，形如 {@code "$3"}，
 * Executor 执行前会替换成第 3 步的 Observation（截断）。这是 ReWOO 风格
 * 「Planner 盲于 Observation」的关键：Planner 一次写完整计划，不需要中间结果也能引用它们。
 */
public class PlanStep {

    /** 步骤序号，从 1 开始。 */
    public final int step;

    /** 工具名；为 null 表示"本步不调工具"（仅用于占位，实际由 Solver 收口）。 */
    public final String tool;

    /** 工具参数，值可能含 {@code $N} 占位符。 */
    public final Map<String, Object> arguments;

    /** 依赖的前置步骤号，可为空；执行时必须等它们全部完成。 */
    public final List<Integer> dependsOn;

    public PlanStep(int step, String tool, Map<String, Object> arguments, List<Integer> dependsOn) {
        this.step = step;
        this.tool = tool;
        this.arguments = arguments == null ? Map.of() : arguments;
        this.dependsOn = dependsOn == null ? List.of() : dependsOn;
    }

    @Override
    public String toString() {
        return "PlanStep{step=" + step + ", tool=" + tool + ", arguments=" + arguments + ", dependsOn=" + dependsOn + '}';
    }
}
