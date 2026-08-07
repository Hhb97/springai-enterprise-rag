package com.example.ragassitant.application.service;

import com.example.ragassitant.common.trace.TraceIdFilter;
import com.example.ragassitant.domain.model.DocumentChunk;
import com.example.ragassitant.domain.model.ToolCall;
import com.example.ragassitant.domain.service.PromptBuilder;
import com.example.ragassitant.domain.service.PromptInjectionDetector;
import com.example.ragassitant.domain.service.TextToolCallParser;
import com.example.ragassitant.domain.spi.AgentTraceStorePort;
import com.example.ragassitant.domain.spi.ConversationStorePort;
import com.example.ragassitant.domain.spi.LlmPort;
import com.example.ragassitant.domain.spi.LlmPort.MessageDTO;
import com.example.ragassitant.domain.spi.LlmPort.MessageDTO.Role;
import com.example.ragassitant.domain.spi.ToolRegistryPort;
import com.example.ragassitant.infrastructure.agent.ToolCatalog;
import com.example.ragassitant.infrastructure.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ReActAgentAppService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SMALL_TALK_FALLBACK =
            "您好！我是基于 ReAct 推理模式的智能运维助手，可以帮您查询设备清单与详情、分析能耗数据，也能基于知识库回答业务问题。请问需要什么帮助？";
    private static final ExecutorService STREAM_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "react-stream-worker");
        t.setDaemon(true);
        return t;
    });

    private final LlmPort llmPort;
    private final ConversationStorePort conversationStorePort;
    private final ToolRegistryPort toolRegistry;
    private final PromptBuilder promptBuilder;
    private final RagAppService ragAppService;
    private final QueryRewriteService queryRewriteService;
    private final ToolCatalog toolCatalog;
    private final TextToolCallParser textToolCallParser;
    private final AgentProperties props;
    private final PromptInjectionDetector injectionDetector;
    private final AgentTraceStorePort traceStore;

    public ReActAgentAppService(LlmPort llmPort,
                                 ConversationStorePort conversationStorePort,
                                 ToolRegistryPort toolRegistry,
                                 PromptBuilder promptBuilder,
                                 RagAppService ragAppService,
                                 QueryRewriteService queryRewriteService,
                                 ToolCatalog toolCatalog,
                                 TextToolCallParser textToolCallParser,
                                 AgentProperties props,
                                 PromptInjectionDetector injectionDetector,
                                 AgentTraceStorePort traceStore) {
        this.llmPort = llmPort;
        this.conversationStorePort = conversationStorePort;
        this.toolRegistry = toolRegistry;
        this.promptBuilder = promptBuilder;
        this.ragAppService = ragAppService;
        this.queryRewriteService = queryRewriteService;
        this.toolCatalog = toolCatalog;
        this.textToolCallParser = textToolCallParser;
        this.props = props;
        this.injectionDetector = injectionDetector;
        this.traceStore = traceStore;
        log.info("🧠 ReActAgentAppService 初始化完成（手动 ReAct 循环，maxSteps={}，链路追踪已开启）", props.getMaxToolCalls());
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

    /**
     * Stream 版本：ReAct 循环每一步 Thought / Action / Observation 阶段以
     * Server-Sent Events 方式分事件推送，最后 Final Answer 按字符流式吐。
     * <p>使用 Spring MVC 原生 {@link SseEmitter}，不需要额外引入 WebFlux 依赖。
     */
    public SseEmitter chatStream(String question, String sessionId,
                                  boolean useRag, boolean enableHybridSearch, Integer customTopK) {
        String sess = sessionId == null ? "" : sessionId;
        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟超时
        AtomicBoolean finished = new AtomicBoolean(false);
        emitter.onCompletion(() -> finished.set(true));
        emitter.onTimeout(() -> {
            log.warn("🧠 ReActStream SSE 超时: sessionId={}", sess);
            finished.set(true);
        });
        emitter.onError(e -> {
            log.warn("🧠 ReActStream SSE 错误: sessionId={}, err={}", sess, e.toString());
            finished.set(true);
        });

        STREAM_EXECUTOR.submit(() -> {
            MDC.put(TraceIdFilter.SESSION_ID_KEY, sess);
            try {
                runReActLoopStreamBlocking(emitter, finished, question, sess, useRag, enableHybridSearch, customTopK);
            } catch (Throwable t) {
                log.error("🧠 ReActStream 执行异常", t);
                safeEmitError(emitter, finished, t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            } finally {
                MDC.remove(TraceIdFilter.SESSION_ID_KEY);
                safeEmitComplete(emitter, finished);
            }
        });

        return emitter;
    }

    private void safeEmit(SseEmitter emitter, AtomicBoolean finished, String eventName, Object data) {
        if (finished.get()) return;
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            log.debug("🧠 ReActStream 推送事件失败 (客户端已断开？): event={}, err={}", eventName, e.toString());
            finished.set(true);
        }
    }

    private void safeEmitError(SseEmitter emitter, AtomicBoolean finished, String message) {
        if (finished.getAndSet(true)) return;
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of("message", message)));
        } catch (Exception ignored) {}
    }

    private void safeEmitComplete(SseEmitter emitter, AtomicBoolean finished) {
        if (finished.getAndSet(true)) return;
        try { emitter.complete(); } catch (Exception ignored) {}
    }

    private void runReActLoopStreamBlocking(
            SseEmitter emitter, AtomicBoolean finished,
            String question, String sessionId, boolean useRag,
            boolean enableHybridSearch, Integer customTopK) {

        long totalStart = System.currentTimeMillis();
        log.info("🧠 ReActStream 处理问题: sessionId={}, useRag={}, question={}", sessionId, useRag, question);

        List<Map.Entry<String, String>> history = conversationStorePort.getHistory(
                sessionId, props.getMemorySize());

        if (isSmallTalk(question)) {
            handleSmallTalkStream(emitter, finished, question, sessionId);
            return;
        }
        if (finished.get()) return;

        // ️ Prompt Injection 安全检测
        if (injectionDetector.isPromptInjection(question)) {
            String attackType = injectionDetector.getAttackType(question);
            log.warn("🛡️ 拦截 Prompt Injection 攻击: sessionId={}, attackType={}", sessionId, attackType);
            String rejectionMsg = "抱歉，我检测到您的请求中包含试图绕过系统安全策略的指令。作为智能运维助手，我必须遵守预设的角色和职责范围，无法执行此类操作。如果您有设备查询、能耗分析或运维知识库相关的问题，欢迎随时向我提问！";
            traceStore.recordDecision(sessionId, 1, "SECURITY_BLOCK",
                    null, null, null,
                    rejectionMsg, rejectionMsg,
                    false, "prompt_injection",
                    0L, "BLOCKED", attackType);
            conversationStorePort.saveUser(sessionId, question);
            conversationStorePort.saveAssistant(sessionId, rejectionMsg);
            safeEmit(emitter, finished, "security_block", Map.of(
                    "type", "prompt_injection",
                    "attackType", attackType,
                    "answer", rejectionMsg
            ));
            safeEmit(emitter, finished, "final_answer", Map.of("answer", rejectionMsg));
            safeEmit(emitter, finished, "done", Map.of("durationMs", System.currentTimeMillis() - totalStart));
            return;
        }
        if (finished.get()) return;

        String retrievalQuery = question;
        boolean queryRewritten = false;
        String rewrittenQuery = null;

        if (!history.isEmpty() && queryRewriteService.isEnabled() && !finished.get()) {
            long rwStart = System.currentTimeMillis();
            List<String> rewrites = queryRewriteService.rewrite(question, history, sessionId);
            long rewriteMs = System.currentTimeMillis() - rwStart;
            if (rewrites != null && !rewrites.isEmpty()) {
                rewrittenQuery = rewrites.get(0);
                queryRewritten = !rewrittenQuery.equals(question);
                if (queryRewritten) {
                    retrievalQuery = rewrittenQuery;
                    safeEmit(emitter, finished, "rewrite",
                            Map.of("from", question, "to", rewrittenQuery, "ms", rewriteMs));
                }
            }
        }
        if (finished.get()) return;

        List<DocumentChunk> docs = new ArrayList<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        String systemPrompt;

        if (useRag && !finished.get()) {
            try {
                int topK = customTopK != null ? customTopK : 5;
                docs = ragAppService.retrieveDocuments(retrievalQuery, enableHybridSearch, topK);
                for (DocumentChunk doc : docs) {
                    Map<String, Object> source = new HashMap<>();
                    source.put("content", doc.getText());
                    Map<String, Object> meta = doc.getMetadata();
                    Object title = meta != null ? meta.get("title") : null;
                    Object fileName = meta != null ? meta.get("fileName") : null;
                    source.put("metadata", (title != null ? title : (fileName != null ? fileName : "文档")));
                    source.put("score", doc.getScore());
                    sources.add(source);
                }
                if (!docs.isEmpty()) {
                    safeEmit(emitter, finished, "rag_ready", Map.of("count", docs.size()));
                }
            } catch (Exception e) {
                log.warn("⚠️ ReActStream RAG 检索失败，已跳过: {}", e.getMessage());
            }
        }
        if (finished.get()) return;

        systemPrompt = promptBuilder.buildReActSystemPrompt(toolCatalog.renderForPrompt(), docs);
        List<MessageDTO> currentMessages = buildBaseMessages(history, systemPrompt);
        currentMessages.add(new MessageDTO(Role.USER, question));

        int maxSteps = props.getMaxToolCalls();
        List<Map<String, Object>> stepTraces = new ArrayList<>();
        String finalAnswer = "";

        traceStore.recordDecision(sessionId, 1, "REACT_LOOP_START",
                null, null, null,
                question,
                "maxSteps=" + maxSteps + ", ragChunks=" + docs.size() + ", stream=true",
                false, "REACT_MANUAL_LOOP",
                null, "OK", null);

        for (int step = 1; step <= maxSteps && !finished.get(); step++) {
            long stepStart = System.currentTimeMillis();
            safeEmit(emitter, finished, "step_start", Map.of("step", step, "maxSteps", maxSteps));
            log.info("🧠 ReActStream step {} start, messages={}", step, currentMessages.size());

            // ===== Thought 阶段：流式逐 token 推送 thought_delta，同时在本地攒全量 =====
            StringBuilder buf = new StringBuilder();
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Throwable> streamErr = new AtomicReference<>();
            try {
                int finalStep = step;
                llmPort.chatStreamWithRawMessages(currentMessages)
                        .doOnNext(token -> {
                            if (finished.get()) return;
                            if (token != null && !token.isEmpty()) {
                                buf.append(token);
                                safeEmit(emitter, finished, "thought_delta",
                                        Map.of("step", finalStep, "delta", token));
                            }
                        })
                        .doOnError(streamErr::set)
                        .doOnComplete(() -> latch.countDown())
                        .subscribe();
                boolean ok = latch.await(180, TimeUnit.SECONDS);
                if (finished.get()) return;
                if (!ok || streamErr.get() != null) {
                    String msg = streamErr.get() != null ? streamErr.get().getMessage() : "LLM 流式调用超时";
                    stepTraces.add(buildStepTrace(step, "LLM 调用出错", null, null, msg, System.currentTimeMillis() - stepStart));
                    traceStore.recordDecision(sessionId, 1, "REACT_STEP",
                            null, null, null,
                            "Thought step " + step,
                            "Thought: LLM 调用出错",
                            false, "REACT_STEP_ERROR",
                            System.currentTimeMillis() - stepStart, "ERROR", msg);
                    safeEmit(emitter, finished, "error", Map.of("step", step, "message", msg));
                    finalAnswer = "推理步骤 " + step + " 出错: " + msg;
                    break;
                }
            } catch (Exception e) {
                stepTraces.add(buildStepTrace(step, "LLM 调用出错", null, null, e.getMessage(), System.currentTimeMillis() - stepStart));
                traceStore.recordDecision(sessionId, 1, "REACT_STEP",
                        null, null, null,
                        "Thought step " + step,
                        "Thought: LLM 调用异常",
                        false, "REACT_STEP_ERROR",
                        System.currentTimeMillis() - stepStart, "ERROR", e.getMessage());
                safeEmit(emitter, finished, "error", Map.of("step", step, "message", e.getMessage()));
                finalAnswer = "推理步骤 " + step + " 出错: " + e.getMessage();
                break;
            }

            String llmResponse = buf.toString();
            if (llmResponse.isBlank()) {
                stepTraces.add(buildStepTrace(step, "模型返回空响应", null, null, "空", System.currentTimeMillis() - stepStart));
                traceStore.recordDecision(sessionId, 1, "REACT_STEP",
                        null, null, null,
                        "Thought step " + step,
                        "Thought: 模型返回空响应",
                        false, "REACT_STEP_EMPTY",
                        System.currentTimeMillis() - stepStart, "ERROR", "模型返回空");
                finalAnswer = "模型返回空响应，请重试。";
                break;
            }

            StepDecision decision = parseReActResponse(llmResponse);
            long stepMs = System.currentTimeMillis() - stepStart;
            log.info("🧠 ReActStream step {} end: thought={} tool={} final={}",
                    step, truncate(decision.thought(), 60),
                    decision.toolCall() != null ? decision.toolCall().name : "none",
                    decision.isFinalAnswer());

            // ===== 发送 thought_end（携带 tool 名或 final 标识）给前端渲染 =====
            Map<String, Object> thoughtEnd = new LinkedHashMap<>();
            thoughtEnd.put("step", step);
            thoughtEnd.put("thought", decision.thought());
            thoughtEnd.put("isFinalAnswer", decision.isFinalAnswer());
            if (decision.toolCall() != null) {
                thoughtEnd.put("tool", decision.toolCall().name);
                thoughtEnd.put("arguments", decision.toolCall().arguments);
            }
            safeEmit(emitter, finished, "thought_end", thoughtEnd);
            if (finished.get()) return;

            // ===== Final Answer：直接打字机逐字符流 final_delta =====
            if (decision.isFinalAnswer()) {
                stepTraces.add(buildStepTrace(step, decision.thought(), null, decision.answer(), null, stepMs));
                traceStore.recordDecision(sessionId, 1, "REACT_STEP",
                        null, null, null,
                        truncate(decision.thought(), 500),
                        "FinalAnswer 长度=" + (decision.answer() == null ? 0 : decision.answer().length()),
                        false, "REACT_STEP_FINAL",
                        stepMs, "OK", null);
                finalAnswer = decision.answer() == null ? "" : decision.answer();
                streamFinalAnswer(emitter, finished, finalAnswer);
                break;
            }

            if (decision.toolCall() == null) {
                stepTraces.add(buildStepTrace(step, decision.thought(), null, null, "未识别到工具调用或最终答案", stepMs));
                traceStore.recordDecision(sessionId, 1, "REACT_STEP",
                        null, null, null,
                        truncate(decision.thought(), 500),
                        "未识别到工具调用或最终答案，提前结束",
                        false, "REACT_STEP_NO_TOOL",
                        stepMs, "WARN", "parse_fail_tool_or_answer");
                finalAnswer = (decision.answer() != null && !decision.answer().isBlank())
                        ? decision.answer()
                        : (decision.thought() != null ? decision.thought() : cleanupAnswer(llmResponse));
                streamFinalAnswer(emitter, finished, finalAnswer);
                break;
            }

            // ===== Action 阶段：通知前端 loading，调工具 =====
            ToolCall tc = decision.toolCall();
            safeEmit(emitter, finished, "action_start",
                    Map.of("step", step, "tool", tc.name, "arguments", tc.arguments == null ? Map.of() : tc.arguments));
            if (finished.get()) return;

            long actStart = System.currentTimeMillis();
            String observation;
            boolean toolOk = true;
            String toolErr = null;
            try {
                observation = toolRegistry.invoke(tc);
            } catch (Exception e) {
                log.error("🧠 ReActStream step {} tool exec failed: {}", step, e.getMessage());
                observation = "工具执行失败: " + e.getMessage();
                toolOk = false;
                toolErr = e.getMessage();
            }
            long actMs = System.currentTimeMillis() - actStart;

            safeEmit(emitter, finished, "action_end",
                    Map.of("step", step, "observation", observation, "durationMs", actMs, "tool", tc.name));
            if (finished.get()) return;

            stepTraces.add(buildStepTrace(step, decision.thought(), tc, observation, null, stepMs));
            traceStore.recordDecision(sessionId, 1, "REACT_STEP",
                    tc.name, tc.arguments, observation,
                    truncate(decision.thought(), 500),
                    "Tool 执行长度=" + (observation == null ? 0 : observation.length()),
                    false, "REACT_STEP_TOOL_" + (toolOk ? "OK" : "ERR"),
                    stepMs, toolOk ? "OK" : "ERROR", toolErr);

            // ===== Observation 塞回 messages，继续下一轮 =====
            currentMessages.add(new MessageDTO(Role.ASSISTANT, llmResponse));
            String observationPrompt = buildObservationPrompt(step, maxSteps, observation);
            currentMessages.add(new MessageDTO(Role.USER, observationPrompt));
        }

        if (finished.get()) return;

        if (finalAnswer == null || finalAnswer.isBlank()) {
            finalAnswer = "已达到最大推理步数 (" + maxSteps + ")，未能得出最终答案。请尝试更具体的问题，或提供更多参数。";
            traceStore.recordDecision(sessionId, 1, "REACT_STEP",
                    null, null, null,
                    "maxSteps=" + maxSteps,
                    "已达最大步数，提前终止",
                    false, "REACT_MAX_STEPS_REACHED",
                    System.currentTimeMillis() - totalStart, "WARN", "max_steps_reached");
            streamFinalAnswer(emitter, finished, finalAnswer);
        }
        if (finished.get()) return;

        conversationStorePort.saveUser(sessionId, question);
        conversationStorePort.saveAssistant(sessionId, finalAnswer);

        long totalMs = System.currentTimeMillis() - totalStart;
        traceStore.recordDecision(sessionId, 1, "FINAL",
                null, null, null,
                question, finalAnswer,
                false, "REACT_MANUAL_LOOP",
                totalMs, "OK", null);

        Map<String, Object> done = new LinkedHashMap<>();
        done.put("answer", finalAnswer);
        done.put("sources", sources);
        done.put("mode", "react");
        done.put("queryRewritten", queryRewritten);
        done.put("rewrittenQuery", rewrittenQuery);
        done.put("totalMs", totalMs);
        done.put("steps", stepTraces);
        done.put("stepCount", stepTraces.size());
        done.put("sessionId", sessionId);
        done.put("stream", true);

        safeEmit(emitter, finished, "done", done);
        log.info("🧠 ReActStream 完成: {} steps, {}ms", stepTraces.size(), totalMs);
    }

    private String buildObservationPrompt(int step, int maxSteps, String observation) {
        String prompt = "【Observation 工具执行结果】\n```\n" + observation + "\n```\n\n";
        if (step == maxSteps) {
            prompt += "已达到最大步数，请直接基于以上结果用中文给出最终回答，不要再调用任何工具。";
        } else if (step == maxSteps - 1) {
            prompt += "剩余 1 步，信息足够就直接给出最终回答，信息不足再调用最后一次工具。";
        } else {
            prompt += "请继续：信息足够就给出最终回答（tool=null 并填 answer），否则继续调用下一个工具。";
        }
        return prompt;
    }

    /** Final Answer 打字机效果：按字符切片推送，前端体验和普通 RAG Stream 一致。 */
    private void streamFinalAnswer(SseEmitter emitter, AtomicBoolean finished, String answer) {
        if (answer == null || answer.isEmpty()) return;
        safeEmit(emitter, finished, "final_start", Map.of("length", answer.length()));
        int chunkSize = 3;
        for (int i = 0; i < answer.length() && !finished.get(); ) {
            int end = Math.min(i + chunkSize, answer.length());
            String delta = answer.substring(i, end);
            safeEmit(emitter, finished, "final_delta", Map.of("delta", delta));
            i = end;
            try {
                TimeUnit.MILLISECONDS.sleep(8);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void handleSmallTalkStream(SseEmitter emitter, AtomicBoolean finished, String question, String sessionId) {
        log.info("💬 ReActStream 闲聊短路");
        List<MessageDTO> messages = List.of(
                new MessageDTO(Role.SYSTEM, promptBuilder.buildSmallTalkSystemPrompt()),
                new MessageDTO(Role.USER, question));
        long start = System.currentTimeMillis();

        StringBuilder buf = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> fullReply = new AtomicReference<>();
        llmPort.chatStreamWithRawMessages(messages)
                .doOnNext(t -> {
                    if (finished.get()) return;
                    if (t != null && !t.isEmpty()) {
                        buf.append(t);
                        safeEmit(emitter, finished, "final_delta", Map.of("delta", t));
                    }
                })
                .doOnComplete(() -> {
                    String reply = buf.toString();
                    String answer = (reply == null || reply.isBlank()) ? SMALL_TALK_FALLBACK : cleanupAnswer(reply);
                    if (answer.isBlank()) answer = SMALL_TALK_FALLBACK;
                    fullReply.set(answer);
                    long ms = System.currentTimeMillis() - start;
                    traceStore.recordDecision(sessionId, 1, "SMALL_TALK",
                            null, null, null,
                            question, answer,
                            false, "SMALL_TALK_SHORT_CIRCUIT",
                            ms, "OK", null);
                    conversationStorePort.saveUser(sessionId, question);
                    conversationStorePort.saveAssistant(sessionId, answer);
                    safeEmit(emitter, finished, "done", Map.of(
                            "answer", answer, "sources", List.of(), "mode", "react",
                            "totalMs", ms, "steps", List.of(), "stepCount", 0,
                            "sessionId", sessionId, "stream", true, "smallTalk", true));
                    latch.countDown();
                })
                .doOnError(e -> {
                    String answer = SMALL_TALK_FALLBACK;
                    long ms = System.currentTimeMillis() - start;
                    traceStore.recordDecision(sessionId, 1, "SMALL_TALK",
                            null, null, null,
                            question, answer,
                            false, "SMALL_TALK_SHORT_CIRCUIT",
                            ms, "ERROR", e.getMessage());
                    conversationStorePort.saveUser(sessionId, question);
                    conversationStorePort.saveAssistant(sessionId, answer);
                    safeEmitError(emitter, finished, e.getMessage() == null ? "闲聊回复失败" : e.getMessage());
                    safeEmit(emitter, finished, "done", Map.of(
                            "answer", answer, "sources", List.of(), "mode", "react",
                            "totalMs", ms, "steps", List.of(), "stepCount", 0,
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

    private Map<String, Object> doChat(String question, String sessionId, boolean useRag,
                                        boolean enableHybridSearch, Integer customTopK) {
        long totalStart = System.currentTimeMillis();
        log.info("🧠 ReAct 处理问题: sessionId={}, useRag={}, question={}", sessionId, useRag, question);

        List<Map.Entry<String, String>> history = conversationStorePort.getHistory(
                sessionId, props.getMemorySize());

        if (isSmallTalk(question)) {
            return handleSmallTalk(question, sessionId);
        }

        // 🛡️ Prompt Injection 安全检测（非流式之前漏掉了，现在补上）
        if (injectionDetector.isPromptInjection(question)) {
            String attackType = injectionDetector.getAttackType(question);
            log.warn("🛡️ 拦截 Prompt Injection 攻击: sessionId={}, attackType={}", sessionId, attackType);
            String rejectionMsg = "抱歉，我检测到您的请求中包含试图绕过系统安全策略的指令。作为智能运维助手，我必须遵守预设的角色和职责范围，无法执行此类操作。如果您有设备查询、能耗分析或运维知识库相关的问题，欢迎随时向我提问！";
            traceStore.recordDecision(sessionId, 1, "SECURITY_BLOCK",
                    null, null, null,
                    rejectionMsg, rejectionMsg,
                    false, "prompt_injection",
                    0L, "BLOCKED", attackType);
            conversationStorePort.saveUser(sessionId, question);
            conversationStorePort.saveAssistant(sessionId, rejectionMsg);
            Map<String, Object> r = new HashMap<>();
            r.put("answer", rejectionMsg);
            r.put("sources", List.of());
            r.put("mode", "react");
            r.put("totalMs", System.currentTimeMillis() - totalStart);
            r.put("blocked", true);
            r.put("blockType", "prompt_injection");
            return r;
        }

        String retrievalQuery = question;
        boolean queryRewritten = false;
        String rewrittenQuery = null;
        long rewriteMs = 0;

        if (!history.isEmpty() && queryRewriteService.isEnabled()) {
            long rwStart = System.currentTimeMillis();
            List<String> rewrites = queryRewriteService.rewrite(question, history, sessionId);
            rewriteMs = System.currentTimeMillis() - rwStart;
            if (rewrites != null && !rewrites.isEmpty()) {
                rewrittenQuery = rewrites.get(0);
                queryRewritten = !rewrittenQuery.equals(question);
                if (queryRewritten) {
                    retrievalQuery = rewrittenQuery;
                    log.info("🧠 ReAct Query 改写: '{}' → '{}'", question, rewrittenQuery);
                }
            }
        }

        List<DocumentChunk> docs = new ArrayList<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        String systemPrompt;

        if (useRag) {
            try {
                int topK = customTopK != null ? customTopK : 5;
                docs = ragAppService.retrieveDocuments(retrievalQuery, enableHybridSearch, topK);
                for (DocumentChunk doc : docs) {
                    Map<String, Object> source = new HashMap<>();
                    source.put("content", doc.getText());
                    Map<String, Object> meta = doc.getMetadata();
                    Object title = meta != null ? meta.get("title") : null;
                    Object fileName = meta != null ? meta.get("fileName") : null;
                    source.put("metadata", (title != null ? title : (fileName != null ? fileName : "文档")));
                    source.put("score", doc.getScore());
                    sources.add(source);
                }
            } catch (Exception e) {
                log.warn("⚠️ ReAct RAG 检索失败，已跳过: {}", e.getMessage());
            }
        }

        systemPrompt = promptBuilder.buildReActSystemPrompt(toolCatalog.renderForPrompt(), docs);

        List<MessageDTO> messages = buildBaseMessages(history, systemPrompt);
        messages.add(new MessageDTO(Role.USER, question));

        int maxSteps = props.getMaxToolCalls();
        traceStore.recordDecision(sessionId, 1, "REACT_LOOP_START",
                null, null, null,
                question,
                "maxSteps=" + maxSteps + ", ragChunks=" + docs.size() + ", stream=false",
                false, "REACT_MANUAL_LOOP",
                null, "OK", null);

        ReActResult result = runReActLoop(messages, sessionId, maxSteps);

        String answer = result.finalAnswer();
        List<Map<String, Object>> stepTraces = result.stepTraces();

        conversationStorePort.saveUser(sessionId, question);
        conversationStorePort.saveAssistant(sessionId, answer);

        long totalMs = System.currentTimeMillis() - totalStart;
        traceStore.recordDecision(sessionId, 1, "FINAL",
                null, null, null,
                question, answer,
                false, "REACT_MANUAL_LOOP",
                totalMs, "OK", null);

        Map<String, Object> response = new HashMap<>();
        response.put("answer", answer);
        response.put("sources", sources);
        response.put("mode", "react");
        response.put("queryRewritten", queryRewritten);
        response.put("rewrittenQuery", rewrittenQuery);
        response.put("rewriteMs", rewriteMs);
        response.put("totalMs", totalMs);
        response.put("steps", stepTraces);
        response.put("stepCount", stepTraces.size());
        response.put("sessionId", sessionId);

        log.info("🧠 ReAct 完成: {} steps, {}ms, answer length={}", stepTraces.size(), totalMs, answer.length());
        return response;
    }

    private Map<String, Object> handleSmallTalk(String question, String sessionId) {
        log.info("💬 ReAct 闲聊短路");
        List<MessageDTO> messages = List.of(
                new MessageDTO(Role.SYSTEM, promptBuilder.buildSmallTalkSystemPrompt()),
                new MessageDTO(Role.USER, question));

        long start = System.currentTimeMillis();
        String reply = llmPort.chatWithRawMessages(messages);
        long elapsed = System.currentTimeMillis() - start;

        String answer = (reply == null || reply.isBlank()) ? SMALL_TALK_FALLBACK : cleanupAnswer(reply);
        if (answer.isBlank()) answer = SMALL_TALK_FALLBACK;

        traceStore.recordDecision(sessionId, 1, "SMALL_TALK",
                null, null, null,
                question, answer,
                false, "SMALL_TALK_SHORT_CIRCUIT",
                elapsed, "OK", null);
        conversationStorePort.saveUser(sessionId, question);
        conversationStorePort.saveAssistant(sessionId, answer);

        Map<String, Object> response = new HashMap<>();
        response.put("answer", answer);
        response.put("sources", List.of());
        response.put("mode", "react");
        response.put("totalMs", elapsed);
        response.put("steps", List.of());
        response.put("stepCount", 0);

        return response;
    }

    private ReActResult runReActLoop(List<MessageDTO> messages, String sessionId, int maxSteps) {
        List<Map<String, Object>> stepTraces = new ArrayList<>();
        List<MessageDTO> currentMessages = new ArrayList<>(messages);
        String finalAnswer = null;

        for (int step = 1; step <= maxSteps; step++) {
            long stepStart = System.currentTimeMillis();
            log.info("🧠 ReAct step {}: sending {} messages to LLM", step, currentMessages.size());

            String llmResponse;
            try {
                llmResponse = llmPort.chatWithRawMessages(currentMessages);
            } catch (Exception e) {
                log.error("🧠 ReAct step {} failed: {}", step, e.getMessage());
                stepTraces.add(buildStepTrace(step, "LLM 调用出错", null, null, e.getMessage(), System.currentTimeMillis() - stepStart));
                traceStore.recordDecision(sessionId, 1, "REACT_STEP",
                        null, null, null,
                        "Thought step " + step,
                        "Thought: LLM 调用异常",
                        false, "REACT_STEP_ERROR",
                        System.currentTimeMillis() - stepStart, "ERROR", e.getMessage());
                finalAnswer = "推理步骤 " + step + " 出错: " + e.getMessage();
                break;
            }

            if (llmResponse == null || llmResponse.isBlank()) {
                stepTraces.add(buildStepTrace(step, "模型返回空响应", null, null, "模型返回空", System.currentTimeMillis() - stepStart));
                traceStore.recordDecision(sessionId, 1, "REACT_STEP",
                        null, null, null,
                        "Thought step " + step,
                        "Thought: 模型返回空响应",
                        false, "REACT_STEP_EMPTY",
                        System.currentTimeMillis() - stepStart, "ERROR", "模型返回空");
                finalAnswer = "模型返回空响应，请重试。";
                break;
            }

            StepDecision decision = parseReActResponse(llmResponse);
            long stepMs = System.currentTimeMillis() - stepStart;

            log.info("🧠 ReAct step {}: thought='{}' tool={} final={}",
                    step, truncate(decision.thought(), 80),
                    decision.toolCall() != null ? decision.toolCall().name : "none",
                    decision.isFinalAnswer());

            if (decision.isFinalAnswer()) {
                stepTraces.add(buildStepTrace(step, decision.thought(), null, decision.answer(), null, stepMs));
                traceStore.recordDecision(sessionId, 1, "REACT_STEP",
                        null, null, null,
                        truncate(decision.thought(), 500),
                        "FinalAnswer 长度=" + (decision.answer() == null ? 0 : decision.answer().length()),
                        false, "REACT_STEP_FINAL",
                        stepMs, "OK", null);
                finalAnswer = decision.answer();
                break;
            }

            if (decision.toolCall() == null) {
                stepTraces.add(buildStepTrace(step, decision.thought(), null, null, "未识别到工具调用或最终答案", stepMs));
                traceStore.recordDecision(sessionId, 1, "REACT_STEP",
                        null, null, null,
                        truncate(decision.thought(), 500),
                        "未识别到工具调用或最终答案，提前结束",
                        false, "REACT_STEP_NO_TOOL",
                        stepMs, "WARN", "parse_fail_tool_or_answer");
                finalAnswer = (decision.answer() != null && !decision.answer().isBlank())
                        ? decision.answer()
                        : (decision.thought() != null ? decision.thought() : cleanupAnswer(llmResponse));
                break;
            }

            String observation;
            boolean toolOk = true;
            String toolErr = null;
            try {
                observation = toolRegistry.invoke(decision.toolCall());
            } catch (Exception e) {
                log.error("🧠 ReAct step {} tool execution failed: {}", step, e.getMessage());
                observation = "工具执行失败: " + e.getMessage();
                toolOk = false;
                toolErr = e.getMessage();
            }

            stepTraces.add(buildStepTrace(step, decision.thought(), decision.toolCall(), observation, null, stepMs));
            traceStore.recordDecision(sessionId, 1, "REACT_STEP",
                    decision.toolCall().name, decision.toolCall().arguments, observation,
                    truncate(decision.thought(), 500),
                    "Tool 执行长度=" + (observation == null ? 0 : observation.length()),
                    false, "REACT_STEP_TOOL_" + (toolOk ? "OK" : "ERR"),
                    stepMs, toolOk ? "OK" : "ERROR", toolErr);

            currentMessages.add(new MessageDTO(Role.ASSISTANT, llmResponse));
            String observationPrompt = "【Observation 工具执行结果】\n```\n" + observation + "\n```\n\n";
            if (step == maxSteps) {
                observationPrompt += "已达到最大步数，请直接基于以上结果用中文给出最终回答，不要再调用任何工具。";
            } else if (step == maxSteps - 1) {
                observationPrompt += "剩余 1 步，信息足够就直接给出最终回答，信息不足再调用最后一次工具。";
            } else {
                observationPrompt += "请继续：信息足够就给出最终回答（tool=null 并填 answer），否则继续调用下一个工具。";
            }
            currentMessages.add(new MessageDTO(Role.USER, observationPrompt));
        }

        if (finalAnswer == null || finalAnswer.isBlank()) {
            finalAnswer = "已达到最大推理步数 (" + maxSteps + ")，未能得出最终答案。请尝试更具体的问题，或提供更多参数。";
            traceStore.recordDecision(sessionId, 1, "REACT_STEP",
                    null, null, null,
                    "maxSteps=" + maxSteps,
                    "已达最大步数，提前终止",
                    false, "REACT_MAX_STEPS_REACHED",
                    null, "WARN", "max_steps_reached");
        }

        return new ReActResult(finalAnswer, stepTraces);
    }

    private StepDecision parseReActResponse(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return new StepDecision("", null, true, "模型未返回有效内容");
        }

        List<ToolCall> parsedCalls = textToolCallParser.parse(llmResponse);

        if (parsedCalls != null && !parsedCalls.isEmpty()) {
            StringBuilder thought = new StringBuilder();
            String cleaned = llmResponse.replace("```json", "").replace("```", "").trim();
            int jsonStart = cleaned.indexOf('[');
            if (jsonStart < 0) jsonStart = cleaned.indexOf('{');
            if (jsonStart > 0) {
                thought.append(cleaned, 0, jsonStart).trimToSize();
            }
            if (thought.length() == 0) {
                thought.append("需要调用工具: ").append(parsedCalls.get(0).name);
            }
            return new StepDecision(thought.toString(), parsedCalls.get(0), false, null);
        }

        Map<String, Object> structured = parseStructuredJson(llmResponse);
        if (structured != null) {
            String thought = getString(structured, "thought");
            String toolName = getString(structured, "tool");
            Object argsObj = structured.get("arguments");
            String answer = getString(structured, "answer");

            if (toolName != null && !toolName.isBlank() && !"null".equalsIgnoreCase(toolName)) {
                Map<String, Object> args = new HashMap<>();
                if (argsObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) argsObj;
                    args = map;
                }
                return new StepDecision(thought, new ToolCall(toolName, args), false, null);
            }

            if (answer != null && !answer.isBlank()) {
                return new StepDecision(thought, null, true, answer);
            }

            if (thought != null && !thought.isBlank()) {
                return new StepDecision(thought, null, true, thought);
            }
        }

        return new StepDecision("", null, true, cleanupAnswer(llmResponse));
    }

    private Map<String, Object> parseStructuredJson(String text) {
        try {
            String cleaned = text.replace("```json", "").replace("```", "").trim();
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) {
                String json = cleaned.substring(start, end + 1);
                @SuppressWarnings("unchecked")
                Map<String, Object> map = MAPPER.readValue(json, Map.class);
                return map;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }

    private List<MessageDTO> buildBaseMessages(List<Map.Entry<String, String>> history, String systemPrompt) {
        List<MessageDTO> messages = new ArrayList<>();
        messages.add(new MessageDTO(Role.SYSTEM, systemPrompt));
        for (Map.Entry<String, String> pair : history) {
            messages.add(new MessageDTO(Role.USER, pair.getKey()));
            messages.add(new MessageDTO(Role.ASSISTANT, pair.getValue()));
        }
        return messages;
    }

    private Map<String, Object> buildStepTrace(int step, String thought, ToolCall action,
                                                String observation, String errorMsg, long durationMs) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("step", step);
        trace.put("thought", thought);
        trace.put("tool", action != null ? action.name : null);
        trace.put("arguments", action != null ? action.arguments : null);
        trace.put("observation", observation);
        trace.put("error", errorMsg);
        trace.put("durationMs", durationMs);
        return trace;
    }

    private String cleanupAnswer(String answer) {
        if (answer == null) return "";
        String cleaned = answer.replaceAll("(?s)\\[Tools?\\].*?(?=\\n(?:## |### |回答：|基于|综上|因此|总结|设备|根据)|$)", "");
        cleaned = cleaned.replaceAll("(?s)\\{\\s*\"name\"\\s*:.*?\"arguments\"\\s*:.*?\\}", "");
        cleaned = cleaned.replaceAll("(?s)\\[\\s*\\{\\s*\"name\".*?\\}\\s*\\]", "");
        cleaned = cleaned.replace("```json", "").replace("```", "");
        cleaned = cleaned.replaceAll("^Thought[：:].*$", "");
        cleaned = cleaned.replaceAll("^Action[：:].*$", "");
        cleaned = cleaned.replaceAll("^Observation[：:].*$", "");
        cleaned = cleaned.replaceAll("^Answer[：:].*$", "");
        return cleaned.trim();
    }

    private boolean isSmallTalk(String q) {
        if (q == null || q.isBlank()) return false;
        String lower = q.trim().toLowerCase();
        return SMALL_TALK_PATTERN.matcher(lower).matches();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static final Pattern SMALL_TALK_PATTERN = Pattern.compile(
            "^(你好|您好|hi|hello|hey|thanks|谢谢|再见|拜拜|bye|good\\s*morning|早上好|晚安|good\\s*night|你是谁|你叫什么|介绍一下自己)$"
    );

    private record StepDecision(String thought, ToolCall toolCall, boolean isFinalAnswer, String answer) {}

    private record ReActResult(String finalAnswer, List<Map<String, Object>> stepTraces) {}
}