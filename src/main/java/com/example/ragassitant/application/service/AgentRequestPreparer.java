package com.example.ragassitant.application.service;

import com.example.ragassitant.domain.model.DocumentChunk;
import com.example.ragassitant.domain.service.PromptInjectionDetector;
import com.example.ragassitant.domain.spi.AgentTraceStorePort;
import com.example.ragassitant.domain.spi.ConversationStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 请求的公共前置流程：载入历史 → 注入检测 → Query 改写 → RAG 检索。
 *
 * <p>【为什么抽出来】改造前这套流程在代码里存在<b>四份逐行复制</b>：
 * {@code PlanThenExecuteAppService} 和 {@code ReActAgentAppService} 各有阻塞版和流式版，
 * 每份都重复了同样的 5 个步骤，连 Prompt Injection 的拒绝话术都是同一段硬编码长字符串。
 * 两个服务因此各自膨胀到 900 行上下，而真正的风险是<b>只在其中一两份里改</b>——
 * 比如收紧了注入检测却漏掉流式路径，攻击就能从流式接口进来，而且没有任何测试会发现。
 *
 * <p>事件通过 {@link StreamChannel} 发出；阻塞式调用传
 * {@link StreamChannel#discarding()} 即可复用同一份实现而不产生任何事件。
 */
@Service
public class AgentRequestPreparer {

    private static final Logger log = LoggerFactory.getLogger(AgentRequestPreparer.class);

    /** 命中 Prompt Injection 时对用户的统一回复（原先在四处各硬编码了一份）。 */
    public static final String INJECTION_REJECTION =
            "抱歉，我检测到您的请求中包含试图绕过系统安全策略的指令。作为智能运维助手，我必须遵守预设的角色和职责范围，"
            + "无法执行此类操作。如果您有设备查询、能耗分析或运维知识库相关的问题，欢迎随时向我提问！";

    private final ConversationStorePort conversationStorePort;
    private final QueryRewriteService queryRewriteService;
    private final RagAppService ragAppService;
    private final PromptInjectionDetector injectionDetector;
    private final AgentTraceStorePort traceStore;

    public AgentRequestPreparer(ConversationStorePort conversationStorePort,
                                QueryRewriteService queryRewriteService,
                                RagAppService ragAppService,
                                PromptInjectionDetector injectionDetector,
                                AgentTraceStorePort traceStore) {
        this.conversationStorePort = conversationStorePort;
        this.queryRewriteService = queryRewriteService;
        this.ragAppService = ragAppService;
        this.injectionDetector = injectionDetector;
        this.traceStore = traceStore;
    }

    /** 前置流程的结论：是继续正常处理，还是已被安全策略拦下。 */
    public enum Outcome { PROCEED, BLOCKED }

    /**
     * 前置流程产出。{@code outcome == BLOCKED} 时调用方应立即用
     * {@link #blockedAnswer()} 收尾，其余字段无意义。
     */
    public record Prepared(Outcome outcome,
                           String blockedAnswer,
                           String attackType,
                           List<Map.Entry<String, String>> history,
                           String retrievalQuery,
                           boolean queryRewritten,
                           String rewrittenQuery,
                           List<DocumentChunk> docs,
                           List<Map<String, Object>> sources) {

        public boolean blocked() {
            return outcome == Outcome.BLOCKED;
        }
    }

    /**
     * @param modeTag     日志用的链路标识（plan / react），只影响日志文案
     * @param memorySize  载入多少轮历史
     * @param defaultTopK 调用方未指定 topK 时的默认值（Plan 用 4、ReAct 用 5，保持各自原值）
     * @param ch          事件通道；阻塞式调用传 {@link StreamChannel#discarding()}
     */
    public Prepared prepare(String question, String sessionId,
                            boolean useRag, boolean enableHybridSearch, Integer customTopK,
                            String modeTag, int memorySize, int defaultTopK,
                            StreamChannel ch) {

        List<Map.Entry<String, String>> history = conversationStorePort.getHistory(sessionId, memorySize);

        // ===== Prompt Injection 安全检测 =====
        if (injectionDetector.isPromptInjection(question)) {
            String attackType = injectionDetector.getAttackType(question);
            log.warn("🛡️ 拦截 Prompt Injection 攻击: sessionId={}, mode={}, attackType={}",
                    sessionId, modeTag, attackType);
            traceStore.recordDecision(sessionId, 1, "SECURITY_BLOCK",
                    null, null, null,
                    INJECTION_REJECTION, INJECTION_REJECTION,
                    false, "prompt_injection",
                    0L, "BLOCKED", attackType);
            saveTurn(sessionId, question, INJECTION_REJECTION);
            ch.emit("security_block", Map.of(
                    "type", "prompt_injection",
                    "attackType", attackType,
                    "answer", INJECTION_REJECTION));
            return new Prepared(Outcome.BLOCKED, INJECTION_REJECTION, attackType,
                    history, question, false, null, List.of(), List.of());
        }

        // ===== Query 改写：只用于检索，塞给下游的仍是用户原话 =====
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
                    log.info("📝[{}] Query 改写: '{}' → '{}'", modeTag, question, rewrittenQuery);
                    ch.emit("rewrite", Map.of("from", question, "to", rewrittenQuery, "ms", 0));
                }
            }
        }

        // ===== RAG 检索：整段包 try-catch，embedding 抖动不该让工具能力一起失效 =====
        List<DocumentChunk> docs = new ArrayList<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        if (useRag) {
            try {
                int topK = customTopK != null && customTopK > 0 ? customTopK : defaultTopK;
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
                    ch.emit("rag_ready", Map.of("count", docs.size()));
                }
            } catch (Exception e) {
                log.warn("⚠️[{}] RAG 检索失败，已跳过: {}", modeTag, e.getMessage());
            }
        }

        return new Prepared(Outcome.PROCEED, null, null,
                history, retrievalQuery, queryRewritten, rewrittenQuery, docs, sources);
    }

    /** 落一轮对话历史。 */
    public void saveTurn(String sessionId, String question, String answer) {
        if (sessionId == null || sessionId.isBlank()) return;
        conversationStorePort.saveUser(sessionId, question);
        conversationStorePort.saveAssistant(sessionId, answer);
    }
}
