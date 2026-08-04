package com.example.ragassitant.application.service;

import com.example.ragassitant.domain.model.ToolCall;
import com.example.ragassitant.domain.service.PromptBuilder;
import com.example.ragassitant.domain.spi.AgentTraceStorePort;
import com.example.ragassitant.domain.spi.LlmPort;
import com.example.ragassitant.domain.spi.LlmPort.MessageDTO;
import com.example.ragassitant.domain.spi.LlmPort.MessageDTO.Role;
import com.example.ragassitant.infrastructure.agent.ToolCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 影子路由：在不影响线上行为的前提下，测量「LLM 自主选工具」和「启发式正则路由」的差异。
 *
 * <p><b>为什么需要它</b>：把 {@code HeuristicRouter} 换成 LLM 自主调用是明确的方向
 * （正则表的规则数随工具数线性增长且互相干扰，几十个工具后无法维护），
 * 但直接切换是拿线上质量赌一个没验证过的假设。影子模式先把两者的选择都记进 AgentTrace，
 * 攒够真实样本、看到一致率和分歧的具体分布之后再决定 —— 这也顺便产出了评测集的素材。
 *
 * <p>三条硬性约束（和 {@link QueryRewriteService} 同一套原则）：
 * <ol>
 *   <li><b>不能影响用户</b>：异步执行、不参与响应、异常全部吞掉。用户拿到的答案和没开影子时完全一致；</li>
 *   <li><b>成本可控</b>：每次比对多一次 LLM 调用，因此默认走轻量后端，并支持抽样与一键关闭；</li>
 *   <li><b>结论可查</b>：比对结果写进 AgentTrace 的 {@code SHADOW_ROUTE} 阶段，
 *       {@code status} 直接就是判定结果（AGREE / TOOL_DIFF / ...），可直接聚合成一致率。</li>
 * </ol>
 */
@Service
public class ShadowToolRouter {

