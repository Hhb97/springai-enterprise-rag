package com.example.ragassitant.application.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.GraphRepresentation;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.example.ragassitant.common.trace.TraceIdFilter;
import com.example.ragassitant.domain.model.DocumentChunk;
import com.example.ragassitant.domain.model.PlanStep;
import com.example.ragassitant.domain.model.ToolCall;
import com.example.ragassitant.domain.service.AgentTextUtils;
import com.example.ragassitant.domain.service.PromptBuilder;
import com.example.ragassitant.domain.spi.AgentTraceStorePort;
import com.example.ragassitant.domain.spi.ConversationStorePort;
import com.example.ragassitant.domain.spi.LlmPort;
import com.example.ragassitant.domain.spi.LlmPort.MessageDTO;
import com.example.ragassitant.domain.spi.LlmPort.MessageDTO.Role;
import com.example.ragassitant.domain.spi.ToolRegistryPort;
import com.example.ragassitant.infrastructure.agent.ToolCatalog;
import com.example.ragassitant.infrastructure.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 基于 <b>spring-ai-alibaba-graph</b>（StateGraph 图编排）的 Plan-Execute-Solve Agent。
 * POC 用途：与手写的 {@link PlanThenExecuteAppService} 逐环节对标，验证图框架与
 * 手写编排的差异，<b>不替换</b>现有链路 —— 通过独立端点 {@code /api/graph-chat-stream} 接入。
 *
 * <pre>
 *              ┌──────────┐  有计划   ┌──────────┐  还有步骤  (自环)
 *   START ──→ │ planner  │ ───────→ │ executor │ ────────→ executor
 *              └──────────┘          └──────────┘  没有步骤 ↓
 *                   │ 无计划                  ┌────────┐
 *                   └────────────────────→   │ solver │ ──→ END
 *                                            └────────┘
 * </pre>
 *
 * <p>与手写版的核心区别：
 * <ul>
 *   <li>流程控制权交给框架：节点只做「读状态 → 计算 → 写状态」，跳转由条件边声明；
 *       手写版的 while/for 循环在这里是 executor 的<b>自环边</b></li>
 *   <li>中间数据全部放 {@link OverAllState}（图级共享状态），等价于手写版散落在
 *       doChat 局部变量里的 plan/results/traces</li>
 *   <li>执行顺序为按 cursor 串行（POC 简化）：graph 也支持并行分支边，但为了先验证
 *       最小闭环，拓扑并行仍由手写版承担</li>
 * </ul>
 */
@Service
public class GraphAgentAppService {

