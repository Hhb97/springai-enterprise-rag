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
import org.springframework.beans.factory.annotation.Value;
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

    /** LLM 不可用时的问候兜底，保证闲聊短路不会返回空串。 */
    private static final String SMALL_TALK_FALLBACK =
            "您好！我是智能运维助手，可以帮您查询设备清单与详情、分析能耗数据，也能基于知识库回答业务问题。请问需要什么帮助？";

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
    private final QueryRewriteService queryRewriteService;
    // Spring AI 2.0 官方 @Tool 注解的 Bean，ChatClient.tools(deviceTools) 自动识别 6 个工具
    private final DeviceTools deviceTools;

    /**
     * 纯知识库模式：{@code true} 时禁用启发式强制路由（FORCE_EXEC）与自动 Tool Calling，
     * 所有问题只走 RAG，检索不到就明确回答「知识库暂无相关信息」。
     *
     * <p>默认 {@code false} —— 打开会让设备清单/能耗查询能力整体失效，必须是显式选择。
     */
    private final boolean pureKnowledgeMode;

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
                           QueryRewriteService queryRewriteService,
                           DeviceTools deviceTools,
                           @Value("${rag.pure-knowledge-mode:false}") boolean pureKnowledgeMode) {
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
        this.queryRewriteService = queryRewriteService;
        this.deviceTools = deviceTools;
        this.pureKnowledgeMode = pureKnowledgeMode;
        log.info("✅ AgentAppService 初始化完成（Spring AI 2.0 官方自动 Tool Calling Agent，不再手写 for 循环），纯知识库模式={}",
                pureKnowledgeMode);
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

        // ===== 闲聊短路：必须在「改写 / 路由 / 检索」三者之前 =====
        // 【线上事故】用户第一句问「列出所有设备」（命中工具、跳过检索、回答正确），
        // 第二句只说了「你好」→ 未命中工具 → 进入 RAG → 检索无相似度下限，必然召回电能质量 FAQ
        // → FAQ 以「## 知识库参考内容」的名义拼进提示词 → 模型回「我已经阅读了知识库中的内容…」
        // 并把 FAQ 表格整段总结了一遍。用户看到的是一次没人问过的知识库问答。
        //
        // 这三条链路对一句问候都是有害的，所以一起跳过：
        //   改写：「你好」只有 2 字必然过长度门禁，可能被补全成设备查询（已在门禁层另行拦截）
        //   路由：问候不该触发任何工具
        //   检索：问候和任何文档的相似度都很低，召回结果只会污染上下文
        if (HeuristicRouter.isSmallTalk(question)) {
            log.info("💬 闲聊/问候短路（Agent 链路）：跳过 Query 改写、启发式路由、向量检索、Tool Calling → question={}", question);
            // 不带历史：上一轮的设备表格留在上下文里，模型很容易顺手把它再总结一遍。
            // 问候不需要任何上下文，给最干净的输入。
            List<MessageDTO> smallTalkMessages = List.of(
                    new MessageDTO(Role.SYSTEM, promptBuilder.buildSmallTalkSystemPrompt()),
                    new MessageDTO(Role.USER, question));

            long smallTalkStart = System.currentTimeMillis();
            String reply = callLLM(smallTalkMessages);
            long smallTalkMs = System.currentTimeMillis() - smallTalkStart;
            String smallTalkAnswer = (reply == null || reply.isBlank()) ? SMALL_TALK_FALLBACK : cleanupAnswer(reply);
            if (smallTalkAnswer.isBlank()) smallTalkAnswer = SMALL_TALK_FALLBACK;

            traceStore.recordDecision(sessionId, 1, "SMALL_TALK",
                    null, null, null,
                    question, smallTalkAnswer,
                    false, "SMALL_TALK_SHORT_CIRCUIT",
                    smallTalkMs, "OK", null);
            if (sessionId != null && !sessionId.isBlank()) {
                conversationStorePort.saveUser(sessionId, question);
                conversationStorePort.saveAssistant(sessionId, smallTalkAnswer);
            }
            return new ChatResponse(smallTalkAnswer, List.of());
        }

        // ===== 两段式路由 =====
        //   ① 原问题直接 route（零成本，覆盖绝大多数场景）
        //   ② 上下文消解后再 route —— 优先用 LLM 改写；改写被关闭时才退回历史拼接启发式
        //
        // 【为什么不是「拼接优先、改写兜底」】线上实测：上一轮「mv-005的呢」查能耗，
        // 下一轮问「如何创建用户呢？」，拼接启发式继承了 mv-005，把知识库问题劫持成能耗查询，
        // 并且 FORCE_EXEC 会跳过向量检索 —— 用户拿到的是一段没有知识库依据的编造回答。
        // 拼接本质是在用字符串匹配猜语义，而这正是 LLM 改写该做的事，所以改写优先、且其判断为准：
        // 改写认为「这是新话题、原样输出」时，不再让拼接推翻它。
        ToolCall forcedTool = null;
        String retrievalQuery = question;
        // 纯知识库模式下不做工具路由，但 Query 改写仍然需要（多轮追问的指代消解对检索同样重要）
        boolean routeEnabled = ENABLE_HEURISTIC_ROUTE && !pureKnowledgeMode;
        if (routeEnabled || (pureKnowledgeMode && !history.isEmpty())) {
            if (routeEnabled) forcedTool = heuristicRouter.route(question);

            if (forcedTool == null && !history.isEmpty()) {
                if (queryRewriteService.isEnabled()) {
                    retrievalQuery = queryRewriteService.rewrite(question, history, sessionId).get(0);
                    if (routeEnabled && !retrievalQuery.equals(question)) {
                        ToolCall rewrittenTool = heuristicRouter.route(retrievalQuery);
                        if (rewrittenTool != null) {
                            forcedTool = rewrittenTool;
                            log.info("🧠[改写命中] '{}' → '{}' → 工具 {}({})",
                                    question, retrievalQuery, forcedTool.name, forcedTool.arguments);
                        }
                    }
                } else if (routeEnabled) {
                    forcedTool = routeByHistoryConcat(question, history);
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
        // 注意：检索用改写后的 query，但下面塞进 messages 的始终是用户原话 ——
        // 改写产物一旦回流到对话上下文，后续轮次会在改写结果上继续改写，误差逐轮累积。
        String userContent = question;
        if (useRag) {
            try {
                int effectiveTopK = customTopK != null && customTopK > 0 ? customTopK : 4;
                List<DocumentChunk> docs = ragAppService.retrieveDocuments(retrievalQuery, enableHybridSearch, effectiveTopK);
                // 【幻觉防护】丢弃 min-keep-chunks 的保底块。
                // 保底块是「相似度全部低于阈值时最不差的那一块」，本质仍是无关文档；
                // 纯 RAG 链路靠低置信度提示词兜着它，Agent 链路没这个必要 ——
                // 这里还有工具调用和常识问答能力，宁可上下文为空也不要注入无关文档。
                if (docs != null && RagAppService.isAllBelowThreshold(docs)) {
                    log.info("🚧 召回 {} 块全部低于相似度阈值，Agent 链路不注入知识库上下文", docs.size());
                    docs = List.of();
                }
                if (docs != null && !docs.isEmpty()) {
                    StringBuilder ragContext = new StringBuilder();
                    ragContext.append(promptBuilder.buildRetrievedContextHeader());
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
        if (routeEnabled) {
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
        } else if (pureKnowledgeMode) {
            log.info("📚 纯知识库模式：已禁用启发式工具路由与自动 Tool Calling，仅依据检索结果回答");
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
                messages.size() + " messages",
                pureKnowledgeMode ? "纯知识库模式：不注册工具" : "Spring AI 2.0 ChatClient.tools(DeviceTools)",
                false, pureKnowledgeMode ? "PURE_KNOWLEDGE" : "SPRING_AI_2_AUTO",
                null, "OK", null);

        // 纯知识库模式走不带工具的普通对话：ChatClient.tools() 传空数组的行为依版本而异，
        // 与其赌它容忍空工具集，不如明确走另一条不涉及工具的接口。
        String modelOutput = pureKnowledgeMode
                ? llmPort.chatWithRawMessages(messages)
                : llmPort.chatWithAutoToolCalling(messages, MAX_TOOL_CALLS, List.of(deviceTools));
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

    /**
     * 零成本的历史拼接补全：把当前问题和上一条用户问题拼起来重新过启发式，
     * 借此从历史继承 SN / 天数等参数。
     *
     * <p><b>仅在 Query 改写被关闭时作为兜底</b>。它是用字符串匹配猜语义，
     * 已知会误伤和历史无关的新问题（见 {@link HeuristicRouter#isLikelySameToolTopic} 的注释），
     * 门禁收紧后误伤面变小，但本质缺陷仍在。
     */
    private ToolCall routeByHistoryConcat(String question, List<Map.Entry<String, String>> history) {
        String lastUserQ = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            lastUserQ = history.get(i).getKey();
            if (lastUserQ != null && !lastUserQ.isBlank()) break;
        }
        if (lastUserQ == null || lastUserQ.isBlank()) return null;

        if (!heuristicRouter.isLikelySameToolTopic(question, lastUserQ)) {
            log.info("🚫 上下文补全被跳过：检测到换话题 → current='{}' 不在设备/能耗话题，不继承 lastUser='{}' 的 SN/工具",
                    question, lastUserQ);
            return null;
        }

        ToolCall mergedT = heuristicRouter.route(question + " " + lastUserQ);
        ToolCall forcedTool = mergedT != null ? mergedT : heuristicRouter.route(lastUserQ);
        if (forcedTool != null) {
            log.info("🧠 上下文补全触发（改写已关闭）：current='{}' 本身未命中，合并 lastUser='{}' → 命中工具 {}({})",
                    question, lastUserQ, forcedTool.name, forcedTool.arguments);
        }
        return forcedTool;
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