    private static final Logger log = LoggerFactory.getLogger(ShadowToolRouter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 从模型输出里抠第一个 JSON 对象：小模型几乎必然会加上解释或代码块围栏 */
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{.*}", Pattern.DOTALL);

    /** 比对判定结果。写进 AgentTrace 的 status 字段，聚合时直接 group by 即可。 */
    public enum Verdict {
        /** 工具和参数都一致 —— 这类占比越高，越可以放心切到 LLM 自主选择 */
        AGREE,
        /** 都认为要调工具，但选了不同的工具 —— 最需要人工看的一类 */
        TOOL_DIFF,
        /** 工具相同但参数不同 —— 通常是天数/日期口径差异 */
        ARGS_DIFF,
        /** 启发式命中了，LLM 认为不该调工具 —— 疑似启发式假阳性（劫持） */
        HEURISTIC_ONLY,
        /** 启发式没命中，LLM 认为该调工具 —— 疑似启发式假阴性（漏判） */
        LLM_ONLY,
        /** 两边都认为不需要工具 */
        BOTH_NONE,
        /** LLM 输出无法解析或调用失败 —— 不计入一致率分母 */
        UNKNOWN
    }

    /** 守护线程池：影子调用绝不能占用请求线程，也不能拖住 JVM 退出 */
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "shadow-route");
        t.setDaemon(true);
        return t;
    });

    private final AtomicLong counter = new AtomicLong();

    private final LlmPort shadowLlmPort;
    private final PromptBuilder promptBuilder;
    private final ToolCatalog toolCatalog;
    private final AgentTraceStorePort traceStore;

    private final boolean enabled;
    private final int sampleEvery;
    private final int maxHistoryPairs;

    /**
     * @param shadowLlmPort 复用 Query 改写那个 bean：它已经指向「快、便宜、非推理」的后端，
     *                      而影子路由的诉求完全一样（机械判断、不需要发挥、不在主链路上）。
     */
    public ShadowToolRouter(@Qualifier("queryRewriteLlmPort") LlmPort shadowLlmPort,
                            PromptBuilder promptBuilder,
                            ToolCatalog toolCatalog,
                            AgentTraceStorePort traceStore,
                            @Value("${agent.heuristic.shadow.enabled:true}") boolean enabled,
                            @Value("${agent.heuristic.shadow.sample-every:1}") int sampleEvery,
                            @Value("${agent.heuristic.shadow.max-history-pairs:3}") int maxHistoryPairs) {
        this.shadowLlmPort = shadowLlmPort;
        this.promptBuilder = promptBuilder;
        this.toolCatalog = toolCatalog;
        this.traceStore = traceStore;
        this.enabled = enabled;
        this.sampleEvery = Math.max(1, sampleEvery);
        this.maxHistoryPairs = Math.max(1, maxHistoryPairs);
        log.info("🔧 影子路由配置: enabled={}, 抽样=每 {} 次请求比对一次, 历史轮数={}",
                enabled, this.sampleEvery, this.maxHistoryPairs);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 异步比对，立即返回。
     *
     * <p>调用方无需（也不应该）等待结果：影子路由的产出是 trace 记录，不是响应内容。
     *
     * @param heuristicChoice 启发式路由的选择，null 表示未命中
     */
    public void compareAsync(String question, List<Map.Entry<String, String>> history,
                             String sessionId, ToolCall heuristicChoice) {
        if (!enabled || question == null || question.isBlank()) return;
        if (counter.incrementAndGet() % sampleEvery != 0) return;

        List<Map.Entry<String, String>> historySnapshot =
                history == null ? List.of() : List.copyOf(history);
        try {
            // 【为什么是 execute 而不是 submit】submit 返回 Future，异步任务里抛出的异常会被
            // 塞进那个没人 get() 的 Future 里彻底消失 —— 既不落 trace 也不打日志。
            // 结果是「一条记录都没有」和「每次都在抛异常」表现完全一样，无从排查。
            // execute 让未捕获异常走线程的 UncaughtExceptionHandler，下面再包一层兜底。
            executor.execute(() -> {
                try {
                    compare(question, historySnapshot, sessionId, heuristicChoice);
                } catch (Exception e) {
                    log.warn("⚠️ 影子路由比对异常（不影响主流程）: question='{}', 原因: {}", question, e.toString());
                }
            });
        } catch (Exception e) {
            // 线程池饱和/关闭中：丢弃这次比对即可，绝不向上抛
            log.warn("⏹️ 影子路由已跳过本次比对（线程池不可用）: {}", e.getMessage());
        }
    }

    private void compare(String question, List<Map.Entry<String, String>> history,
                         String sessionId, ToolCall heuristicChoice) {
        long start = System.currentTimeMillis();
        String raw = null;
        String error = null;
        ToolCall llmChoice = null;
        try {
            raw = askLlm(question, history);
            llmChoice = parse(raw);
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        long elapsed = System.currentTimeMillis() - start;

        Verdict verdict = (error != null || (raw != null && !raw.isBlank() && llmChoice == null && !looksLikeNoTool(raw)))
                ? Verdict.UNKNOWN
                : judge(heuristicChoice, llmChoice);

        String detail = "启发式=" + describe(heuristicChoice) + " | LLM=" + describe(llmChoice);
        // toolName 存 LLM 的选择、toolArguments 存启发式的选择，一行 trace 就能看出分歧点
        traceStore.recordDecision(sessionId, 1, "SHADOW_ROUTE",
                llmChoice == null ? null : llmChoice.name,
                heuristicChoice == null ? null : heuristicChoice.arguments,
                raw,
                question, detail,
                heuristicChoice != null, "SHADOW_COMPARE",
                elapsed, verdict.name(), error);

        // 一律 INFO：影子模式是临时的验证手段，「每次请求都确实比对了」这件事本身就是要观察的对象。
        // 原先 AGREE 走 debug，结果一致的时候日志里什么都看不到 ——
        // 没有产出记录时无法判断是「没有请求」还是「链路坏了」。
        log.info("👥[影子路由] {} | question='{}' | {} | {}ms", verdict, question, detail, elapsed);
    }

    private String askLlm(String question, List<Map.Entry<String, String>> history) {
        List<MessageDTO> messages = new ArrayList<>();
        messages.add(new MessageDTO(Role.SYSTEM, promptBuilder.buildToolSelectionPrompt(toolCatalog.renderForPrompt())));
        int from = Math.max(0, history.size() - maxHistoryPairs);
        for (Map.Entry<String, String> pair : history.subList(from, history.size())) {
            messages.add(new MessageDTO(Role.USER, pair.getKey()));
            messages.add(new MessageDTO(Role.ASSISTANT, truncate(pair.getValue())));
        }
        messages.add(new MessageDTO(Role.USER, question));
        return shadowLlmPort.chatWithRawMessages(messages);
    }

    /** 历史里的助手回复可能是整张能耗表，全塞进去既贵又会淹没提问本身 */
    private String truncate(String text) {
        if (text == null) return "";
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
    }

    /**
     * 解析模型输出，返回 null 表示「模型认为不需要调工具」<b>或</b>「解析失败」——
     * 两者由 {@link #looksLikeNoTool} 区分，避免把解析失败混进「不需要工具」的统计里。
     */
    ToolCall parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.replace("```json", "").replace("```", "").trim();
        Matcher m = JSON_OBJECT.matcher(cleaned);
        if (!m.find()) return null;
        try {
            JsonNode node = MAPPER.readTree(m.group());
            JsonNode toolNode = node.get("tool");
            if (toolNode == null || toolNode.isNull()) return null;
            String name = toolNode.asText("").trim();
            if (name.isEmpty() || "null".equalsIgnoreCase(name)) return null;

            Map<String, Object> args = new LinkedHashMap<>();
            JsonNode argsNode = node.get("arguments");
            if (argsNode != null && argsNode.isObject()) {
                argsNode.properties().forEach(e -> {
                    JsonNode v = e.getValue();
                    if (v != null && !v.isNull()) args.put(e.getKey(), v.isNumber() ? v.numberValue() : v.asText());
                });
            }
            // 幻觉工具名不参与比对：模型编了一个不存在的工具，等于没选对
            if (!toolCatalog.contains(name)) {
                log.info("👻[影子路由] 模型选了不存在的工具 '{}'，按未命中处理", name);
                return null;
            }
            return new ToolCall(name, args);
        } catch (Exception e) {
            return null;
        }
    }

    /** 输出里明确表达了「不调工具」，用于把它和「解析失败」区分开 */
    private boolean looksLikeNoTool(String raw) {
        String s = raw.replaceAll("\\s+", "").toLowerCase();
        return s.contains("\"tool\":null") || s.contains("\"tool\":\"null\"") || s.contains("\"tool\":\"\"");
    }

    Verdict judge(ToolCall heuristic, ToolCall llm) {
        if (heuristic == null && llm == null) return Verdict.BOTH_NONE;
        if (heuristic == null) return Verdict.LLM_ONLY;
        if (llm == null) return Verdict.HEURISTIC_ONLY;
        if (!heuristic.name.equals(llm.name)) return Verdict.TOOL_DIFF;
        return sameArguments(heuristic.arguments, llm.arguments) ? Verdict.AGREE : Verdict.ARGS_DIFF;
    }

    /**
     * 参数等价比较。
     *
     * <p>刻意做得宽松：只比双方都提供了的键，且做归一化（SN 忽略大小写、数字按数值比）。
     * 目的是让 ARGS_DIFF 只在<b>语义真的不同</b>时出现 —— 若把「LLM 少填一个可选的 keyword」
     * 也算分歧，统计出来的一致率会低得毫无参考价值。
     */
    private boolean sameArguments(Map<String, Object> a, Map<String, Object> b) {
        Map<String, String> na = normalize(a);
        Map<String, String> nb = normalize(b);
        for (Map.Entry<String, String> e : na.entrySet()) {
            String other = nb.get(e.getKey());
            if (other != null && !other.equals(e.getValue())) return false;
        }
        // 一方给了实质参数、另一方完全没给，仍算分歧（比如 sn 缺失）
        return !(na.isEmpty() ^ nb.isEmpty());
    }

    private Map<String, String> normalize(Map<String, Object> args) {
        Map<String, String> out = new LinkedHashMap<>();
        if (args == null) return out;
        args.forEach((k, v) -> {
            if (k == null || v == null) return;
            String value = String.valueOf(v).trim();
            if (value.isEmpty()) return;   // 空 keyword 等同于没给
            if (v instanceof Number n) {
                value = String.valueOf(n.longValue());
            } else if (value.matches("-?\\d+")) {
                value = String.valueOf(Long.parseLong(value));
            }
            out.put(k.toLowerCase(), value.toLowerCase());
        });
        return out;
    }

    private String describe(ToolCall call) {
        return call == null ? "(不调工具)" : call.name + call.arguments;
    }
}