    private static final Logger log = LoggerFactory.getLogger(GraphAgentAppService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_PLAN_STEPS = 5;
    private static final int ARG_REF_MAX_LEN = 2000;

    // ===== OverAllState 的键名 =====
    static final String K_QUESTION = "question";
    static final String K_HISTORY = "history";
    static final String K_RAG_CONTEXT = "rag_context";
    static final String K_PLAN = "plan";
    static final String K_RESULTS = "results";
    static final String K_CURSOR = "cursor";
    static final String K_TRACES = "step_traces";
    static final String K_ANSWER = "answer";
    static final String K_SESSION_ID = "session_id";

    /** SSE 流式专用线程池：一个请求一条 worker（与 Plan/ReAct 版同款模式）。 */
    private static final ExecutorService STREAM_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "graph-stream-worker");
        t.setDaemon(true);
        return t;
    });

    private final LlmPort llmPort;
    private final ConversationStorePort conversationStorePort;
    private final ToolRegistryPort toolRegistry;
    private final PromptBuilder promptBuilder;
    private final AgentTraceStorePort traceStore;
    private final ToolCatalog toolCatalog;
    private final AgentProperties props;
    private final AgentRequestPreparer requestPreparer;

    public GraphAgentAppService(LlmPort llmPort,
                                ConversationStorePort conversationStorePort,
                                ToolRegistryPort toolRegistry,
                                PromptBuilder promptBuilder,
                                AgentTraceStorePort traceStore,
                                ToolCatalog toolCatalog,
                                AgentProperties props,
                                AgentRequestPreparer requestPreparer) {
        this.llmPort = llmPort;
        this.conversationStorePort = conversationStorePort;
        this.toolRegistry = toolRegistry;
        this.promptBuilder = promptBuilder;
        this.traceStore = traceStore;
        this.toolCatalog = toolCatalog;
        this.props = props;
        this.requestPreparer = requestPreparer;
        log.info("🧭 GraphAgentAppService 初始化完成（spring-ai-alibaba-graph StateGraph POC）");
        try {
            log.info("🧭 图结构(Mermaid):\n{}", buildGraph().getGraph(GraphRepresentation.Type.MERMAID).content());
        } catch (Throwable t) {
            // 图结构日志属于锦上添花；graph 库与 Spring AI GA 存在 API 漂移时也不能阻断启动
            log.warn("🧭 StateGraph 构建/渲染失败（首次请求时将再次尝试）: {}", t.toString());
        }
    }

    // ==================== 图定义 ====================

    /**
     * 组装 StateGraph：planner → (executor 自环)* → solver。
     * 节点动作都是同步阻塞实现（LLM/工具调用），经 node_async 包装成框架要求的异步形态。
     */
    CompiledGraph buildGraph() {
        KeyStrategyFactory keyStrategyFactory = () -> Map.of(
                K_QUESTION, new ReplaceStrategy(),
                K_HISTORY, new ReplaceStrategy(),
                K_RAG_CONTEXT, new ReplaceStrategy(),
                K_PLAN, new ReplaceStrategy(),
                K_RESULTS, new ReplaceStrategy(),
                K_CURSOR, new ReplaceStrategy(),
                K_TRACES, new AppendStrategy(),
                K_ANSWER, new ReplaceStrategy(),
                K_SESSION_ID, new ReplaceStrategy());

        StateGraph graph = new StateGraph(keyStrategyFactory);
        try {
            graph.addNode("planner", AsyncNodeAction.node_async(plannerNode()));
            graph.addNode("executor", AsyncNodeAction.node_async(executorNode()));
            graph.addNode("solver", AsyncNodeAction.node_async(solverNode()));

            graph.addEdge(StateGraph.START, "planner");
            // 条件边：Planner 出了计划走 executor，空计划（纯 RAG/闲聊）直接 solver
            graph.addConditionalEdges("planner", AsyncEdgeAction.edge_async(state ->
                            hasRemainingSteps(state, readCursor(state)) ? "executor" : "solver"),
                    Map.of("executor", "executor", "solver", "solver"));
            // 自环边：一次执行一步，直到 cursor 走完计划
            graph.addConditionalEdges("executor", AsyncEdgeAction.edge_async(state ->
                            hasRemainingSteps(state, readCursor(state)) ? "executor" : "solver"),
                    Map.of("executor", "executor", "solver", "solver"));
            graph.addEdge("solver", StateGraph.END);

            return graph.compile();
        } catch (Exception e) {
            throw new IllegalStateException("StateGraph 编译失败", e);
        }
    }

    /** 阶段一：调 LLM 产出 JSON 计划写入状态；失败置空计划（条件边会直接放行到 solver）。 */
    private NodeAction plannerNode() {
        return state -> {
            long start = System.currentTimeMillis();
            String question = state.value(K_QUESTION, String.class).orElse("");
            @SuppressWarnings("unchecked")
            List<Map.Entry<String, String>> history =
                    (List<Map.Entry<String, String>>) state.value(K_HISTORY).orElse(List.of());

            List<PlanStep> plan;
            String planJson = "";
            try {
                String catalogText = toolCatalog.renderForPrompt();
                String historyText = AgentTextUtils.formatHistory(history, 3);
                MessageDTO system = new MessageDTO(Role.SYSTEM,
                        promptBuilder.buildPlanSystemPrompt(catalogText, historyText));
                String raw = llmPort.chatWithRawMessages(List.of(system, new MessageDTO(Role.USER, question)));
                planJson = raw == null ? "" : raw.trim();
                plan = parsePlan(planJson);
            } catch (Exception e) {
                log.warn("🧭 [planner] 规划异常，回退直接 Solver: {}", e.getMessage());
                plan = Collections.emptyList();
            }
            long ms = System.currentTimeMillis() - start;
            traceStore.recordDecision(sessionIdOf(state), 1, "PLAN_GENERATE",
                    null, null, planJson,
                    question, plan.isEmpty() ? "no_tool_needed" : "steps=" + plan.size(),
                    false, "GRAPH_STATE_GRAPH",
                    ms, "OK", null);
            log.info("🧭 [planner] {} 步, 耗时 {}ms", plan.size(), ms);
            return Map.of(K_PLAN, plan, K_CURSOR, 0, K_RESULTS, new HashMap<Integer, String>());
        };
    }

    /** 阶段二（每次自环执行一步）：取 cursor 指向的步骤 → $N 替换 → 调工具 → 结果写回状态。 */
    private NodeAction executorNode() {
        return state -> {
            long start = System.currentTimeMillis();
            List<PlanStep> plan = readPlan(state);
            int cursor = readCursor(state);
            @SuppressWarnings("unchecked")
            Map<Integer, String> results = (Map<Integer, String>) state.value(K_RESULTS).orElse(new HashMap<>());

            PlanStep s = plan.get(cursor);
            Map<String, Object> resolvedArgs = resolvePlaceholders(s.arguments, results);
            String observation;
            String error = null;
            try {
                observation = toolRegistry.invoke(new ToolCall(s.tool, resolvedArgs));
            } catch (Exception e) {
                observation = "❌ 工具 " + s.tool + " 执行失败: " + e.getMessage();
                error = e.getMessage();
            }
            long ms = System.currentTimeMillis() - start;
            results.put(s.step, observation);

            traceStore.recordDecision(sessionIdOf(state), 1, "PLAN_EXECUTE",
                    s.tool, resolvedArgs, observation,
                    "graph step " + s.step + " (" + (cursor + 1) + "/" + plan.size() + ")",
                    "depends=" + s.dependsOn, false, "GRAPH_STATE_GRAPH",
                    ms, error == null ? "OK" : "ERROR", error);
            log.info("🧭 [executor] 步骤 {}/{}: {} → {} 字结果, 耗时 {}ms",
                    cursor + 1, plan.size(), s.tool, observation.length(), ms);

            Map<String, Object> trace = buildStepTrace(s.step, s.tool, resolvedArgs, observation, error, ms);
            return Map.of(K_RESULTS, results, K_CURSOR, cursor + 1, K_TRACES, List.of(trace));
        };
    }

    /** 阶段三：汇总 RAG 上下文 + 全部 Observation，一次 LLM 调用出最终答案。 */
    private NodeAction solverNode() {
        return state -> {
            long start = System.currentTimeMillis();
            String question = state.value(K_QUESTION, String.class).orElse("");
            @SuppressWarnings("unchecked")
            List<Map.Entry<String, String>> history =
                    (List<Map.Entry<String, String>>) state.value(K_HISTORY).orElse(List.of());
            String ragContext = state.value(K_RAG_CONTEXT, String.class).orElse("");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> traces =
                    (List<Map<String, Object>>) state.value(K_TRACES).orElse(List.of());

            List<MessageDTO> messages = new ArrayList<>();
            messages.add(new MessageDTO(Role.SYSTEM,
                    promptBuilder.buildPlanSolverSystemPrompt(ragContext, buildExecutionResults(traces))));
            for (Map.Entry<String, String> pair : history) {
                messages.add(new MessageDTO(Role.USER, pair.getKey()));
                messages.add(new MessageDTO(Role.ASSISTANT, pair.getValue()));
            }
            messages.add(new MessageDTO(Role.USER, question));

            String answer = llmPort.chatWithRawMessages(messages);
            if (answer == null || answer.isBlank()) {
                answer = "❌ Graph Solver 综合阶段失败（LLM 返回为空）。工具执行结果可能已在上方给出。";
            }
            long ms = System.currentTimeMillis() - start;
            traceStore.recordDecision(sessionIdOf(state), 1, "PLAN_SOLVE",
                    null, null, buildExecutionResults(traces),
                    "steps=" + traces.size(), answer, false, "GRAPH_STATE_GRAPH",
                    ms, "OK", null);
            log.info("🧭 [solver] 答案 {} 字, 耗时 {}ms", answer.length(), ms);
            return Map.of(K_ANSWER, answer);
        };
    }

    // ==================== 流式入口 ====================

    /**
     * SSE 流式端点实现：请求准备在图外完成（RAG/注入检测与会话历史属于请求级逻辑，
     * 不是图节点），然后 {@code compiled.stream(inputs)} 返回 Flux&lt;NodeOutput&gt;，
     * 每个节点执行完吐一个 NodeOutput —— 把它翻译成与 ReAct/Plan 对齐的 SSE 事件，
     * 前端无需改造即可渲染。
     */
    public Flux<ServerSentEvent<String>> chatStream(String question, String sessionId,
                                                    boolean useRag, boolean enableHybridSearch, Integer customTopK) {
        String sess = sessionId == null ? "" : sessionId;
        StreamChannel channel = StreamChannel.open();
        long totalStart = System.currentTimeMillis();

        STREAM_EXECUTOR.submit(() -> {
            MDC.put(TraceIdFilter.SESSION_ID_KEY, sess);
            try {
                AgentRequestPreparer.Prepared prep = requestPreparer.prepare(
                        question, sess, useRag, enableHybridSearch, customTopK,
                        "graph", props.getMemorySize(), 4, StreamChannel.discarding());
                if (prep.blocked()) {
                    channel.emit("final_start", Map.of());
                    channel.emit("final_delta", Map.of("delta", prep.blockedAnswer()));
                    emitDone(channel, prep.blockedAnswer(), List.of(), totalStart, sess);
                    return;
                }

                CompiledGraph compiled = buildGraph();
                Map<String, Object> inputs = new HashMap<>();
                inputs.put(K_QUESTION, question);
                inputs.put(K_HISTORY, prep.history());
                inputs.put(K_RAG_CONTEXT, buildRagContext(prep.docs()));
                inputs.put(K_PLAN, List.of());
                inputs.put(K_RESULTS, new HashMap<Integer, String>());
                inputs.put(K_CURSOR, 0);
                inputs.put(K_TRACES, new ArrayList<Map<String, Object>>());
                inputs.put(K_ANSWER, "");
                inputs.put(K_SESSION_ID, sess);

                StringBuilder answerBuf = new StringBuilder();
                compiled.stream(inputs)
                        .doOnNext(out -> translateNodeOutput(out, channel, answerBuf))
                        .doOnError(e -> {
                            log.error("🧭 Graph 执行异常", e);
                            channel.emitError(e.getMessage() == null ? "图执行失败" : e.getMessage());
                        })
                        .doFinally(sig -> {
                            String answer = answerBuf.toString();
                            saveHistory(sess, question, answer);
                            emitDone(channel, answer, sourcesOf(prep), totalStart, sess);
                        })
                        .subscribe();
            } catch (Throwable t) {
                log.error("🧭 GraphStream 准备阶段异常", t);
                channel.emitError(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                emitDone(channel, "", List.of(), totalStart, sess);
            } finally {
                MDC.remove(TraceIdFilter.SESSION_ID_KEY);
                channel.complete();
            }
        });
        return channel.flux();
    }

    /**
     * NodeOutput → 对齐 ReAct/Plan 的 SSE 事件序列（前端 handleSseEvent 可直接复用）。
     * 契约注意：前端 ensureStep(d.step) 按 1 起始索引存步骤卡片，step 必须为正整数；
     * final_delta 的文本字段名是 delta 而非 text。
     */
    private void translateNodeOutput(NodeOutput out, StreamChannel channel, StringBuilder answerBuf) {
        String node = out.node();
        OverAllState state = out.state();
        switch (node) {
            case "planner" -> {
                List<PlanStep> plan = readPlan(state);
                log.info("🧭 [SSE] planner 完成: {} 步", plan.size());
            }
            case "executor" -> {
                int stepNum = readCursor(state); // 自环里 cursor 已 +1，正好是本次的 1-based 序号
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> traces =
                        (List<Map<String, Object>>) state.value(K_TRACES).orElse(List.of());
                if (traces.isEmpty()) break;
                Map<String, Object> t = traces.get(traces.size() - 1);
                channel.emit("step_start", Map.of("step", stepNum));
                channel.emit("thought_end", Map.of(
                        "step", stepNum,
                        "thought", t.get("thought"),
                        "tool", t.get("tool")));
                channel.emit("action_start", Map.of(
                        "step", stepNum,
                        "tool", t.get("tool"), "arguments", t.get("arguments")));
                Map<String, Object> endPayload = new LinkedHashMap<>();
                endPayload.put("step", stepNum);
                endPayload.put("observation", t.get("observation"));
                if (t.containsKey("error")) endPayload.put("error", t.get("error"));
                channel.emit("action_end", endPayload);
            }
            case "solver" -> {
                String answer = state.value(K_ANSWER, String.class).orElse("");
                answerBuf.append(answer);
                channel.emit("final_delta", Map.of("delta", answer));
            }
            default -> { /* START/END 等骨架输出不产生事件 */ }
        }
    }

    private void emitDone(StreamChannel ch, String answer, List<Map<String, Object>> sources,
                          long totalStart, String sessionId) {
        ch.emit("done", Map.of(
                "answer", answer, "sources", sources, "mode", "graph",
                "totalMs", System.currentTimeMillis() - totalStart,
                "steps", List.of(), "stepCount", 0,
                "sessionId", sessionId, "stream", true));
    }

    // ==================== 状态读取辅助 ====================

    private static boolean hasRemainingSteps(OverAllState state, int cursor) {
        return cursor < readPlan(state).size();
    }

    private static List<PlanStep> readPlan(OverAllState state) {
        return state.value(K_PLAN).map(v -> (List<PlanStep>) v).orElse(List.of());
    }

    private static int readCursor(OverAllState state) {
        return state.value(K_CURSOR).map(v -> ((Number) v).intValue()).orElse(0);
    }

    private static String sessionIdOf(OverAllState state) {
        // sessionId 作为普通状态键随 inputs 灌进图（见 K_SESSION_ID），节点内随时可读
        return state.value(K_SESSION_ID, String.class).orElse("");
    }

    private List<Map<String, Object>> sourcesOf(AgentRequestPreparer.Prepared prep) {
        return prep.sources() == null ? List.of() : prep.sources();
    }

    // ==================== 与 PlanThenExecuteAppService 相同的纯函数 ====================
    // （原方法为 private 无法复用，POC 阶段复制最小集合；验证通过后再抽到公共领域服务）

    List<PlanStep> parsePlan(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        String json = AgentTextUtils.extractJson(raw);
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            Map<String, Object> root = MAPPER.readValue(json, Map.class);
            if (!(root.get("steps") instanceof List)) return Collections.emptyList();
            List<PlanStep> steps = new ArrayList<>();
            for (Object o : (List<?>) root.get("steps")) {
                if (steps.size() >= MAX_PLAN_STEPS) break;
                if (!(o instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) o;
                Number stepNum = m.get("step") instanceof Number n ? n : null;
                String tool = m.get("tool") instanceof String s && !s.isBlank()
                        && !"null".equalsIgnoreCase(s) ? s : null;
                if (tool == null || !toolCatalog.contains(tool)) continue;
                Map<String, Object> args = m.get("arguments") instanceof Map<?, ?> am
                        ? new LinkedHashMap<>((Map<String, Object>) am) : new LinkedHashMap<>();
                steps.add(new PlanStep(stepNum == null ? steps.size() + 1 : stepNum.intValue(),
                        tool, args, parseDeps(m.get("depends_on"))));
            }
            return steps;
        } catch (Exception e) {
            log.warn("🧭 Planner JSON 解析失败，视为空计划: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private static List<Integer> parseDeps(Object raw) {
        if (!(raw instanceof List)) return Collections.emptyList();
        List<Integer> deps = new ArrayList<>();
        for (Object o : (List<?>) raw) {
            if (o instanceof Number n && n.intValue() > 0) deps.add(n.intValue());
        }
        return deps;
    }

    private static Map<String, Object> resolvePlaceholders(Map<String, Object> args, Map<Integer, String> results) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : args.entrySet()) {
            Object val = e.getValue();
            if (val instanceof String str && str.contains("$")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$(\\d+)").matcher(str);
                StringBuilder sb = new StringBuilder();
                while (m.find()) {
                    String value = results.getOrDefault(Integer.parseInt(m.group(1)), "");
                    m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                            value.length() > ARG_REF_MAX_LEN ? value.substring(0, ARG_REF_MAX_LEN) : value));
                }
                m.appendTail(sb);
                resolved.put(e.getKey(), sb.toString());
            } else {
                resolved.put(e.getKey(), val);
            }
        }
        return resolved;
    }

    private String buildRagContext(List<DocumentChunk> docs) {
        if (docs == null || docs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            sb.append("**[").append(i + 1).append("]** ")
              .append(docs.get(i).getText()).append("\n\n");
        }
        return sb.toString();
    }

    private static String buildExecutionResults(List<Map<String, Object>> stepTraces) {
        if (stepTraces == null || stepTraces.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> t : stepTraces) {
            sb.append("【步骤 ").append(t.get("step")).append("】工具 ")
              .append(t.get("tool")).append(" (").append(t.get("arguments")).append(")\n")
              .append("返回: ").append(t.get("observation")).append("\n\n");
        }
        return sb.toString();
    }

    private void saveHistory(String sessionId, String question, String answer) {
        if (sessionId != null && !sessionId.isBlank() && answer != null && !answer.isBlank()) {
            conversationStorePort.saveUser(sessionId, question);
            conversationStorePort.saveAssistant(sessionId, answer);
        }
    }

    private static Map<String, Object> buildStepTrace(int step, String tool, Map<String, Object> args,
                                                      String observation, String error, long durationMs) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("step", step);
        trace.put("thought", "计划步骤 " + step + "：调用 " + tool + (error == null ? "" : "（执行失败）"));
        trace.put("tool", tool);
        trace.put("arguments", args == null ? Map.of() : args);
        trace.put("observation", observation);
        if (error != null) trace.put("error", error);
        trace.put("durationMs", durationMs);
        trace.put("status", error == null ? "OK" : "ERROR");
        return trace;
    }
}
