package com.example.ragassitant.domain.service;

import java.util.List;
import java.util.Map;

/**
 * Agent 链路共用的纯文本处理，无状态、无依赖。
 *
 * <p>【为什么抽出来】改造前 {@code AgentAppService} / {@code PlanThenExecuteAppService} /
 * {@code ReActAgentAppService} 三个服务各自复制了一份 {@code cleanupAnswer} / {@code truncate} /
 * {@code formatHistory}，其中 {@code cleanupAnswer} 已经开始漂移（ReAct 那份多了
 * Thought/Action/Observation 前缀的剥离）。同一个「模型把工具调用写成文本」的 bug，
 * 修一处不等于三处都修好 —— 这类跨服务复制是最难发现的缺陷来源。
 */
public final class AgentTextUtils {

    private AgentTextUtils() {}

    /**
     * 清洗模型输出里的工具调用残留与 ReAct 标记。
     *
     * <p>合并了原三份实现，并保留 ReAct 版对 {@code Thought/Action/Observation/Answer} 前缀的剥离
     * （对另外两条链路无害 —— 它们的模型本就不该输出这些前缀，输出了也是噪声）。
     *
     * <p><b>顺带修了一个哑火的正则</b>：原 ReAct 版写的是 {@code "^Thought[：:].*$"}，
     * 没有 {@code (?m)} 标志，{@code ^}/{@code $} 只匹配整个字符串的首尾 ——
     * 除非整段回答就是一行 {@code Thought: xxx}，否则这四条规则从未生效过。
     * 这里补上多行标志让它按原意工作。
     */
    public static String cleanupAnswer(String answer) {
        if (answer == null) return "";
        String cleaned = answer.replaceAll("(?s)\\[Tools?\\].*?(?=\\n(?:## |### |回答：|基于|综上|因此|总结|设备|根据)|$)", "");
        cleaned = cleaned.replaceAll("(?s)\\{\\s*\"name\"\\s*:.*?\"arguments\"\\s*:.*?\\}", "");
        cleaned = cleaned.replaceAll("(?s)\\[\\s*\\{\\s*\"name\".*?\\}\\s*\\]", "");
        cleaned = cleaned.replace("```json", "").replace("```", "");
        cleaned = cleaned.replaceAll("(?m)^Thought[：:].*$", "");
        cleaned = cleaned.replaceAll("(?m)^Action[：:].*$", "");
        cleaned = cleaned.replaceAll("(?m)^Observation[：:].*$", "");
        cleaned = cleaned.replaceAll("(?m)^Answer[：:].*$", "");
        return cleaned.trim();
    }

    /**
     * 清洗后是否只剩标点/括号残渣。
     *
     * <p>线上现场：模型把工具调用写成文本 {@code [{"name":...}]}（52 字符），
     * 被上面的正则洗成 3 个字符 {@code [}]} 后<b>原样展示给了用户</b>。
     * 只判 {@code isEmpty()} 拦不住，因为残渣非空。
     */
    public static boolean isMeaningless(String answer) {
        if (answer == null) return true;
        return answer.replaceAll("[\\s\\p{Punct}\\p{IsPunctuation}]", "").isEmpty();
    }

    /** 截断长文本，超长时补省略号。 */
    public static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * 把最近若干轮问答渲染成提示词里的历史片段。
     *
     * <p>单条截断到 200 字符：历史里的助手回复可能是整张能耗表，全塞进去既贵又会淹没提问本身。
     */
    public static String formatHistory(List<Map.Entry<String, String>> history, int maxPairs) {
        if (history == null || history.isEmpty()) return "";
        int from = Math.max(0, history.size() - Math.max(1, maxPairs));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> pair : history.subList(from, history.size())) {
            sb.append("用户: ").append(truncate(pair.getKey(), 200)).append('\n');
            sb.append("助手: ").append(truncate(pair.getValue(), 200)).append('\n');
        }
        return sb.toString();
    }

    /**
     * 从模型输出里抠出第一个完整 JSON 对象，取不到返回 null。
     *
     * <p>小模型几乎必然会加代码块围栏或前后解释，直接 parse 会失败。
     */
    public static String extractJson(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("```(?:json)?", "").trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return s.substring(start, end + 1);
    }
}
