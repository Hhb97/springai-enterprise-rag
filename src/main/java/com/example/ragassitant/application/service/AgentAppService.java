package com.example.ragassitant.application.service;

import com.example.ragassitant.domain.model.DocumentChunk;
import com.example.ragassitant.domain.model.ToolCall;
import com.example.ragassitant.domain.service.HeuristicRouter;
import com.example.ragassitant.domain.service.PromptBuilder;
import com.example.ragassitant.domain.service.TextToolCallParser;
import com.example.ragassitant.domain.spi.AgentTraceStorePort;
import com.example.ragassitant.domain.spi.ConversationStorePort;
import com.example.ragassitant.domain.spi.LlmPort;
import com.example.ragassitant.domain.spi.LlmPort.MessageDTO;
import com.example.ragassitant.domain.spi.LlmPort.MessageDTO.Role;
import com.example.ragassitant.domain.spi.ToolRegistryPort;
import com.example.ragassitant.infrastructure.agent.ToolCatalog;
import com.example.ragassitant.infrastructure.config.AgentProperties;
import com.example.ragassitant.infrastructure.config.AgentProperties.Heuristic.Strategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Agent 编排。一次请求走且只走下面三条路径之一：
 *
 * <ol>
 *   <li>{@link #handleSmallTalk} —— 闲聊/问候。不改写、不路由、不检索、不调工具；</li>
 *   <li>{@link #handleForcedTool} —— 启发式命中且策略为 FORCE_EXEC。直接执行工具并跳过向量检索，
 *       省掉一次「让 LLM 决定调什么工具」的往返；</li>
 *   <li>{@link #handleAutoAgent} —— 其余情况。RAG 检索 + Spring AI 2.0 原生自动 Tool Calling。</li>
 * </ol>
 *
 * <p>三条路径都经由 {@link #finish} 收口：FINAL trace、写会话历史、构造响应只有一处实现。
 * 改造前这三段挤在一个约 200 行的 {@code chat()} 里，各自重复「记 trace + 存历史 + return」，
 * 已经出现过不一致（一条先写历史再记 trace、另一条相反）。
 */
@Service
public class AgentAppService {

    private static final Logger log = LoggerFactory.getLogger(AgentAppService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** LLM 不可用时的问候兜底，保证闲聊短路不会返回空串。 */
    private static final String SMALL_TALK_FALLBACK =
            "您好！我是智能运维助手，可以帮您查询设备清单与详情、分析能耗数据，也能基于知识库回答业务问题。请问需要什么帮助？";

    private final LlmPort llmPort;
    private final ConversationStorePort conversationStorePort;
    private final ToolRegistryPort toolRegistry;
    private final HeuristicRouter heuristicRouter;
    private final PromptBuilder promptBuilder;
    private final AgentTraceStorePort traceStore;
    private final RagAppService ragAppService;
    private final QueryRewriteService queryRewriteService;
    private final ShadowToolRouter shadowToolRouter;
    private final TextToolCallParser textToolCallParser;
    /**
     * 工具目录。注册给 LLM 时用 {@link ToolCatalog#tracingToolCallbacks()} 而不是裸的 @Tool Bean ——
     * 那层装饰器会在「LLM 真正通过原生 tool_calls 通道调用工具」时打日志并写 trace，
     * 是判断「模型到底有没有自己调工具」的确定性依据（裸 Bean 的日志两条路径都会打，分不出来）。
     */
    private final ToolCatalog toolCatalog;
    private final AgentProperties props;

    /**
     * 纯知识库模式：{@code true} 时禁用启发式强制路由（FORCE_EXEC）与自动 Tool Calling，
     * 所有问题只走 RAG，检索不到就明确回答「知识库暂无相关信息」。
     *
     * <p>默认 {@code false} —— 打开会让设备清单/能耗查询能力整体失效，必须是显式选择。
     */
    private final boolean pureKnowledgeMode;

    public AgentAppService(LlmPort llmPort,
                           ConversationStorePort conversationStorePort,
                           ToolRegistryPort toolRegistry,
                           HeuristicRouter heuristicRouter,
                           PromptBuilder promptBuilder,
                           AgentTraceStorePort traceStore,
                           RagAppService ragAppService,
                           QueryRewriteService queryRewriteService,
                           ShadowToolRouter shadowToolRouter,
                           TextToolCallParser textToolCallParser,
                           ToolCatalog toolCatalog,
                           AgentProperties props,
                           @Value("${rag.pure-knowledge-mode:false}") boolean pureKnowledgeMode) {
        this.llmPort = llmPort;
        this.conversationStorePort = conversationStorePort;
        this.toolRegistry = toolRegistry;
        this.heuristicRouter = heuristicRouter;
        this.promptBuilder = promptBuilder;
        this.traceStore = traceStore;
        this.ragAppService = ragAppService;
        this.queryRewriteService = queryRewriteService;
        this.shadowToolRouter = shadowToolRouter;
        this.textToolCallParser = textToolCallParser;
        this.toolCatalog = toolCatalog;
        this.props = props;
        this.pureKnowledgeMode = pureKnowledgeMode;
        log.info("✅ AgentAppService 初始化完成（Spring AI 2.0 原生自动 Tool Calling），纯知识库模式={}", pureKnowledgeMode);
    }

    public ChatResponse chat(String question, String sessionId, boolean useRag, boolean enableHybridSearch, Integer customTopK) {
        log.info("🤖 Agent 处理问题: sessionId={}, useRag={}, hybrid={}, topK={}, question={}",
                sessionId, useRag, enableHybridSearch, customTopK, question);

        List<Map.Entry<String, String>> history = loadHistory(sessionId);

        // 闲聊短路必须在「改写 / 路由 / 检索」三者之前。
        // 【线上事故】用户第一句问「列出所有设备」（命中工具、跳过检索、回答正确），
        // 第二句只说了「你好」→ 未命中工具 → 进入 RAG → 检索无相似度下限，必然召回电能质量 FAQ
        // → FAQ 以「知识库参考内容」的名义拼进提示词 → 模型回「我已经阅读了知识库中的内容…」
        // 并把 FAQ 表格整段总结了一遍。用户看到的是一次没人问过的知识库问答。
        //
        // 这三条链路对一句问候都是有害的，所以一起跳过：
        //   改写：「你好」只有 2 字必然过长度门禁，可能被补全成设备查询（已在门禁层另行拦截）
        //   路由：问候不该触发任何工具
        //   检索：问候和任何文档的相似度都很低，召回结果只会污染上下文
        if (HeuristicRouter.isSmallTalk(question)) {
            return handleSmallTalk(question, sessionId);
        }

        Routing routing = resolveRouting(question, history, sessionId);

        // 影子路由：异步问一次「LLM 会选哪个工具」，和启发式的选择记进 trace 做比对。
        // 不参与本次响应、不阻塞、异常不外抛 —— 目的是为「把正则路由换成 LLM 自主调用」攒真实数据。
        // 放在这里是因为它和下面的主流程并发跑，用户侧感知不到额外耗时。
        shadowToolRouter.compareAsync(question, history, sessionId, routing.forcedTool());

        if (routing.forcedTool() != null && props.getHeuristic().getStrategy() == Strategy.FORCE_EXEC) {
            return handleForcedTool(question, sessionId, history, routing.forcedTool(), "FORCE_EXEC", List.of());
        }

        return handleAutoAgent(question, sessionId, history, routing, useRag, enableHybridSearch, customTopK);
    }

    // ==================== 路径一：闲聊 ====================

    private ChatResponse handleSmallTalk(String question, String sessionId) {
        log.info("💬 闲聊/问候短路：跳过 Query 改写、启发式路由、向量检索、Tool Calling → question={}", question);
        // 不带历史：上一轮的设备表格留在上下文里，模型很容易顺手把它再总结一遍。
        // 问候不需要任何上下文，给最干净的输入。
        List<MessageDTO> messages = List.of(
                new MessageDTO(Role.SYSTEM, promptBuilder.buildSmallTalkSystemPrompt()),
                new MessageDTO(Role.USER, question));

        long start = System.currentTimeMillis();
        String reply = callLLM(messages);
        long elapsed = System.currentTimeMillis() - start;

        String answer = (reply == null || reply.isBlank()) ? SMALL_TALK_FALLBACK : cleanupAnswer(reply);
        if (answer.isBlank()) answer = SMALL_TALK_FALLBACK;

        traceStore.recordDecision(sessionId, 1, "SMALL_TALK",
                null, null, null,
                question, answer,
                false, "SMALL_TALK_SHORT_CIRCUIT",
                elapsed, "OK", null);
        return finish(sessionId, question, answer, List.of(), "SMALL_TALK_SHORT_CIRCUIT", false, null, "OK", null);
    }

    // ==================== 路径二：执行一个已经确定的工具调用 ====================

    /**
     * 执行一个已确定的工具调用，并让 LLM 把结果整理成回答。
     *
     * <p>两个入口共用这条实现：
     * <ul>
     *   <li>{@code FORCE_EXEC} —— 启发式路由抢先命中；</li>
     *   <li>{@code TEXT_TOOL_CALL_FALLBACK} —— 模型把工具调用写成了文本（见
     *       {@link #handleAutoAgent} 里的兜底），此时调用意图已经明确，只是没走原生通道。</li>
     * </ul>
     *
     * @param strategy 写进 trace 的策略标签，用于区分两个入口、统计兜底触发频率
     */
    private ChatResponse handleForcedTool(String question, String sessionId,
                                          List<Map.Entry<String, String>> history, ToolCall forcedTool,
                                          String strategy, List<ChatResponse.SourceDoc> sources) {
        long toolStart = System.currentTimeMillis();
        log.info("🎯[{}] 执行工具 {}({})", strategy, forcedTool.name, forcedTool.arguments);

        String forcedResult;
        String toolErr = null;
        try {
            forcedResult = toolRegistry.invoke(forcedTool);
        } catch (Exception e) {
            forcedResult = null;
            toolErr = e.getMessage();
        }
        long toolMs = System.currentTimeMillis() - toolStart;
        log.info("🎯[{}] 工具 {} 执行完毕，结果长度={}", strategy, forcedTool.name, forcedResult == null ? 0 : forcedResult.length());
        traceStore.recordDecision(sessionId, 1, "HEURISTIC_TOOL",
                forcedTool.name, forcedTool.arguments, forcedResult,
                null, null, true, strategy,
                toolMs, toolErr == null ? "OK" : "ERROR", toolErr);

        List<MessageDTO> messages = buildBaseMessages(history);
        messages.add(new MessageDTO(Role.USER, question));
        messages.add(new MessageDTO(Role.ASSISTANT,
                "[{\"name\":\"" + forcedTool.name + "\",\"arguments\":" + toJson(forcedTool.arguments) + "}]"));
        messages.add(new MessageDTO(Role.USER, promptBuilder.buildForcedToolExecutionFeedback(forcedResult)));

        long llmStart = System.currentTimeMillis();
        String summary = callLLM(messages);
        long llmMs = System.currentTimeMillis() - llmStart;
        // LLM 总结失败时直接把工具原始输出返回给用户 —— 工具产出的已经是排版好的 Markdown，
        // 比一句「处理失败」有用得多。
        String answer = (summary != null && !summary.isBlank())
                ? cleanupAnswer(summary)
                : (forcedResult == null ? "" : forcedResult);

        traceStore.recordDecision(sessionId, 1, "LLM_SUMMARY",
                null, null, null,
                messages.size() + " messages", summary,
                true, strategy,
                llmMs, "OK", null);
        log.info("✅[{}] 流程结束：最终回答长度={}", strategy, answer.length());

        return finish(sessionId, question, answer, sources, strategy, true, null, "OK", null);
    }

    // ==================== 路径三：RAG + 原生自动 Tool Calling ====================

    private ChatResponse handleAutoAgent(String question, String sessionId,
                                         List<Map.Entry<String, String>> history, Routing routing,
                                         boolean useRag, boolean enableHybridSearch, Integer customTopK) {
        List<MessageDTO> messages = buildBaseMessages(history);
        List<ChatResponse.SourceDoc> sourceDocs = new ArrayList<>();

        // 检索用改写后的 query，但塞进 messages 的始终是用户原话 ——
        // 改写产物一旦回流到对话上下文，后续轮次会在改写结果上继续改写，误差逐轮累积。
        String userContent = question;
        if (useRag) {
            String ragContext = retrieveContext(routing.retrievalQuery(), enableHybridSearch, customTopK, sourceDocs);
            if (ragContext != null) userContent = question + "\n\n" + ragContext;
        }
        messages.add(new MessageDTO(Role.USER, userContent));

        boolean routeEnabled = isRouteEnabled();
        if (routeEnabled && routing.forcedTool() != null) {
            // 走到这里说明策略是 RECOMMEND_LLM（FORCE_EXEC 已在 chat() 里分流走了）
            appendRecommendHint(messages, routing.forcedTool());
        } else if (routeEnabled) {
            log.info("🟡 启发式未命中，交由 Spring AI 2.0 ChatClient 自动 Tool Calling");
        } else if (pureKnowledgeMode) {
            log.info("📚 纯知识库模式：已禁用启发式工具路由与自动 Tool Calling，仅依据检索结果回答");
        } else {
            log.info("⚪ 启发式路由已关闭，完全由 Spring AI 2.0 ChatClient 自动 Tool Calling");
        }

        long agentStart = System.currentTimeMillis();
        traceStore.recordDecision(sessionId, 1, "LLM_AGENT_AUTO_LOOP_START",
                null, null, null,
                messages.size() + " messages",
                pureKnowledgeMode ? "纯知识库模式：不注册工具" : "ChatClient.toolCallbacks(" + toolCatalog.definitions().size() + " tools)",
                false, pureKnowledgeMode ? "PURE_KNOWLEDGE" : "SPRING_AI_2_AUTO",
                null, "OK", null);

        // 纯知识库模式走不带工具的普通对话：ChatClient.tools() 传空数组的行为依版本而异，
        // 与其赌它容忍空工具集，不如明确走另一条不涉及工具的接口。
        String modelOutput = pureKnowledgeMode
                ? llmPort.chatWithRawMessages(messages)
                : llmPort.chatWithAutoToolCalling(messages, props.getMaxToolCalls(),
                        List.copyOf(toolCatalog.tracingToolCallbacks()));
        long agentMs = System.currentTimeMillis() - agentStart;

        String strategy = pureKnowledgeMode ? "PURE_KNOWLEDGE" : "SPRING_AI_2_AUTO";
        if (modelOutput == null || modelOutput.isBlank()) {
            return finish(sessionId, question, "❌ Agent 处理失败（LLM 返回为空或调用异常）", sourceDocs,
                    strategy, false, agentMs, "ERROR", "model_output_blank");
        }

        // 【弱模型兜底】模型没走原生 tool_calls 通道，而是把调用当文本打印出来。
        // 实测 qwen2.5:1.5b 就是这样：问「列出所有设备」返回 52 字符的
        // [{"name":"list_devices","arguments":{"keyword":""}}]，框架看不到 tool_calls，
        // 于是把它当最终答案；再被 cleanupAnswer 的历史正则洗成 3 个字符「[}]」交给用户。
        // 意图其实是明确的，这里识别出来真正执行掉，而不是把垃圾字符展示出去。
        if (!pureKnowledgeMode) {
            List<ToolCall> textCalls = textToolCallParser.parse(modelOutput);
            if (!textCalls.isEmpty()) {
                ToolCall call = textCalls.get(0);
                // 措辞刻意不指向「模型不支持 tool calling」：线上第一次遇到时就是这么误判的，
                // 而实际原因是系统提示词里有一条「只输出 JSON 数组」的遗留指令，模型只是照做。
                // 按发生频率从高到低列出真正需要排查的方向。
                log.warn("🩹 模型未走原生 tool_calls 通道，把调用写成了文本，已识别并补执行: {}({})。"
                         + "排查顺序：① 系统提示词里是否残留「输出 JSON」类指令（最常见）"
                         + " ② 该模型/端点是否真的支持 function calling"
                         + " ③ 推理模型是否把调用混进了思维链。模型名见上一行日志。",
                        call.name, call.arguments);
                traceStore.recordDecision(sessionId, 1, "TEXT_TOOL_CALL_DETECTED",
                        call.name, call.arguments, null,
                        question, modelOutput.substring(0, Math.min(500, modelOutput.length())),
                        false, "TEXT_TOOL_CALL_FALLBACK",
                        agentMs, "WARN", "model_emitted_tool_call_as_text");
                return handleForcedTool(question, sessionId, history, call,
                        "TEXT_TOOL_CALL_FALLBACK", sourceDocs);
            }
        }

        String answer = cleanupAnswer(modelOutput);
        // 清洗后只剩标点/括号也算无效。原来只判 isEmpty()，而实测残渣是「[}]」这 3 个字符，
        // 非空却毫无意义，于是被原样展示给用户。
        if (isMeaningless(answer)) {
            log.warn("⚠️ 模型输出被清洗为无意义残渣（'{}'），原始输出前 200 字: {}", answer,
                    modelOutput.substring(0, Math.min(200, modelOutput.length())));
            return finish(sessionId, question, "⚠️ 未能生成有效回答，请尝试简化问题。", sourceDocs,
                    strategy, false, agentMs, "ERROR", "answer_emptied_by_cleanup");
        }
        log.info("✅ 自动 Agent 结束：总耗时 {}ms，最终回答长度={}", agentMs, answer.length());
        return finish(sessionId, question, answer, sourceDocs, strategy, false, agentMs, "OK", null);
    }

    /**
     * 检索并拼装知识库上下文，返回 null 表示「没有可用上下文」。
     *
     * <p>整段包 try-catch：embedding 服务抖动不该让 Agent 的工具能力一起失效。
     */
    private String retrieveContext(String retrievalQuery, boolean enableHybridSearch, Integer customTopK,
                                   List<ChatResponse.SourceDoc> sourceDocs) {
        try {
            int effectiveTopK = customTopK != null && customTopK > 0 ? customTopK : 4;
            List<DocumentChunk> docs = ragAppService.retrieveDocuments(retrievalQuery, enableHybridSearch, effectiveTopK);

            // 【幻觉防护】丢弃 min-keep-chunks 的保底块。
            // 保底块是「相似度全部低于阈值时最不差的那一块」，本质仍是无关文档；
            // 纯 RAG 链路靠低置信度提示词兜着它，Agent 链路没这个必要 ——
            // 这里还有工具调用和常识问答能力，宁可上下文为空也不要注入无关文档。
            if (docs != null && RagAppService.isAllBelowThreshold(docs)) {
                log.info("🚧 召回 {} 块全部低于相似度阈值，不注入知识库上下文", docs.size());
                return null;
            }
            if (docs == null || docs.isEmpty()) return null;

            StringBuilder ragContext = new StringBuilder(promptBuilder.buildRetrievedContextHeader());
            for (int i = 0; i < docs.size(); i++) {
                DocumentChunk doc = docs.get(i);
                ragContext.append("**[").append(i + 1).append("]** ").append(doc.getText()).append("\n\n");
                Map<String, Object> meta = doc.getMetadata();
                Object title = meta == null ? null : meta.get("title");
                Object fileName = meta == null ? null : meta.get("fileName");
                Object hybridScore = meta == null ? null : meta.get("hybridScore");
                Double score = hybridScore instanceof Number n ? n.doubleValue() : doc.getScore();
                sourceDocs.add(new ChatResponse.SourceDoc(
                        title != null ? title.toString() : (fileName != null ? fileName.toString() : "文档" + (i + 1)),
                        fileName != null ? fileName.toString() : "",
                        score));
            }
            return ragContext.toString();
        } catch (Exception e) {
            log.warn("⚠️ RAG 检索失败，已跳过，不影响 Agent 工具调用。原因: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 把启发式的选择作为「建议」附到用户消息后面，由 LLM 拍板。
     *
     * <p>提示词本体见 {@link PromptBuilder#buildToolRecommendationHint} —— 那里记录了它原先
     * 为什么是坏的（要求模型输出文本 JSON，而原生 Tool Calling 只执行结构化 tool_calls）。
     */
    private void appendRecommendHint(List<MessageDTO> messages, ToolCall forcedTool) {
        log.info("💡[RECOMMEND_LLM] 启发式命中 → 推荐工具 {}({})（最终由 LLM 决定）", forcedTool.name, forcedTool.arguments);
        String hint = promptBuilder.buildToolRecommendationHint(forcedTool.name, toJson(forcedTool.arguments));
        MessageDTO lastUserMsg = messages.remove(messages.size() - 1);
        messages.add(new MessageDTO(Role.USER, lastUserMsg.content + hint));
    }

    // ==================== 路由决策 ====================

    /** 本轮路由结论：命中的工具（可能为 null）+ 用于检索的 query（可能是改写产物）。 */
    private record Routing(ToolCall forcedTool, String retrievalQuery) {}

    /**
     * 两段式路由：
     * <ol>
     *   <li>原问题直接 route（零成本，覆盖绝大多数场景）；</li>
     *   <li>上下文消解后再 route —— 优先用 LLM 改写；改写被关闭时才退回历史拼接启发式。</li>
     * </ol>
     *
     * <p>【为什么不是「拼接优先、改写兜底」】线上实测：上一轮「mv-005的呢」查能耗，
     * 下一轮问「如何创建用户呢？」，拼接启发式继承了 mv-005，把知识库问题劫持成能耗查询，
     * 并且 FORCE_EXEC 会跳过向量检索 —— 用户拿到的是一段没有知识库依据的编造回答。
     * 拼接本质是在用字符串匹配猜语义，而这正是 LLM 改写该做的事，所以改写优先、且其判断为准：
     * 改写认为「这是新话题、原样输出」时，不再让拼接推翻它。
     */
    private Routing resolveRouting(String question, List<Map.Entry<String, String>> history, String sessionId) {
        boolean routeEnabled = isRouteEnabled();
        ToolCall forcedTool = routeEnabled ? heuristicRouter.route(question) : null;
        String retrievalQuery = question;

        // 纯知识库模式下不做工具路由，但 Query 改写仍然需要（多轮追问的指代消解对检索同样重要）
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
        return new Routing(forcedTool, retrievalQuery);
    }

    private boolean isRouteEnabled() {
        return props.getHeuristic().isEnabled() && !pureKnowledgeMode;
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

    // ==================== 公共环节 ====================

    private List<Map.Entry<String, String>> loadHistory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Collections.emptyList();
        return conversationStorePort.getHistory(sessionId, props.getMemorySize());
    }

    /** 系统提示词 + 历史对话，三条路径共用的消息前缀。 */
    private List<MessageDTO> buildBaseMessages(List<Map.Entry<String, String>> history) {
        List<MessageDTO> messages = new ArrayList<>();
        messages.add(new MessageDTO(Role.SYSTEM, promptBuilder.buildAgentSystemPrompt()));
        for (Map.Entry<String, String> pair : history) {
            messages.add(new MessageDTO(Role.USER, pair.getKey()));
            messages.add(new MessageDTO(Role.ASSISTANT, pair.getValue()));
        }
        return messages;
    }

    /** 统一出口：记 FINAL trace → 落会话历史 → 构造响应。顺序固定，避免各路径各写一套。 */
    private ChatResponse finish(String sessionId, String question, String answer,
                                List<ChatResponse.SourceDoc> sources, String strategy, boolean heuristicHit,
                                Long durationMs, String status, String error) {
        traceStore.recordDecision(sessionId, 1, "FINAL",
                null, null, null,
                question, answer,
                heuristicHit, strategy,
                durationMs, status, error);
        if (sessionId != null && !sessionId.isBlank()) {
            conversationStorePort.saveUser(sessionId, question);
            conversationStorePort.saveAssistant(sessionId, answer);
        }
        return new ChatResponse(answer, sources);
    }

    private String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String callLLM(List<MessageDTO> messages) {
        try {
            return llmPort.chatWithRawMessages(messages);
        } catch (Exception e) {
            log.error("❌ LLM 调用失败", e);
            return null;
        }
    }

    /** 只剩空白、括号、引号、冒号这类符号 → 是清洗残渣而不是回答。 */
    static boolean isMeaningless(String answer) {
        if (answer == null) return true;
        return answer.replaceAll("[\\s\\p{Punct}\\p{IsPunctuation}]", "").isEmpty();
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
