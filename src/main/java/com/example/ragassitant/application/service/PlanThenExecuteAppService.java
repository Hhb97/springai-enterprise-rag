package com.example.ragassitant.application.service;

import com.example.ragassitant.common.trace.TraceIdFilter;
import com.example.ragassitant.domain.model.DocumentChunk;
import com.example.ragassitant.domain.model.PlanStep;
import com.example.ragassitant.domain.model.ToolCall;
import com.example.ragassitant.domain.service.HeuristicRouter;
import com.example.ragassitant.domain.service.PromptBuilder;
import com.example.ragassitant.domain.service.PromptInjectionDetector;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plan-then-Execute（规划-执行-综合）Agent，2026 生产级编排的主流替代方案。
 *
 * <p>对比 {@link AgentAppService}（框架原生 Tool Calling 黑盒）与
 * {@link ReActAgentAppService}（逐轮手写 ReAct）：
 *
 * <pre>
 *                Planner(一次规划)  Executor(并行执行)  Solver(一次综合)
 *   ReAct       ✗ 逐轮思考          ✗ 每轮一个工具      ✗ 每轮一次 LLM
 *   Plan-Exec   一次 LLM 调用       无依赖步骤并行       一次 LLM 调用
 * </pre>
 *
 * <p>核心收益：无依赖的工具步骤并行执行，墙钟时间 ≈ 最慢那一步而非步骤之和；
 * Observation 一次性交给 Solver，不给模型逐轮回灌中间结果，省 token 且杜绝 ReAct 式
 * 「确认循环 / 递归回溯」。Planner 用 {@code $N} 占位符引用后续步骤产物（ReWOO 思路），
 * 规划时无需真的看到中间结果。
 */
@Service
public class PlanThenExecuteAppService {

