package com.example.ragassitant.application.service;

import com.example.ragassitant.domain.model.DocumentChunk;
import com.example.ragassitant.domain.model.ToolCall;
import com.example.ragassitant.domain.service.HeuristicRouter;
import com.example.ragassitant.domain.service.PromptBuilder;
import com.example.ragassitant.domain.service.ToolCallParser;
import com.example.ragassitant.domain.service.ToolCallValidator;
import com.example.ragassitant.domain.spi.*;
import com.example.ragassitant.domain.spi.LlmPort.MessageDTO;
import com.example.ragassitant.domain.spi.LlmPort.MessageDTO.Role;
import com.example.ragassitant.infrastructure.agent.DeviceTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AgentAppService {

    private static final Logger log = LoggerFactory.getLogger(AgentAppService.class);
    private static final int MAX_TOOL_CALLS = 5;
    private static final int MAX_HISTORY_PER_SESSION = 20;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final boolean ENABLE_HEURISTIC_ROUTE = true;

    private enum HeuristicStrategy { FORCE_EXEC, RECOMMEND_LLM }
    private static final HeuristicStrategy HEURISTIC_STRATEGY = HeuristicStrategy.FORCE_EXEC;

    private final LlmPort llmPort;
    private final VectorStorePort vectorStorePort;
    private final ConversationStorePort conversationStorePort;
    private final ToolRegistryPort toolRegistry;
    private final HeuristicRouter heuristicRouter;
    private final ToolCallParser toolCallParser;
    private final ToolCallValidator toolCallValidator;
    private final PromptBuilder promptBuilder;
    private final AgentTraceStorePort traceStore;
    private final RagAppService ragAppService;
    // Spring AI 2.0 官方 @Tool 注解的 Bean，ChatClient.tools(deviceTools) 自动识别 6 个工具
    private final DeviceTools deviceTools;

    public AgentAppService(LlmPort llmPort,
                           VectorStorePort vectorStorePort,
                           ConversationStorePort conversationStorePort,
                           ToolRegistryPort toolRegistry,
                           HeuristicRouter heuristicRouter,
                           ToolCallParser toolCallParser,
                           ToolCallValidator toolCallValidator,
                           PromptBuilder promptBuilder,
                           AgentTraceStorePort traceStore,
                           RagAppService ragAppService,
                           DeviceTools deviceTools) {
        this.llmPort = llmPort;
        this.vectorStorePort = vectorStorePort;
        this.conversationStorePort = conversationStorePort;
        this.toolRegistry = toolRegistry;
        this.heuristicRouter = heuristicRouter;
        this.toolCallParser = toolCallParser;
        this.toolCallValidator = toolCallValidator;
        this.promptBuilder = promptBuilder;
        this.traceStore = traceStore;
        this.ragAppService = ragAppService;
        this.deviceTools = deviceTools;
        log.info("✅ AgentAppService 初始化完成（Spring AI 2.0 官方自动 Tool Calling Agent，不再手写 for 循环）");
    }

    public ChatResponse chat(String question, String sessionId, boolean useRag, boolean enableHybridSearch, Integer customTopK) {
        log.info("🤖 Agent 处理问题: sessionId={}, useRag={}, hybrid={}, topK={}, question={}",
                sessionId, useRag, enableHybridSearch, customTopK, question);

        List<MessageDTO> messages = new ArrayList<>();
        messages.add(new MessageDTO(Role.SYSTEM, promptBuilder.buildAgentSystemPrompt()));

        List<Map.Entry<String, String>> history = Collections.emptyList();
        if (sessionId != null && !sessionId.isBlank()) {
            history = conversationStorePort.getHistory(sessionId, MAX_HISTORY_PER_SESSION);
            for (Map.Entry<String, String> pair : history) {
                messages.add(new MessageDTO(Role.USER, pair.getKey()));
                messages.add(new MessageDTO(Role.ASSISTANT, pair.getValue()));
            }
        }

        // ===== 启发式路由的上下文补全：从最近历史用户问题继承 SN/天数 =====
        ToolCall forcedTool = null;
        if (ENABLE_HEURISTIC_ROUTE) {
            forcedTool = heuristicRouter.route(question);
            if (forcedTool == null && !history.isEmpty()) {
                String lastUserQ = null;
                for (int i = history.size() - 1; i >= 0; i--) {
                    lastUserQ = history.get(i).getKey();
                    if (lastUserQ != null && !lastUserQ.isBlank()) break;
                }
                if (lastUserQ != null) {
                    String merged = question + " " + lastUserQ;
                    ToolCall lastT = heuristicRouter.route(lastUserQ);
                    ToolCall mergedT = heuristicRouter.route(merged);
                    forcedTool = mergedT != null ? mergedT : lastT;
                    if (forcedTool != null) {
                        log.info("🧠 上下文补全触发：current='{}' 本身未命中，合并 lastUser='{}' → 命中工具 {}({})",
                                question, lastUserQ, forcedTool.name, forcedTool.arguments);
                    }
                }
            }
        }

        String thisTurnUserQuestion = question;
        String finalAnswer = "";
        List<ChatResponse.SourceDoc> sourceDocs = new ArrayList<>();

        // ===== 优化 1：启发式路由 FORCE_EXEC 提到 RAG 之前，纯工具类问题（列设备、查能耗）完全跳过向量检索 =====
        if (ENABLE_HEURISTIC_ROUTE && forcedTool != null && HEURISTIC_STRATEGY == HeuristicStrategy.FORCE_EXEC) {
            long toolStart = System.currentTimeMillis();
            log.info("🎯[FORCE_EXEC] 启发式命中（先于 RAG）→ 强制调用工具 {}({})，跳过向量检索", forcedTool.name, forcedTool.arguments);
            String forcedResult;
            String toolErr = null;
            try {
                forcedResult = toolRegistry.invoke(forcedTool);
            } catch (Exception e) {
                forcedResult = null;
                toolErr = e.getMessage();
            }
            long toolMs = System.currentTimeMillis() - toolStart;
            log.info("🎯[FORCE_EXEC] 工具 {} 执行完毕，结果长度={}", forcedTool.name, forcedResult == null ? 0 : forcedResult.length());
            traceStore.recordDecision(sessionId, 1, "HEURISTIC_TOOL",
                    forcedTool.name, forcedTool.arguments, forcedResult,
                    null, null, true, "FORCE_EXEC",
                    toolMs, toolErr == null ? "OK" : "ERROR", toolErr);

            messages.add(new MessageDTO(Role.USER, question));
            String argsJson;
            try { argsJson = MAPPER.writeValueAsString(forcedTool.arguments); }
            catch (Exception e) { argsJson = "{}"; }
            messages.add(new MessageDTO(Role.ASSISTANT, "[{\"name\":\"" + forcedTool.name + "\",\"arguments\":" + argsJson + "}]"));
            String userFeedback = promptBuilder.buildForcedToolExecutionFeedback(forcedResult);
            messages.add(new MessageDTO(Role.USER, userFeedback));

            long llmStart = System.currentTimeMillis();
            String summary = callLLM(messages);
            long llmMs = System.currentTimeMillis() - llmStart;
            if (summary != null && !summary.isBlank()) {
                finalAnswer = cleanupAnswer(summary);
            } else {
                finalAnswer = forcedResult == null ? "" : forcedResult;
            }
            traceStore.recordDecision(sessionId, 1, "LLM_SUMMARY",
                    null, null, null,
                    messages.size() + " messages", summary,
                    true, "FORCE_EXEC",
                    llmMs, "OK", null);
            log.info("✅[FORCE_EXEC] 流程结束：LLM 总结结果长度={}", finalAnswer.length());

            if (sessionId != null && !sessionId.isBlank()) {
                conversationStorePort.saveUser(sessionId, thisTurnUserQuestion);
                conversationStorePort.saveAssistant(sessionId, finalAnswer);
            }
            traceStore.recordDecision(sessionId, 1, "FINAL",
                    null, null, null,
                    thisTurnUserQuestion, finalAnswer,
                    true, "FORCE_EXEC",
                    null, "OK", null);
            return new ChatResponse(finalAnswer, sourceDocs);
        }

        // ===== RAG 检索：只在启发式没命中 FORCE_EXEC 时才执行，且包 try-catch（embedding 模型异常不阻断 Agent）=====
        String userContent = question;
        if (useRag) {
            try {
                int effectiveTopK = customTopK != null && customTopK > 0 ? customTopK : 4;
                List<DocumentChunk> docs = ragAppService.retrieveDocuments(question, enableHybridSearch, effectiveTopK);
                if (docs != null && !docs.isEmpty()) {
                    StringBuilder ragContext = new StringBuilder();
                    ragContext.append("## 知识库参考内容\n\n");
                    for (int i = 0; i < docs.size(); i++) {
                        DocumentChunk doc = docs.get(i);
                        ragContext.append("**[").append(i + 1).append("]** ").append(doc.getText()).append("\n\n");
                        Object title = doc.getMetadata() == null ? null : doc.getMetadata().get("title");
                        Object fileName = doc.getMetadata() == null ? null : doc.getMetadata().get("fileName");
                        Object hybridScore = doc.getMetadata() == null ? null : doc.getMetadata().get("hybridScore");
                        Double score = hybridScore instanceof Number ? ((Number) hybridScore).doubleValue() : doc.getScore();
                        sourceDocs.add(new ChatResponse.SourceDoc(
                                title != null ? title.toString() : (fileName != null ? fileName.toString() : "文档" + (i + 1)),
                                fileName != null ? fileName.toString() : "",
                                score
                        ));
                    }
                    userContent = question + "\n\n" + ragContext;
                }
            } catch (Exception e) {
                log.warn("⚠️ RAG 检索失败，已跳过，不影响 Agent 工具调用。原因: {}", e.getMessage());
            }
        }
        messages.add(new MessageDTO(Role.USER, userContent));

        // ===== 启发式路由 RECOMMEND_LLM / 未命中分支 =====
        if (ENABLE_HEURISTIC_ROUTE) {
            if (forcedTool != null) {
                if (HEURISTIC_STRATEGY != HeuristicStrategy.FORCE_EXEC) {
                    log.info("💡[RECOMMEND_LLM] 启发式命中 → 推荐工具 {}({})（最终由 LLM 决定）", forcedTool.name, forcedTool.arguments);
                    String argsJson;
                    try { argsJson = MAPPER.writeValueAsString(forcedTool.arguments); }
                    catch (Exception e) { argsJson = "{}"; }
                    String recommendHint = "\n\n【系统推荐调用工具（准确率 99%+，强烈建议采纳）】\n" +
                            "  工具: " + forcedTool.name + "\n" +
                            "  参数: " + argsJson + "\n" +
                            "  如果你认为系统推荐正确，**只**输出下面这行 JSON，不要加别的：\n" +
                            "  [{\"name\":\"" + forcedTool.name + "\",\"arguments\":" + argsJson + "}]\n" +
                            "  只有当你 100% 确认推荐的工具完全不符合用户意图时，才自行选别的工具调用。\n";
                    MessageDTO lastUserMsg = messages.remove(messages.size() - 1);
                    String augmented = lastUserMsg.content + recommendHint;
                    messages.add(new MessageDTO(Role.USER, augmented));
                }
            } else {
                log.info("🟡 启发式未命中，交由 Spring AI 2.0 ChatClient 自动 Tool Calling（无需手写 for）");
            }
        } else {
            log.info("⚪ 启发式路由已关闭，完全由 Spring AI 2.0 ChatClient 自动 Tool Calling");
        }

        // ===== 🔴 Spring AI 2.0 官方自动 Tool Calling（替换原来的 61 行手写 for 循环）🔴 =====
        // 对比：
        //   - 改造前：61 行代码 → 手动 for (i=0;i<5) → 手动解析 toolCallParser.parse() → 手动校验 → 手动 invoke → 手动拼结果 → 手动循环
        //   - 改造后：1 行代码  → ChatClient 内部自动做全部流程，DeviceTools 上 @Tool 注解的 6 个方法被自动注册
        long agentStart = System.currentTimeMillis();
        traceStore.recordDecision(sessionId, 1, "LLM_AGENT_AUTO_LOOP_START",
                null, null, null,
                messages.size() + " messages", "Spring AI 2.0 ChatClient.tools(DeviceTools)",
                false, "SPRING_AI_2_AUTO",
                null, "OK", null);

        String modelOutput = llmPort.chatWithAutoToolCalling(messages, MAX_TOOL_CALLS, List.of(deviceTools));
        long agentMs = System.currentTimeMillis() - agentStart;

        if (modelOutput == null || modelOutput.isBlank()) {
            finalAnswer = "❌ Agent 处理失败（LLM 返回为空或调用异常）";
            traceStore.recordDecision(sessionId, 1, "FINAL",
                    null, null, null,
                    thisTurnUserQuestion, finalAnswer,
                    false, "SPRING_AI_2_AUTO",
                    agentMs, "ERROR", "model_output_blank");
        } else {
            finalAnswer = cleanupAnswer(modelOutput);
            traceStore.recordDecision(sessionId, 1, "FINAL",
                    null, null, null,
                    thisTurnUserQuestion, finalAnswer,
                    false, "SPRING_AI_2_AUTO",
                    agentMs, "OK", null);
            log.info("✅ Spring AI 2.0 自动 Agent 结束：总耗时 {}ms，最终回答长度={}", agentMs, finalAnswer.length());
        }

        if (finalAnswer.isEmpty()) {
            finalAnswer = "⚠️ 未能生成有效回答，请尝试简化问题。";
        }

        if (sessionId != null && !sessionId.isBlank()) {
            conversationStorePort.saveUser(sessionId, thisTurnUserQuestion);
            conversationStorePort.saveAssistant(sessionId, finalAnswer);
        }

        return new ChatResponse(finalAnswer, sourceDocs);
    }

    private String callLLM(List<MessageDTO> messages) {
        try {
            return llmPort.chatWithRawMessages(messages);
        } catch (Exception e) {
            log.error("❌ LLM 调用失败", e);
            return null;
        }
    }

    private String cleanupAnswer(String answer) {
        if (answer == null) return "";
        String cleaned = answer.replaceAll("(?s)\\[Tools?\\].*?(?=\\n(?:## |### |回答：|基于|综上|因此|总结|设备|根据)|$)", "");
        cleaned = cleaned.replaceAll("(?s)\\{\\s*\"name\"\\s*:.*?\"arguments\"\\s*:.*?\\}", "");
        cleaned = cleaned.replaceAll("(?s)\\[\\s*\\{\\s*\"name\".*?\\}\\s*\\]", "");
        cleaned = cleaned.replace("```json", "").replace("```", "");
        return cleaned.trim();
    }

    public static class ChatResponse {
        public final String answer;
        public final List<SourceDoc> sources;

        public ChatResponse(String answer, List<SourceDoc> sources) {
            this.answer = answer;
            this.sources = sources;
        }

        public static class SourceDoc {
            public final String content;
            public final String metadata;
            public final Double score;

            public SourceDoc(String content, String metadata, Double score) {
                this.content = content;
                this.metadata = metadata;
                this.score = score;
            }
        }
    }
}