package com.example.ragassitant.application.eval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * 规则打分：纯函数，不调 LLM。
 *
 * <p>刻意不用「LLM 当裁判」：裁判模型自己也会漂移，基线就不稳定；而本项目的答案大多是可核对的事实
 * （限值、型号、SN、调用了哪个工具），关键词 + 工具断言已经能覆盖绝大部分回归。
 * 需要语义判断的用例（如「解释得是否清楚」）不适合放进门禁集。
 */
public final class EvalScorer {

    /** 拒答判定词。和 RagAppService.NO_RELEVANT_INFO_ANSWER、各提示词里要求的拒答话术保持一致。 */
    static final List<String> REFUSAL_MARKERS = List.of(
            "没有找到", "未找到相关", "暂无相关", "无相关信息", "没有相关信息", "知识库中没有", "无法回答", "不在知识库");

    private EvalScorer() {}

    public record CaseResult(String id, String category, boolean passed, List<String> failures,
                             String answerPreview, List<String> toolsUsed, long latencyMs) {
    }

    public static CaseResult score(GoldenSet.Case c, String answer, Collection<String> toolsUsed, long latencyMs) {
        List<String> failures = new ArrayList<>();
        String text = answer == null ? "" : answer;
        String lower = text.toLowerCase(Locale.ROOT);
        GoldenSet.Answer a = c.answer();
        List<String> tools = toolsUsed == null ? List.of() : List.copyOf(toolsUsed);

        if (a != null) {
            if (a.contains() != null) {
                for (String kw : a.contains()) {
                    if (!lower.contains(kw.toLowerCase(Locale.ROOT))) failures.add("缺少关键词: " + kw);
                }
            }
            if (a.containsAny() != null) {
                for (List<String> group : a.containsAny()) {
                    boolean hit = group.stream().anyMatch(kw -> lower.contains(kw.toLowerCase(Locale.ROOT)));
                    if (!hit) failures.add("缺少任一关键词: " + group);
                }
            }
            if (a.notContains() != null) {
                for (String kw : a.notContains()) {
                    if (lower.contains(kw.toLowerCase(Locale.ROOT))) failures.add("出现了不该出现的内容: " + kw);
                }
            }
            if (a.tools() != null) {
                for (String t : a.tools()) {
                    if (!tools.contains(t)) failures.add("未调用工具: " + t + "（实际: " + tools + "）");
                }
            }
            if (a.notTools() != null) {
                for (String t : a.notTools()) {
                    if (tools.contains(t)) failures.add("不该调用工具: " + t);
                }
            }
            if (a.refusal() != null) {
                boolean refused = isRefusal(text);
                if (a.refusal() && !refused) failures.add("应拒答但给出了回答");
                if (!a.refusal() && refused) failures.add("不应拒答但拒答了");
            }
        }
        if (text.isBlank()) failures.add("回答为空");

        String preview = text.length() > 300 ? text.substring(0, 300) + "…" : text;
        return new CaseResult(c.id(), c.category(), failures.isEmpty(), failures, preview, tools, latencyMs);
    }

    static boolean isRefusal(String answer) {
        if (answer == null) return false;
        return REFUSAL_MARKERS.stream().anyMatch(answer::contains);
    }
}
