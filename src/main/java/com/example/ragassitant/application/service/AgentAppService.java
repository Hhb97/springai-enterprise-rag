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

    public AgentAppService(LlmPort llmPort,
                           VectorStorePort vectorStorePort,
                           ConversationStorePort conversationStorePort,
                           ToolRegistryPort toolRegistry,
                           HeuristicRouter heuristicRouter,
                           ToolCallParser toolCallParser,
                           ToolCallValidator toolCallValidator,
                           PromptBuilder promptBuilder,
                           AgentTraceStorePort traceStore) {
        this.llmPort = llmPort;
        this.vectorStorePort = vectorStorePort;
        this.conversationStorePort = conversationStorePort;
        this.toolRegistry = toolRegistry;
        this.heuristicRouter = heuristicRouter;
        this.toolCallParser = toolCallParser;
        this.toolCallValidator = toolCallValidator;
        this.promptBuilder = promptBuilder;
        this.traceStore = traceStore;
        log.info("✅ AgentAppService 初始化完成（DDD 应用层 - Ports Adapters 解耦 + 决策链路持久化）");
    }

    public ChatResponse chat(String question, String sessionId, boolean useRag) {
        log.info("🤖 Agent 处理问题: sessionId={}, useRag={}, question={}", sessionId, useRag, question);

        List<MessageDTO> messages = new ArrayList<>();
        messages.add(new MessageDTO(Role.SYSTEM, promptBuilder.buildAgentSystemPrompt()));

        if (sessionId != null && !sessionId.isBlank()) {
            List<Map.Entry<String, String>> history = conversationStorePort.getHistory(sessionId, MAX_HISTORY_PER_SESSION);
            for (Map.Entry<String, String> pair : history) {
                messages.add(new MessageDTO(Role.USER, pair.getKey()));
                messages.add(new MessageDTO(Role.ASSISTANT, pair.getValue()));
            }
        }

        List<ChatResponse.SourceDoc> sourceDocs = new ArrayList<>();
        String userContent = question;
        if (useRag) {
            List<DocumentChunk> docs = vectorStorePort.similaritySearch(question, 4);
            if (docs != null && !docs.isEmpty()) {
                StringBuilder ragContext = new StringBuilder();
                ragContext.append("## 知识库参考内容\n\n");
                for (int i = 0; i < docs.size(); i++) {
                    DocumentChunk doc = docs.get(i);
                    ragContext.append("**[").append(i + 1).append("]** ").append(doc.getText()).append("\n\n");
                    Object title = doc.getMetadata() == null ? null : doc.getMetadata().get("title");
                    Object fileName = doc.getMetadata() == null ? null : doc.getMetadata().get("fileName");
                    Double score = doc.getScore();
                    sourceDocs.add(new ChatResponse.SourceDoc(
                            title != null ? title.toString() : (fileName != null ? fileName.toString() : "文档" + (i + 1)),
                            fileName != null ? fileName.toString() : "",
                            score
                    ));
                }
                userContent = question + "\n\n" + ragContext;
            }
        }
        messages.add(new MessageDTO(Role.USER, userContent));

        String thisTurnUserQuestion = question;
        String finalAnswer = "";

        if (ENABLE_HEURISTIC_ROUTE) {
            ToolCall forcedTool = heuristicRouter.route(question);
            if (forcedTool != null) {
                if (HEURISTIC_STRATEGY == HeuristicStrategy.FORCE_EXEC) {
                    long toolStart = System.currentTimeMillis();
                    log.info("🎯[FORCE_EXEC] 启发式命中 → 强制调用工具 {}({})", forcedTool.name, forcedTool.arguments);
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
                } else {
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
                log.info("🟡 启发式未命中，交由 LLM 自主选择工具");
            }
        } else {
            log.info("⚪ 启发式路由已关闭，完全由 LLM 选择工具");
        }

        for (int i = 0; i < MAX_TOOL_CALLS; i++) {
            int round = i + 1;
            long llmStart = System.currentTimeMillis();
            String modelOutput = callLLM(messages);
            long llmMs = System.currentTimeMillis() - llmStart;
            traceStore.recordDecision(sessionId, round, "LLM_DECIDE",
                    null, null, null,
                    messages.size() + " messages", modelOutput,
                    false, null, llmMs,
                    modelOutput != null ? "OK" : "ERROR",
                    modelOutput == null ? "LLM 返回为空" : null);

            if (modelOutput == null || modelOutput.isBlank()) {
                finalAnswer = "❌ 模型返回内容为空";
                break;
            }
            List<ToolCall> toolCalls = toolCallParser.parse(modelOutput);
            if (toolCalls.isEmpty()) {
                log.info("✅ 第 {} 轮：没有工具调用，返回最终回答，长度={}", round, modelOutput.length());
                finalAnswer = cleanupAnswer(modelOutput);
                traceStore.recordDecision(sessionId, round, "FINAL",
                        null, null, null,
                        thisTurnUserQuestion, finalAnswer,
                        false, null, null, "OK", null);
                break;
            }

            messages.add(new MessageDTO(Role.ASSISTANT, modelOutput));

            StringBuilder toolResults = new StringBuilder();
            boolean hasValidTool = false;
            for (ToolCall tc : toolCalls) {
                long tStart = System.currentTimeMillis();
                String validationError = toolCallValidator.validate(tc.name, tc.arguments);
                String result;
                String tErr = null;
                if (validationError != null) {
                    log.warn("⚠️ 工具 {} 参数校验失败: {}", tc.name, validationError);
                    result = "❌ " + validationError;
                    tErr = validationError;
                } else {
                    log.info("🔧 第 {} 轮：执行工具 {}({})", round, tc.name, tc.arguments);
                    try {
                        result = toolRegistry.invoke(tc);
                        hasValidTool = true;
                    } catch (Exception e) {
                        result = "❌ " + e.getMessage();
                        tErr = e.getMessage();
                    }
                    log.info("🔧 工具 {} 执行结果长度={}", tc.name, result == null ? 0 : result.length());
                }
                long tMs = System.currentTimeMillis() - tStart;
                traceStore.recordDecision(sessionId, round, "TOOL_INVOKE",
                        tc.name, tc.arguments, result,
                        null, null, false, null,
                        tMs, tErr == null ? "OK" : "VALIDATE_ERROR", tErr);
                toolResults.append("\n### 工具调用结果：").append(tc.name).append("\n```\n").append(result == null ? "" : result).append("\n```\n");
            }

            String userFeedback = promptBuilder.buildToolResultsFeedback(toolResults.toString(), hasValidTool);
            messages.add(new MessageDTO(Role.USER, userFeedback));
        }

        if (finalAnswer.isEmpty()) {
            finalAnswer = "⚠️ 已达到最大工具调用次数，请尝试简化问题。";
            traceStore.recordDecision(sessionId, MAX_TOOL_CALLS, "FINAL",
                    null, null, null,
                    thisTurnUserQuestion, finalAnswer,
                    false, null, null, "MAX_RETRY", null);
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