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
import com.example.ragassitant.infrastructure.resilience.AgentExecutors;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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

    /** 有界线程池：SSE worker 用 stream()，波内并行用 tool()（改造前两个都是 static 且无界）。 */
    private final AgentExecutors executors;
    private final LlmPort llmPort;
    private final ConversationStorePort conversationStorePort;
    private final ToolRegistryPort toolRegistry;
    private final PromptBuilder promptBuilder;
    private final AgentTraceStorePort traceStore;
    private final ToolCatalog toolCatalog;
    private final AgentProperties props;
    private final AgentRequestPreparer requestPreparer;

    /**
     * 编译后的图，构造时建好后复用。
     *
     * <p>图结构是静态的（节点与边都不随请求变化），原实现在每个请求线程里重新 buildGraph()，
     * 等于每次请求都白付一次编译开销。
     */
    private volatile CompiledGraph compiledGraph;

    public GraphAgentAppService(LlmPort llmPort,
                                ConversationStorePort conversationStorePort,
                                ToolRegistryPort toolRegistry,
                                PromptBuilder promptBuilder,
                                AgentTraceStorePort traceStore,
                                ToolCatalog toolCatalog,
                                AgentProperties props,
                                AgentRequestPreparer requestPreparer,
                                AgentExecutors executors) {
        this.executors = executors;
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
            this.compiledGraph = buildGraph();
            log.info("🧭 图结构(Mermaid):\n{}", this.compiledGraph.getGraph(GraphRepresentation.Type.MERMAID).content());
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
                            hasRemainingSteps(state) ? "executor" : "solver"),
                    Map.of("executor", "executor", "solver", "solver"));
            // 自环边：一次执行一步，直到 cursor 走完计划
            graph.addConditionalEdges("executor", AsyncEdgeAction.edge_async(state ->
                            hasRemainingSteps(state) ? "executor" : "solver"),
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
                String raw = callPlanner(List.of(system, new MessageDTO(Role.USER, question)));
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

    /**
     * 阶段二：<b>一次自环执行一整波</b>（依赖已满足的步骤），波内并行。
     *
     * <p>原 POC 是「一次自环走一步」的串行游标，相对手写版
     * {@code PlanThenExecuteAppService.executePlan} 是功能倒退 —— 后者按依赖分波、波内并行，
     * 墙钟时间约等于每波最慢的一步。这里对齐该语义：图的形状不变（仍是自环边），
     * 只是每次迭代处理的粒度从「一步」变成「一波」。
     *
     * <p>完成度不再用游标表示，而是看 {@code K_RESULTS} 是否已覆盖计划里全部 step ——
     * 并行下步骤不再按顺序完成，游标失去意义；{@code K_CURSOR} 退化为波计数器，仅用于兜底防死循环。
     *
     * <p>依赖无法满足（循环依赖 / 引用了不存在的步骤）时，把剩余步骤标记失败并写入结果池，
     * 让自环条件立刻变假、转入 solver，而不是空转。失败步骤的错误文本照样进结果池：
     * 依赖方经 {@code $N} 引用时拿到的是错误说明而非空串，Solver 才能如实报告哪一步失败了。
     */
    private NodeAction executorNode() {
        return state -> {
            long waveStart = System.currentTimeMillis();
            List<PlanStep> plan = readPlan(state);
            int wave = readCursor(state);

            // 并行写入必须用并发容器：原实现把 HashMap 取出来原地改再放回，
            // 串行下没问题，一旦波内并行就是数据竞争。
            Map<Integer, String> results = new ConcurrentHashMap<>(readResults(state));

            List<PlanStep> ready = plan.stream()
                    .filter(s -> !results.containsKey(s.step))
                    .filter(s -> s.dependsOn == null || s.dependsOn.stream().allMatch(results::containsKey))
                    .toList();

            List<Map<String, Object>> waveTraces = new ArrayList<>();

            if (ready.isEmpty() || wave >= MAX_PLAN_STEPS) {
                String reason = ready.isEmpty()
                        ? "依赖无法满足（循环依赖或引用了不存在的步骤），跳过执行"
                        : "超过最大波次 " + MAX_PLAN_STEPS + "，强制结束";
                for (PlanStep s : plan) {
                    if (results.containsKey(s.step)) continue;
                    results.put(s.step, "❌ " + reason);
                    waveTraces.add(buildStepTrace(s.step, s.tool, s.arguments, null, reason, 0));
                }
                log.warn("🧭 [executor] 执行停滞：{}，剩余步骤已标记失败", reason);
                return Map.of(K_RESULTS, results, K_CURSOR, wave + 1, K_TRACES, waveTraces);
            }

            // 同一波内互不依赖，并行执行；墙钟 ≈ 最慢那一步
            String sessionId = sessionIdOf(state);
            List<CompletableFuture<Map<String, Object>>> futures = ready.stream()
                    .map(s -> CompletableFuture.supplyAsync(
                            () -> executeOneStep(s, results, sessionId), executors.tool()))
                    .toList();

            for (CompletableFuture<Map<String, Object>> f : futures) {
                try {
                    Map<String, Object> trace = f.get(60, TimeUnit.SECONDS);
                    int step = ((Number) trace.get("step")).intValue();
                    Object obs = trace.get("observation");
                    results.put(step, obs == null ? "" : obs.toString());
                    waveTraces.add(trace);
                } catch (Exception e) {
                    log.warn("🧭 [executor] 步骤取结果失败: {}", e.getMessage());
                }
            }
            waveTraces.sort((a, b) -> Integer.compare(
                    ((Number) a.get("step")).intValue(), ((Number) b.get("step")).intValue()));

            log.info("🧭 [executor] 第 {} 波并行执行 {} 步，耗时 {}ms（累计完成 {}/{}）",
                    wave + 1, ready.size(), System.currentTimeMillis() - waveStart, results.size(), plan.size());
            return Map.of(K_RESULTS, results, K_CURSOR, wave + 1, K_TRACES, waveTraces);
        };
    }

    /** 执行单个计划步骤：$N 占位符替换 → 调工具 → 产出 trace。 */
    private Map<String, Object> executeOneStep(PlanStep s, Map<Integer, String> results, String sessionId) {
        long start = System.currentTimeMillis();
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
        traceStore.recordDecision(sessionId, 1, "PLAN_EXECUTE",
                s.tool, resolvedArgs, observation,
                "graph step " + s.step, "depends=" + s.dependsOn,
                false, "GRAPH_STATE_GRAPH",
                ms, error == null ? "OK" : "ERROR", error);
        return buildStepTrace(s.step, s.tool, resolvedArgs, observation, error, ms);
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

        // 有界池：满了抛 RejectedExecutionException → 429（见 GlobalExceptionHandler）
        executors.stream().submit(AgentExecutors.withMdc(() -> {
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

                // 图结构静态，构造时已编译好；构造阶段若失败则这里补建一次
                CompiledGraph compiled = compiledGraph;
                if (compiled == null) {
                    compiled = buildGraph();
                    compiledGraph = compiled;
                }
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
                // K_TRACES 用 AppendStrategy 累加，每波拿到的是全量列表；
                // 用它去重，保证同一步骤的 SSE 事件只推一次
                Set<Integer> emittedSteps = ConcurrentHashMap.newKeySet();
                compiled.stream(inputs)
                        .doOnNext(out -> translateNodeOutput(out, channel, answerBuf, emittedSteps))
                        .doOnError(e -> {
                            log.error("🧭 Graph 执行异常", e);
                            channel.emitError(e.getMessage() == null ? "图执行失败" : e.getMessage());
                        })
                        // 错误已在 doOnError 里推给前端；这里吞掉，避免 blockLast 再抛一次、外层 catch 重复推 done
                        .onErrorResume(e -> Flux.empty())
                        .doFinally(sig -> {
                            String answer = answerBuf.toString();
                            saveHistory(sess, question, answer);
                            emitDone(channel, answer, sourcesOf(prep), totalStart, sess);
                        })
                        // 阻塞到图执行结束再返回：原来用 subscribe() 立即返回，若图在其它线程上执行，
                        // 外层 finally 里的 channel.complete() 会先于 done 事件触发，前端收不到最终结果；
                        // 同时 worker 提前释放，线程池的并发上限也就形同虚设
                        .blockLast();
            } catch (Throwable t) {
                log.error("🧭 GraphStream 准备阶段异常", t);
                channel.emitError(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                emitDone(channel, "", List.of(), totalStart, sess);
            } finally {
                executors.metrics().recordStage("total", "graph_stream", System.currentTimeMillis() - totalStart);
                MDC.remove(TraceIdFilter.SESSION_ID_KEY);
                channel.complete();
            }
        }));
        return channel.flux();
    }

    /**
     * NodeOutput → 对齐 ReAct/Plan 的 SSE 事件序列（前端 handleSseEvent 可直接复用）。
     * 契约注意：前端 ensureStep(d.step) 按 1 起始索引存步骤卡片，step 必须为正整数；
     * final_delta 的文本字段名是 delta 而非 text。
     */
    private void translateNodeOutput(NodeOutput out, StreamChannel channel, StringBuilder answerBuf,
                                     Set<Integer> emittedSteps) {
        String node = out.node();
        OverAllState state = out.state();
        switch (node) {
            case "planner" -> {
                List<PlanStep> plan = readPlan(state);
                log.info("🧭 [SSE] planner 完成: {} 步", plan.size());
            }
            case "executor" -> {
                // 一次自环 = 一整波（可能多步），逐条把本波 traces 翻成 SSE 事件。
                // 原实现用 cursor 当步号且只取最后一条 trace，波内并行后会丢步骤、步号也对不上；
                // 现在直接用 trace 里的 step（Planner 给的 1-based 步号），与前端 ensureStep 契约一致。
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> traces =
                        (List<Map<String, Object>>) state.value(K_TRACES).orElse(List.of());
                if (traces.isEmpty()) break;
                int emitted = 0;
                for (Map<String, Object> t : traces) {
                    Object stepObj = t.get("step");
                    if (!(stepObj instanceof Number n)) continue;
                    int stepNum = n.intValue();
                    if (emittedSteps.contains(stepNum)) continue;   // K_TRACES 是累加策略，跳过历史波次
                    emittedSteps.add(stepNum);
                    emitted++;
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
                log.info("🧭 [SSE] executor 本波推送 {} 个步骤事件", emitted);
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

    /**
     * 是否还有未完成的计划步骤。
     *
     * <p>并行执行后步骤不再按顺序完成，游标无法表示完成度，改为看结果池是否已覆盖全部 step。
     */
    private static boolean hasRemainingSteps(OverAllState state) {
        Map<Integer, String> results = readResults(state);
        return readPlan(state).stream().anyMatch(s -> !results.containsKey(s.step));
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, String> readResults(OverAllState state) {
        return state.value(K_RESULTS).map(v -> (Map<Integer, String>) v).orElse(Map.of());
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

    /**
     * Planner 专用模型（{@code agent.planner.model}），未配置时就是主 LlmPort。
     * 规划是「看工具清单挑工具、排依赖」的结构化任务，可以单独换一个更强 / 更便宜的模型而不影响 Solver 的回答风格。
     * setter 注入：保持构造器签名不变。
     */
    private LlmPort plannerLlmPort;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPlannerLlmPort(@org.springframework.beans.factory.annotation.Qualifier("plannerLlmPort") LlmPort plannerLlmPort) {
        this.plannerLlmPort = plannerLlmPort;
    }

    /**
     * 调 Planner：走 {@code chatStructured}，由 {@code PlanResult} 生成的 JSON Schema 约束输出格式；
     * 解析仍用本类更宽容的 parsePlan(raw)，结构化结果只用于统计格式遵从率。
     */
    private String callPlanner(List<MessageDTO> messages) {
        LlmPort port = plannerLlmPort != null ? plannerLlmPort : llmPort;
        LlmPort.Structured<com.example.ragassitant.domain.model.PlanResult> r =
                port.chatStructured(messages, com.example.ragassitant.domain.model.PlanResult.class);
        if (!r.parsed() && r.raw() != null && !r.raw().isBlank()) {
            log.info("📋 Planner 输出未通过 schema 解析，交给宽容解析兜底");
        }
        return r.raw();
    }
}