    private static final Logger log = LoggerFactory.getLogger(PlanThenExecuteAppService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SMALL_TALK_FALLBACK =
            "您好！我是基于 Plan-then-Execute 规划模式的智能运维助手，可以帮您查询设备清单与详情、分析能耗数据，也能基于知识库回答业务问题。请问需要什么帮助？";

    /** Executor 并行池：上限 4，daemon 线程不阻塞 JVM 退出。 */
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "plan-exec-worker");
        t.setDaemon(true);
        return t;
    });

    /** SSE 流式专用线程池：一个请求一条 worker，daemon 不阻塞 JVM 退出。 */
    private static final ExecutorService STREAM_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "plan-stream-worker");
        t.setDaemon(true);
        return t;
    });

    private static final int MAX_PLAN_STEPS = 5;
    private static final int ARG_REF_MAX_LEN = 2000;

    private final LlmPort llmPort;
    private final ConversationStorePort conversationStorePort;
    private final ToolRegistryPort toolRegistry;
    private final PromptBuilder promptBuilder;
    private final AgentTraceStorePort traceStore;
    private final RagAppService ragAppService;
    private final QueryRewriteService queryRewriteService;
    private final ToolCatalog toolCatalog;
    private final PromptInjectionDetector injectionDetector;
    private final AgentProperties props;

    public PlanThenExecuteAppService(LlmPort llmPort,
                                     ConversationStorePort conversationStorePort,
                                     ToolRegistryPort toolRegistry,
                                     PromptBuilder promptBuilder,
                                     AgentTraceStorePort traceStore,
                                     RagAppService ragAppService,
                                     QueryRewriteService queryRewriteService,
                                     ToolCatalog toolCatalog,
                                     PromptInjectionDetector injectionDetector,
                                     AgentProperties props) {
        this.llmPort = llmPort;
        this.conversationStorePort = conversationStorePort;
        this.toolRegistry = toolRegistry;
        this.promptBuilder = promptBuilder;
        this.traceStore = traceStore;
        this.ragAppService = ragAppService;
        this.queryRewriteService = queryRewriteService;
        this.toolCatalog = toolCatalog;
        this.injectionDetector = injectionDetector;
        this.props = props;
        log.info("📋 PlanThenExecuteAppService 初始化完成（Planner → Executor → Solver，maxSteps={}）", MAX_PLAN_STEPS);
    }

    public Map<String, Object> chat(String question, String sessionId,
                                    boolean useRag, boolean enableHybridSearch, Integer customTopK) {
        MDC.put(TraceIdFilter.SESSION_ID_KEY, sessionId == null ? "" : sessionId);
        try {
            return doChat(question, sessionId, useRag, enableHybridSearch, customTopK);
        } finally {
            MDC.remove(TraceIdFilter.SESSION_ID_KEY);
        }
    }

    private Map<String, Object> doChat(String question, String sessionId,
                                       boolean useRag, boolean enableHybridSearch, Integer customTopK) {
        long totalStart = System.currentTimeMillis();
        log.info("📋 Plan-then-Execute 处理问题: sessionId={}, useRag={}, question={}", sessionId, useRag, question);

        List<Map.Entry<String, String>> history = loadHistory(sessionId);

        // ===== 闲聊短路：和 Agent/ReAct 同一套判定 =====
        if (HeuristicRouter.isSmallTalk(question)) {
            return handleSmallTalk(question, sessionId, totalStart);
        }

        // ===== Prompt Injection 安全检测 =====
        if (injectionDetector.isPromptInjection(question)) {
            String attackType = injectionDetector.getAttackType(question);
            log.warn("🛡️ 拦截 Prompt Injection 攻击: sessionId={}, attackType={}", sessionId, attackType);
            String rejectionMsg = "抱歉，我检测到您的请求中包含试图绕过系统安全策略的指令。作为智能运维助手，我必须遵守预设的角色和职责范围，无法执行此类操作。如果您有设备查询、能耗分析或运维知识库相关的问题，欢迎随时向我提问！";
            traceStore.recordDecision(sessionId, 1, "SECURITY_BLOCK",
                    null, null, null,
                    rejectionMsg, rejectionMsg,
                    false, "prompt_injection",
                    0L, "BLOCKED", attackType);
            saveHistory(sessionId, question, rejectionMsg);
            return buildResponse(rejectionMsg, List.of(), List.of(), false, null,
                    0, 0, System.currentTimeMillis() - totalStart, sessionId);
        }

        // ===== Query 改写：只用于检索，塞给 Solver 的仍是用户原话 =====
        String retrievalQuery = question;
        boolean queryRewritten = false;
        String rewrittenQuery = null;
        if (!history.isEmpty() && queryRewriteService.isEnabled()) {
            List<String> rewrites = queryRewriteService.rewrite(question, history, sessionId);
            if (rewrites != null && !rewrites.isEmpty()) {
                rewrittenQuery = rewrites.get(0);
                queryRewritten = !rewrittenQuery.equals(question);
                if (queryRewritten) {
                    retrievalQuery = rewrittenQuery;
                    log.info("📋 Query 改写: '{}' → '{}'", question, rewrittenQuery);
                }
            }
        }

        // ===== RAG 检索 =====
        List<DocumentChunk> docs = new ArrayList<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        if (useRag) {
            try {
                int topK = customTopK != null && customTopK > 0 ? customTopK : 4;
                docs = ragAppService.retrieveDocuments(retrievalQuery, enableHybridSearch, topK);
                for (DocumentChunk doc : docs) {
                    Map<String, Object> source = new HashMap<>();
                    source.put("content", doc.getText());
                    Map<String, Object> meta = doc.getMetadata();
                    Object title = meta != null ? meta.get("title") : null;
                    Object fileName = meta != null ? meta.get("fileName") : null;
                    source.put("metadata", title != null ? title : (fileName != null ? fileName : "文档"));
                    source.put("score", doc.getScore());
                    sources.add(source);
                }
            } catch (Exception e) {
                log.warn("⚠️ Plan RAG 检索失败，已跳过: {}", e.getMessage());
            }
        }

        // ===== 阶段一：Planner（一次 LLM 调用产出完整计划）=====
        long planStart = System.currentTimeMillis();
        List<PlanStep> plan = Collections.emptyList();
        String planJson = null;
        String planError = null;
        try {
            String raw = llmPort.chatWithRawMessages(buildPlannerMessages(question, history));
            planJson = raw == null ? "" : raw.trim();
            plan = parsePlan(planJson, sessionId);
        } catch (Exception e) {
            planError = e.getMessage();
            log.warn("⚠️ Planner 异常，回退为直接 Solver: {}", planError);
        }
        long planMs = System.currentTimeMillis() - planStart;
        traceStore.recordDecision(sessionId, 1, "PLAN_GENERATE",
                null, null, planJson,
                question, plan.isEmpty() ? "no_tool_needed" : "steps=" + plan.size(),
                false, "PLAN_THEN_EXECUTE",
                planMs, planError == null ? "OK" : "ERROR", planError);

        // ===== 阶段二：Executor（拓扑调度 + 无依赖步骤并行）=====
        long execStart = System.currentTimeMillis();
        List<Map<String, Object>> stepTraces = executePlan(plan, sessionId);
        long execMs = System.currentTimeMillis() - execStart;

        // ===== 阶段三：Solver（一次 LLM 调用综合全部 Observation）=====
        long solveStart = System.currentTimeMillis();
        String ragContext = buildRagContext(docs);
        String executionResults = buildExecutionResults(stepTraces);
        String answer = llmPort.chatWithRawMessages(buildSolverMessages(question, history, ragContext, executionResults));
        long solveMs = System.currentTimeMillis() - solveStart;

        if (answer == null || answer.isBlank()) {
            answer = "❌ Plan 综合阶段失败（LLM 返回为空）。工具执行结果可能已在上方给出。";
        }

        traceStore.recordDecision(sessionId, 1, "PLAN_SOLVE",
                null, null, executionResults,
                "steps=" + stepTraces.size(), answer,
                false, "PLAN_THEN_EXECUTE",
                solveMs, "OK", null);

        saveHistory(sessionId, question, answer);

        long totalMs = System.currentTimeMillis() - totalStart;
        Map<String, Object> response = buildResponse(answer, sources, stepTraces, queryRewritten,
                rewrittenQuery, planMs, execMs, totalMs, sessionId);
        response.put("solveMs", solveMs);
        response.put("planJson", planJson);
        response.put("mode", "plan");
        log.info("📋 Plan 完成: {} steps, plan={}ms, exec={}ms, solve={}ms, total={}ms",
                stepTraces.size(), planMs, execMs, solveMs, totalMs);
        return response;
    }

    // ==================== 流式版本（SSE，事件序列与 ReAct 对齐，前端可复用渲染）====================

    /**
     * Stream 版本：Planner 一次产出计划（{@code plan} 阶段静默生成），Executor 每步按
     * {@code step_start → thought_end → action_start → action_end} 推送，Solver 最终答案
     * 按 token 流式吐（{@code solve_start → final_delta*N}），最后 {@code done} 收尾。
     * <p>事件命名与 {@link ReActAgentAppService} 完全一致，前端 {@code handleSseEvent}
     * 和 {@code renderReActSteps} 无需改逻辑即可复用。
     * <p>返回 {@code Flux<ServerSentEvent>}（Spring MVC 原生支持，无需 WebFlux 依赖），
     * 事件由 worker 线程经 {@link StreamChannel} 推入 Reactor Sink；客户端断连或
     * 异步超时触发订阅取消，worker 侧自动短路退出。
     */
    public Flux<ServerSentEvent<String>> chatStream(String question, String sessionId,
                                 boolean useRag, boolean enableHybridSearch, Integer customTopK) {
        String sess = sessionId == null ? "" : sessionId;
        StreamChannel channel = StreamChannel.open();

        STREAM_EXECUTOR.submit(() -> {
            MDC.put(TraceIdFilter.SESSION_ID_KEY, sess);
            try {
                runPlanStreamBlocking(channel, question, sess, useRag, enableHybridSearch, customTopK);
            } catch (Throwable t) {
                log.error("📋 PlanStream 执行异常", t);
                channel.emitError(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            } finally {
                MDC.remove(TraceIdFilter.SESSION_ID_KEY);
                channel.complete();
            }
        });

        return channel.flux();
    }

    private void runPlanStreamBlocking(
            StreamChannel ch,
            String question, String sessionId, boolean useRag,
            boolean enableHybridSearch, Integer customTopK) {

        long totalStart = System.currentTimeMillis();
        log.info("📋 PlanStream 处理问题: sessionId={}, useRag={}, question={}", sessionId, useRag, question);

        List<Map.Entry<String, String>> history = loadHistory(sessionId);

        // ===== 闲聊短路：和 Agent/ReAct 同一套判定 =====
        if (HeuristicRouter.isSmallTalk(question)) {
            handleSmallTalkStream(ch, question, sessionId, totalStart);
            return;
        }
        if (ch.isFinished()) return;

        // ===== Prompt Injection 安全检测 =====
        if (injectionDetector.isPromptInjection(question)) {
            String attackType = injectionDetector.getAttackType(question);
            log.warn("🛡️ 拦截 Prompt Injection 攻击: sessionId={}, attackType={}", sessionId, attackType);
            String rejectionMsg = "抱歉，我检测到您的请求中包含试图绕过系统安全策略的指令。作为智能运维助手，我必须遵守预设的角色和职责范围，无法执行此类操作。如果您有设备查询、能耗分析或运维知识库相关的问题，欢迎随时向我提问！";
            traceStore.recordDecision(sessionId, 1, "SECURITY_BLOCK",
                    null, null, null,
                    rejectionMsg, rejectionMsg,
                    false, "prompt_injection",
                    0L, "BLOCKED", attackType);
            saveHistory(sessionId, question, rejectionMsg);
            ch.emit( "security_block", Map.of(
                    "type", "prompt_injection",
                    "attackType", attackType,
                    "answer", rejectionMsg));
            ch.emit( "final_answer", Map.of("answer", rejectionMsg));
            ch.emit( "done", Map.of(
                    "answer", rejectionMsg, "sources", List.of(), "mode", "plan",
                    "totalMs", System.currentTimeMillis() - totalStart,
                    "steps", List.of(), "stepCount", 0, "sessionId", sessionId, "stream", true));
            return;
        }
        if (ch.isFinished()) return;

        // ===== Query 改写：只用于检索，塞给 Solver 的仍是用户原话 =====
        String retrievalQuery = question;
        boolean queryRewritten = false;
        String rewrittenQuery = null;
        if (!history.isEmpty() && queryRewriteService.isEnabled()) {
            List<String> rewrites = queryRewriteService.rewrite(question, history, sessionId);
            if (rewrites != null && !rewrites.isEmpty()) {
                rewrittenQuery = rewrites.get(0);
                queryRewritten = !rewrittenQuery.equals(question);
                if (queryRewritten) {
                    retrievalQuery = rewrittenQuery;
                    ch.emit( "rewrite", Map.of("from", question, "to", rewrittenQuery, "ms", 0));
                }
            }
        }
        if (ch.isFinished()) return;

        // ===== RAG 检索 =====
        List<DocumentChunk> docs = new ArrayList<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        if (useRag) {
            try {
                int topK = customTopK != null && customTopK > 0 ? customTopK : 4;
                docs = ragAppService.retrieveDocuments(retrievalQuery, enableHybridSearch, topK);
                for (DocumentChunk doc : docs) {
                    Map<String, Object> source = new HashMap<>();
                    source.put("content", doc.getText());
                    Map<String, Object> meta = doc.getMetadata();
                    Object title = meta != null ? meta.get("title") : null;
                    Object fileName = meta != null ? meta.get("fileName") : null;
                    source.put("metadata", title != null ? title : (fileName != null ? fileName : "文档"));
                    source.put("score", doc.getScore());
                    sources.add(source);
                }
                if (!docs.isEmpty()) {
                    ch.emit( "rag_ready", Map.of("count", docs.size()));
                }
            } catch (Exception e) {
                log.warn("⚠️ PlanStream RAG 检索失败，已跳过: {}", e.getMessage());
            }
        }
        if (ch.isFinished()) return;

        // ===== 阶段一：Planner（静默一次生成完整计划）=====
        long planStart = System.currentTimeMillis();
        List<PlanStep> plan = Collections.emptyList();
        String planJson = null;
        String planError = null;
        try {
            String raw = llmPort.chatWithRawMessages(buildPlannerMessages(question, history));
            planJson = raw == null ? "" : raw.trim();
            plan = parsePlan(planJson, sessionId);
        } catch (Exception e) {
            planError = e.getMessage();
            log.warn("⚠️ Planner 异常，回退为直接 Solver: {}", planError);
        }
        long planMs = System.currentTimeMillis() - planStart;
        traceStore.recordDecision(sessionId, 1, "PLAN_GENERATE",
                null, null, planJson,
                question, plan.isEmpty() ? "no_tool_needed" : "steps=" + plan.size(),
                false, "PLAN_THEN_EXECUTE",
                planMs, planError == null ? "OK" : "ERROR", planError);
        if (ch.isFinished()) return;

        // ===== 阶段二：Executor（拓扑调度 + 无依赖步骤并行，事件逐个推送）=====
        long execStart = System.currentTimeMillis();
        List<Map<String, Object>> stepTraces = executePlanStream(plan, sessionId, ch);
        long execMs = System.currentTimeMillis() - execStart;
        if (ch.isFinished()) return;

        // ===== 阶段三：Solver（最终答案按 token 流式吐）=====
        long solveStart = System.currentTimeMillis();
        String ragContext = buildRagContext(docs);
        String executionResults = buildExecutionResults(stepTraces);
        String answer = streamSolver(ch, question, history, ragContext, executionResults, sessionId);
        if (ch.isFinished()) return;
        if (answer == null || answer.isBlank()) {
            answer = "❌ Plan 综合阶段失败（LLM 返回为空）。工具执行结果可能已在上方给出。";
        }

        traceStore.recordDecision(sessionId, 1, "PLAN_SOLVE",
                null, null, executionResults,
                "steps=" + stepTraces.size(), answer,
                false, "PLAN_THEN_EXECUTE",
                System.currentTimeMillis() - solveStart, "OK", null);

        saveHistory(sessionId, question, answer);

        long totalMs = System.currentTimeMillis() - totalStart;
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("answer", answer);
        done.put("sources", sources);
        done.put("mode", "plan");
        done.put("queryRewritten", queryRewritten);
        done.put("rewrittenQuery", rewrittenQuery);
        done.put("planMs", planMs);
        done.put("executeMs", execMs);
        done.put("totalMs", totalMs);
        done.put("steps", stepTraces);
        done.put("stepCount", stepTraces.size());
        done.put("sessionId", sessionId);
        done.put("stream", true);

        ch.emit( "done", done);
        log.info("📋 PlanStream 完成: {} steps, plan={}ms, exec={}ms, total={}ms",
                stepTraces.size(), planMs, execMs, totalMs);
    }

    /**
     * 流式版 Executor：与 {@link #executePlan} 相同的拓扑调度，额外在每步的
     * 关键节点推 SSE 事件，让前端能看到「计划步骤 → 调用工具中 → 观察结果」的完整过程。
     * <p>事件在 worker 主线程（串行）发出，天然有序；工具并行执行只在 {@link #EXECUTOR} 内。
     */
    private List<Map<String, Object>> executePlanStream(List<PlanStep> plan, String sessionId,
                                                        StreamChannel ch) {
        List<Map<String, Object>> traces = new ArrayList<>();
        if (plan == null || plan.isEmpty()) {
            traceStore.recordDecision(sessionId, 1, "PLAN_EXECUTE",
                    null, null, null,
                    "plan_steps=0", "无步骤执行（纯 RAG 或直接回答）",
                    false, "PLAN_THEN_EXECUTE",
                    0L, "SKIPPED", null);
            return traces;
        }

        Map<Integer, String> results = new HashMap<>();
        Set<Integer> done = new HashSet<>();
        Set<Integer> failed = new HashSet<>();
        List<PlanStep> pending = new ArrayList<>(plan);

        int wave = 0;
        while (!pending.isEmpty() && wave++ <= MAX_PLAN_STEPS && !ch.isFinished()) {
            List<PlanStep> ready = pending.stream()
                    .filter(s -> s.dependsOn.stream().allMatch(done::contains))
                    .toList();
            if (ready.isEmpty()) {
                for (PlanStep s : pending) {
                    traces.add(buildStepTrace(s.step, s.tool, s.arguments, null,
                            "依赖无法满足（循环依赖或引用了不存在的步骤），跳过执行", 0, false));
                    failed.add(s.step);
                }
                log.warn("⚠️ PlanStream 执行停滞：剩余步骤的依赖无法满足，提前结束。剩余={}", pending);
                break;
            }
            pending.removeAll(ready);

            List<CompletableFuture<Map<String, Object>>> futures = ready.stream()
                    .map(s -> {
                        // 先按顺序推事件（thought_end 的 thought 由前端渲染成步骤标题）
                        if (ch.isFinished()) return null;
                        ch.emit( "step_start",
                                Map.of("step", s.step, "maxSteps", MAX_PLAN_STEPS));
                        ch.emit( "thought_end", Map.of(
                                "step", s.step,
                                "thought", "计划步骤 " + s.step + "：调用 " + s.tool,
                                "tool", s.tool,
                                "arguments", s.arguments == null ? Map.of() : s.arguments));
                        ch.emit( "action_start", Map.of(
                                "step", s.step,
                                "tool", s.tool,
                                "arguments", s.arguments == null ? Map.of() : s.arguments));
                        return CompletableFuture.supplyAsync(() -> executeOne(s, results, sessionId), EXECUTOR);
                    })
                    .toList();

            for (CompletableFuture<Map<String, Object>> f : futures) {
                if (f == null) continue;
                try {
                    Map<String, Object> trace = f.get(60, TimeUnit.SECONDS);
                    int step = ((Number) trace.get("step")).intValue();
                    traces.add(trace);
                    String obs = (String) trace.get("observation");
                    results.put(step, obs == null ? "" : obs);
                    if (Boolean.TRUE.equals(trace.get("error"))) failed.add(step);
                    done.add(step);
                    Map<String, Object> end = new LinkedHashMap<>();
                    end.put("step", step);
                    end.put("observation", obs == null ? "" : obs);
                    end.put("durationMs", trace.get("durationMs"));
                    end.put("tool", trace.get("tool"));
                    if (trace.get("error") != null) end.put("error", trace.get("error"));
                    ch.emit( "action_end", end);
                } catch (Exception e) {
                    log.warn("⚠️ PlanStream 步骤执行取结果失败: {}", e.getMessage());
                    failed.add(-1);
                }
            }
        }
        traces.sort((a, b) -> Integer.compare(((Number) a.get("step")).intValue(), ((Number) b.get("step")).intValue()));
        return traces;
    }

    /** Solver 最终答案按 token 流式推送（{@code final_delta}），本地攒全量返回。 */
    private String streamSolver(StreamChannel ch,
                                String question, List<Map.Entry<String, String>> history,
                                String ragContext, String executionResults, String sessionId) {
        ch.emit( "solve_start", Map.of());
        List<MessageDTO> messages = buildSolverMessages(question, history, ragContext, executionResults);
        StringBuilder buf = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> streamErr = new AtomicReference<>();
        try {
            llmPort.chatStreamWithRawMessages(messages)
                    .doOnNext(token -> {
                        if (ch.isFinished()) return;
                        if (token != null && !token.isEmpty()) {
                            buf.append(token);
                            ch.emit( "final_delta", Map.of("delta", token));
                        }
                    })
                    .doOnError(streamErr::set)
                    .doOnComplete(() -> latch.countDown())
                    .subscribe();
            boolean ok = latch.await(180, TimeUnit.SECONDS);
            if (ch.isFinished()) return "";
            if (!ok || streamErr.get() != null) {
                String msg = streamErr.get() != null ? streamErr.get().getMessage() : "Solver 流式调用超时";
                log.warn("⚠️ PlanStream Solver 异常: {}", msg);
                ch.emit( "error", Map.of("message", msg));
                return "❌ Plan 综合阶段失败: " + msg;
            }
        } catch (Exception e) {
            log.error("📋 PlanStream Solver 异常", e);
            ch.emit( "error", Map.of("message", e.getMessage()));
            return "❌ Plan 综合阶段失败: " + e.getMessage();
        }
        return buf.toString().trim();
    }

    private void handleSmallTalkStream(StreamChannel ch,
                                       String question, String sessionId, long totalStart) {
        log.info("💬 PlanStream 闲聊短路：跳过 Planner/Executor/Solver → question={}", question);
        List<MessageDTO> messages = List.of(
                new MessageDTO(Role.SYSTEM, promptBuilder.buildSmallTalkSystemPrompt()),
                new MessageDTO(Role.USER, question));
        long start = System.currentTimeMillis();
        StringBuilder buf = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        llmPort.chatStreamWithRawMessages(messages)
                .doOnNext(t -> {
                    if (ch.isFinished()) return;
                    if (t != null && !t.isEmpty()) {
                        buf.append(t);
                        ch.emit( "final_delta", Map.of("delta", t));
                    }
                })
                .doOnComplete(() -> {
                    String answer = buf.toString().trim();
                    if (answer.isBlank()) answer = SMALL_TALK_FALLBACK;
                    long ms = System.currentTimeMillis() - start;
                    traceStore.recordDecision(sessionId, 1, "SMALL_TALK",
                            null, null, null,
                            question, answer,
                            false, "PLAN_THEN_EXECUTE_SMALL_TALK",
                            ms, "OK", null);
                    saveHistory(sessionId, question, answer);
                    ch.emit( "done", Map.of(
                            "answer", answer, "sources", List.of(), "mode", "plan",
                            "totalMs", System.currentTimeMillis() - totalStart,
                            "steps", List.of(), "stepCount", 0,
                            "sessionId", sessionId, "stream", true, "smallTalk", true));
                    latch.countDown();
                })
                .doOnError(e -> {
                    long ms = System.currentTimeMillis() - start;
                    traceStore.recordDecision(sessionId, 1, "SMALL_TALK",
                            null, null, null,
                            question, SMALL_TALK_FALLBACK,
                            false, "PLAN_THEN_EXECUTE_SMALL_TALK",
                            ms, "ERROR", e.getMessage());
                    saveHistory(sessionId, question, SMALL_TALK_FALLBACK);
                    ch.emitError( e.getMessage() == null ? "闲聊回复失败" : e.getMessage());
                    ch.emit( "done", Map.of(
                            "answer", SMALL_TALK_FALLBACK, "sources", List.of(), "mode", "plan",
                            "totalMs", System.currentTimeMillis() - totalStart,
                            "steps", List.of(), "stepCount", 0,
                            "sessionId", sessionId, "stream", true, "smallTalk", true));
                    latch.countDown();
                })
                .subscribe();
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 阶段一：Planner 解析 ====================

    private List<MessageDTO> buildPlannerMessages(String question, List<Map.Entry<String, String>> history) {
        String historyText = formatHistory(history, 3);
        String catalogText = toolCatalog.renderForPrompt();
        MessageDTO system = new MessageDTO(Role.SYSTEM, promptBuilder.buildPlanSystemPrompt(catalogText, historyText));
        return List.of(system, new MessageDTO(Role.USER, question));
    }

    /**
     * 解析 Planner 的 JSON 计划，并做健壮性处理：
     * 剥代码块围栏 → 取首个 { 到末个 } → 反序列化 → 校验步骤数上限。
     * 任一步骤工具名不在目录中会被剔除（其依赖者随之失效），避免执行阶段打到未知工具。
     */
    List<PlanStep> parsePlan(String raw, String sessionId) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        String json = extractJson(raw);
        if (json == null || json.isBlank()) {
            log.warn("⚠️ Planner 输出无法解析为 JSON，视为空计划: {}", truncate(raw, 200));
            return Collections.emptyList();
        }
        try {
            Map<String, Object> root = MAPPER.readValue(json, Map.class);
            Object stepsObj = root.get("steps");
            if (!(stepsObj instanceof List)) return Collections.emptyList();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawSteps = (List<Map<String, Object>>) stepsObj;
            List<PlanStep> steps = new ArrayList<>();
            for (Object o : rawSteps) {
                if (steps.size() >= MAX_PLAN_STEPS) break;
                if (!(o instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) o;
                Number stepNum = m.get("step") instanceof Number n ? n : null;
                String tool = m.get("tool") instanceof String s && !s.isBlank() && !"null".equalsIgnoreCase(s) ? s : null;
                Map<String, Object> args = m.get("arguments") instanceof Map<?, ?> am ? new LinkedHashMap<>(cast(am)) : new LinkedHashMap<>();
                List<Integer> deps = parseDeps(m.get("depends_on"));

                if (tool == null) continue;
                if (!toolCatalog.contains(tool)) {
                    log.warn("⚠️ Planner 引用了未知工具 '{}'，已剔除该步骤", tool);
                    continue;
                }
                steps.add(new PlanStep(stepNum == null ? steps.size() + 1 : stepNum.intValue(), tool, args, deps));
            }
            log.info("📋 Planner 解析出 {} 步计划: {}", steps.size(), steps);
            return steps;
        } catch (Exception e) {
            log.warn("⚠️ Planner JSON 解析失败，视为空计划: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static List<Integer> parseDeps(Object raw) {
        if (!(raw instanceof List)) return Collections.emptyList();
        List<Integer> deps = new ArrayList<>();
        for (Object o : (List<?>) raw) {
            if (o instanceof Number n && n.intValue() > 0) deps.add(n.intValue());
            else if (o instanceof String s) {
                try {
                    int v = Integer.parseInt(s.trim());
                    if (v > 0) deps.add(v);
                } catch (NumberFormatException ignored) {}
            }
        }
        return deps;
    }

    private static String extractJson(String raw) {
        String s = raw.replaceAll("```(?:json)?", "").trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return s.substring(start, end + 1);
    }

    // ==================== 阶段二：Executor ====================

    /**
     * 按依赖做拓扑调度执行：每一波取「依赖全部完成」的步骤并行执行，结果写入
     * {@code Map<step, observation>} 供后续步骤的 {@code $N} 占位符替换。
     *
     * <p>失败隔离：单个工具抛异常不会拖垮同波其他步骤 —— 该步 Observation 记录为错误文本，
     * 后续步骤照常执行（它们依赖到的 $N 会是错误说明，正好让 Solver 知道哪里失败了）。
     */
    private List<Map<String, Object>> executePlan(List<PlanStep> plan, String sessionId) {
        List<Map<String, Object>> traces = new ArrayList<>();
        if (plan == null || plan.isEmpty()) {
            traceStore.recordDecision(sessionId, 1, "PLAN_EXECUTE",
                    null, null, null,
                    "plan_steps=0", "无步骤执行（纯 RAG 或直接回答）",
                    false, "PLAN_THEN_EXECUTE",
                    0L, "SKIPPED", null);
            return traces;
        }

        Map<Integer, String> results = new HashMap<>();
        Set<Integer> done = new HashSet<>();
        Set<Integer> failed = new HashSet<>();
        List<PlanStep> pending = new ArrayList<>(plan);

        int wave = 0;
        while (!pending.isEmpty() && wave++ <= MAX_PLAN_STEPS) {
            List<PlanStep> ready = pending.stream()
                    .filter(s -> s.dependsOn.stream().allMatch(done::contains))
                    .toList();
            if (ready.isEmpty()) {
                // 循环依赖或引用了不存在的步骤 → 无法推进，剩余步骤标记失败
                for (PlanStep s : pending) {
                    traces.add(buildStepTrace(s.step, s.tool, s.arguments, null,
                            "依赖无法满足（循环依赖或引用了不存在的步骤），跳过执行", 0, false));
                    failed.add(s.step);
                }
                log.warn("⚠️ Plan 执行停滞：剩余步骤的依赖无法满足，提前结束。剩余={}", pending);
                break;
            }
            pending.removeAll(ready);

            // 同一波内无依赖关系，并行执行；墙钟时间 ≈ 最慢那一步
            List<CompletableFuture<Map<String, Object>>> futures = ready.stream()
                    .map(s -> CompletableFuture.supplyAsync(() -> executeOne(s, results, sessionId), EXECUTOR))
                    .toList();
            for (CompletableFuture<Map<String, Object>> f : futures) {
                try {
                    Map<String, Object> trace = f.get(60, TimeUnit.SECONDS);
                    int step = ((Number) trace.get("step")).intValue();
                    traces.add(trace);
                    String obs = (String) trace.get("observation");
                    // 无论成败都把 Observation 记进结果池：依赖方经 $N 引用失败步骤时，
                    // 拿到的是错误说明而非空串，Solver 才能如实报告哪一步失败了。
                    results.put(step, obs == null ? "" : obs);
                    if (Boolean.TRUE.equals(trace.get("error"))) failed.add(step);
                    done.add(step);
                } catch (Exception e) {
                    log.warn("⚠️ Plan 步骤执行取结果失败: {}", e.getMessage());
                    failed.add(-1);
                }
            }
        }
        traces.sort((a, b) -> Integer.compare(((Number) a.get("step")).intValue(), ((Number) b.get("step")).intValue()));
        return traces;
    }

    private Map<String, Object> executeOne(PlanStep s, Map<Integer, String> results, String sessionId) {
        long start = System.currentTimeMillis();
        Map<String, Object> resolvedArgs = resolvePlaceholders(s.arguments, results);
        String observation;
        String error = null;
        boolean ok = true;
        try {
            observation = toolRegistry.invoke(new ToolCall(s.tool, resolvedArgs));
        } catch (Exception e) {
            observation = "❌ 工具 " + s.tool + " 执行失败: " + e.getMessage();
            error = e.getMessage();
            ok = false;
        }
        long ms = System.currentTimeMillis() - start;
        traceStore.recordDecision(sessionId, 1, "PLAN_EXECUTE",
                s.tool, resolvedArgs, observation,
                "plan step " + s.step, "depends=" + s.dependsOn,
                false, "PLAN_THEN_EXECUTE",
                ms, ok ? "OK" : "ERROR", error);
        return buildStepTrace(s.step, s.tool, resolvedArgs, observation, error, ms, !ok);
    }

    /** 把 arguments 里字符串值中的 {@code $N} 替换成第 N 步的 Observation（截断防爆上下文）。 */
    private static Map<String, Object> resolvePlaceholders(Map<String, Object> args, Map<Integer, String> results) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        if (args == null || args.isEmpty()) return resolved;
        for (Map.Entry<String, Object> e : args.entrySet()) {
            Object val = e.getValue();
            if (val instanceof String str && str.contains("$")) {
                resolved.put(e.getKey(), substitute(str, results));
            } else {
                resolved.put(e.getKey(), val);
            }
        }
        return resolved;
    }

    private static String substitute(String template, Map<Integer, String> results) {
        String out = template;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$(\\d+)").matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int ref = Integer.parseInt(m.group(1));
            String value = results.getOrDefault(ref, "");
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                    value.length() > ARG_REF_MAX_LEN ? value.substring(0, ARG_REF_MAX_LEN) : value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ==================== 阶段三：Solver ====================

    private List<MessageDTO> buildSolverMessages(String question, List<Map.Entry<String, String>> history,
                                                 String ragContext, String executionResults) {
        List<MessageDTO> messages = new ArrayList<>();
        messages.add(new MessageDTO(Role.SYSTEM, promptBuilder.buildPlanSolverSystemPrompt(ragContext, executionResults)));
        for (Map.Entry<String, String> pair : history) {
            messages.add(new MessageDTO(Role.USER, pair.getKey()));
            messages.add(new MessageDTO(Role.ASSISTANT, pair.getValue()));
        }
        messages.add(new MessageDTO(Role.USER, question));
        return messages;
    }

    private String buildRagContext(List<DocumentChunk> docs) {
        if (docs == null || docs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            DocumentChunk doc = docs.get(i);
            sb.append("**[")
              .append(i + 1)
              .append("]** ")
              .append(doc.getText())
              .append("\n\n");
        }
        return sb.toString();
    }

    private String buildExecutionResults(List<Map<String, Object>> stepTraces) {
        if (stepTraces == null || stepTraces.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> t : stepTraces) {
            sb.append("【步骤 ")
              .append(t.get("step"))
              .append("】工具 ")
              .append(t.get("tool"))
              .append(" (")
              .append(t.get("arguments"))
              .append(")\n");
            sb.append("返回: ").append(t.get("observation")).append("\n\n");
        }
        return sb.toString();
    }

    // ==================== 公共环节 ====================

    private List<Map.Entry<String, String>> loadHistory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Collections.emptyList();
        return conversationStorePort.getHistory(sessionId, props.getMemorySize());
    }

    private void saveHistory(String sessionId, String question, String answer) {
        if (sessionId != null && !sessionId.isBlank()) {
            conversationStorePort.saveUser(sessionId, question);
            conversationStorePort.saveAssistant(sessionId, answer);
        }
    }

    private Map<String, Object> handleSmallTalk(String question, String sessionId, long totalStart) {
        log.info("💬 Plan 闲聊短路：跳过 Planner/Executor/Solver → question={}", question);
        List<MessageDTO> messages = List.of(
                new MessageDTO(Role.SYSTEM, promptBuilder.buildSmallTalkSystemPrompt()),
                new MessageDTO(Role.USER, question));
        long start = System.currentTimeMillis();
        String reply = llmPort.chatWithRawMessages(messages);
        long elapsed = System.currentTimeMillis() - start;
        String answer = (reply == null || reply.isBlank()) ? SMALL_TALK_FALLBACK : reply.trim();
        if (answer.isBlank()) answer = SMALL_TALK_FALLBACK;
        traceStore.recordDecision(sessionId, 1, "SMALL_TALK",
                null, null, null,
                question, answer,
                false, "SMALL_TALK_SHORT_CIRCUIT",
                elapsed, "OK", null);
        saveHistory(sessionId, question, answer);
        return buildResponse(answer, List.of(), List.of(), false, null,
                0, 0, System.currentTimeMillis() - totalStart, sessionId);
    }

    /** 兼容前端 {@code renderReActSteps} 的步骤结构（thought/tool/arguments/observation/error/durationMs）。 */
    private static Map<String, Object> buildStepTrace(int step, String tool, Map<String, Object> args,
                                                      String observation, String error, long durationMs, boolean isError) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("step", step);
        trace.put("thought", tool != null ? "计划步骤 " + step + "：调用 " + tool + (error == null ? "" : "（执行失败）") : "计划步骤 " + step);
        trace.put("tool", tool);
        trace.put("arguments", args == null ? Map.of() : args);
        trace.put("observation", observation);
        if (error != null) trace.put("error", error);
        trace.put("durationMs", durationMs);
        trace.put("status", isError ? "ERROR" : "OK");
        return trace;
    }

    private Map<String, Object> buildResponse(String answer, List<Map<String, Object>> sources,
                                              List<Map<String, Object>> stepTraces, boolean queryRewritten,
                                              String rewrittenQuery, long planMs, long execMs,
                                              long totalMs, String sessionId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("answer", answer);
        response.put("sources", sources);
        response.put("mode", "plan");
        response.put("queryRewritten", queryRewritten);
        response.put("rewrittenQuery", rewrittenQuery);
        response.put("planMs", planMs);
        response.put("executeMs", execMs);
        response.put("totalMs", totalMs);
        response.put("steps", stepTraces);
        response.put("stepCount", stepTraces.size());
        response.put("sessionId", sessionId);
        return response;
    }

    private String formatHistory(List<Map.Entry<String, String>> history, int maxPairs) {
        if (history == null || history.isEmpty()) return "";
        int from = Math.max(0, history.size() - maxPairs);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> pair : history.subList(from, history.size())) {
            sb.append("用户: ").append(truncate(pair.getKey(), 200)).append('\n');
            sb.append("助手: ").append(truncate(pair.getValue(), 200)).append('\n');
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
