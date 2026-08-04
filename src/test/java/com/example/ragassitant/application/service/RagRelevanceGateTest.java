package com.example.ragassitant.application.service;

import com.example.ragassitant.domain.model.DocumentChunk;
import com.example.ragassitant.domain.service.PromptBuilder;
import com.example.ragassitant.domain.spi.AgentTraceStorePort;
import com.example.ragassitant.domain.spi.ConversationStorePort;
import com.example.ragassitant.domain.spi.LlmPort;
import com.example.ragassitant.domain.spi.VectorStorePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 检索相关性闸门的回归测试。
 *
 * <p>背景（线上事故）：用户第一句「列出所有设备」命中工具、跳过检索，回答正确；
 * 第二句只说了「你好」，未命中工具 → 进入 RAG → 检索没有任何相似度下限，
 * 必然召回电能质量 FAQ → FAQ 被注入提示词 → 模型回「我已经阅读了知识库中的内容…」
 * 并把 FAQ 表格整段总结了一遍。
 *
 * <p>根因有两层，这里都要盯住：
 * <ol>
 *   <li>{@code rag.similarity-threshold} 只写在 application.yml 里，没有任何代码读取；</li>
 *   <li>{@code VectorStoreDocumentRetriever} 的默认阈值是 0.0 = 有文档就一定返回。</li>
 * </ol>
 */
class RagRelevanceGateTest {

    // ==================== 桩 ====================

    /** 记录收到的 SearchRequest，并返回预设分数的文档（故意不自己做阈值过滤，用来验证服务层的兜底过滤） */
    static class CapturingVectorStore implements VectorStore {
        final List<SearchRequest> requests = new ArrayList<>();
        List<Document> results = List.of();

        @Override
        public void add(List<Document> documents) {
        }

        @Override
        public void delete(List<String> idList) {
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            requests.add(request);
            return results;
        }
    }

    static class StubVectorStorePort implements VectorStorePort {
        int similaritySearchCalls = 0;
        List<DocumentChunk> docs = List.of();

        @Override
        public void add(List<DocumentChunk> chunks) {
        }

        @Override
        public List<DocumentChunk> similaritySearch(String query, int topK) {
            similaritySearchCalls++;
            return docs;
        }

        @Override
        public List<DocumentChunk> listAll(int limit) {
            return docs;
        }

        @Override
        public void delete(String chunkId) {
        }

        @Override
        public int deleteByDocumentId(String documentId) {
            return 0;
        }

        @Override
        public int deleteAll() {
            return 0;
        }

        @Override
        public void persist() {
        }
    }

    /** 记录 systemPrompt，便于断言用了哪一套提示词 */
    static class RecordingLlm implements LlmPort {
        final List<String> systemPrompts = new ArrayList<>();
        int calls = 0;

        @Override
        public String chat(List<String> systemPrompts, List<String> hu, List<String> ha, String cu) {
            calls++;
            this.systemPrompts.addAll(systemPrompts);
            return "模型回答";
        }

        @Override
        public String chatWithRawMessages(List<MessageDTO> messages) {
            calls++;
            return "模型回答";
        }

        @Override
        public String chatWithAutoToolCalling(List<MessageDTO> m, int max, List<Object> tools) {
            throw new UnsupportedOperationException();
        }
    }

    static class NoopTraceStore implements AgentTraceStorePort {
        @Override
        public void recordDecision(String sessionId, int roundNo, String stage,
                                   String toolName, Map<String, Object> toolArguments, String toolResult,
                                   String llmInput, String llmOutput,
                                   boolean heuristicHit, String heuristicStrategy,
                                   Long durationMs, String status, String errorMessage) {
        }

        @Override
        public List<Map<String, Object>> queryBySession(String sessionId) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> queryRecent(int limit) {
            return List.of();
        }

        @Override
        public long countSince(LocalDateTime since) {
            return 0;
        }
    }

    /** 真的记账：纯 RAG 链路曾经只读历史、从不写历史，导致该模式下改写永远拿不到上下文 */
    static class RecordingConversationStore implements ConversationStorePort {
        final List<Map.Entry<String, String>> turns = new ArrayList<>();
        private String pendingUser;

        @Override
        public void saveUser(String sessionId, String userMessage) {
            this.pendingUser = userMessage;
        }

        @Override
        public void saveAssistant(String sessionId, String assistantMessage) {
            turns.add(Map.entry(pendingUser == null ? "" : pendingUser, assistantMessage));
            pendingUser = null;
        }

        @Override
        public List<Map.Entry<String, String>> getHistory(String sessionId, int maxPairs) {
            return List.copyOf(turns);
        }

        @Override
        public void clearExpired() {
        }
    }

    // ==================== 装配 ====================

    private final CapturingVectorStore vectorStore = new CapturingVectorStore();
    private final StubVectorStorePort vectorStorePort = new StubVectorStorePort();
    private final RecordingLlm llm = new RecordingLlm();
    private final RecordingConversationStore conversations = new RecordingConversationStore();

    private RagAppService service(double threshold, int minKeep) {
        PromptBuilder promptBuilder = new PromptBuilder();
        QueryRewriteService rewrite = new QueryRewriteService(
                llm, promptBuilder, new NoopTraceStore(), false, 3000, 3, 3.0);
        RagAppService svc = new RagAppService(llm, vectorStorePort, vectorStore, promptBuilder,
                rewrite, conversations,
                3, 0.3, 0.7, threshold, minKeep);
        svc.initRetrievers();
        return svc;
    }

    private static Document doc(String id, String text, double score) {
        return Document.builder().id(id).text(text).score(score).build();
    }

    // ==================== 阈值必须真的生效 ====================

    @Test
    @DisplayName("配置的相似度阈值必须传给检索请求（默认 0.0 = 有文档就一定返回）")
    void thresholdReachesSearchRequest() {
        service(0.40, 1).retrieveDocuments("电压合规范围是多少", false, 3);

        assertFalse(vectorStore.requests.isEmpty(), "未发起检索");
        assertEquals(0.40, vectorStore.requests.get(0).getSimilarityThreshold(), 1e-9,
                "阈值没有传到 SearchRequest，等于完全没有防护");
    }

    @Test
    @DisplayName("底层向量库不过滤时，服务层仍会按阈值兜底过滤")
    void serviceLayerFiltersLowScoreDocs() {
        vectorStore.results = List.of(
                doc("1", "电能质量 FAQ：电压合规范围 90%~110%", 0.12),
                doc("2", "通用业务规则：谐波失真阈值 8%", 0.09));

        List<DocumentChunk> docs = service(0.40, 0).retrieveDocuments("你好", false, 3);

        assertTrue(docs.isEmpty(), "低相关文档没有被拦下，会被当成用户想问的内容总结");
    }

    @Test
    @DisplayName("min-keep-chunks 保底的块必须打上 belowThreshold 标记")
    void keptFallbackChunksAreMarked() {
        vectorStore.results = List.of(
                doc("1", "电能质量 FAQ", 0.12),
                doc("2", "通用业务规则", 0.09));

        List<DocumentChunk> docs = service(0.40, 1).retrieveDocuments("你好", false, 3);

        assertEquals(1, docs.size(), "min-keep-chunks=1 应只保底 1 块");
        assertTrue(RagAppService.isAllBelowThreshold(docs),
                "保底块未标记 belowThreshold，调用方无法区分它和达标块");
    }

    @Test
    @DisplayName("达标文档正常放行，且不被误标为低置信度")
    void relevantDocsPassThrough() {
        vectorStore.results = List.of(
                doc("1", "电压合规范围为额定电压的 90%~110%", 0.78),
                doc("2", "无关内容", 0.10));

        List<DocumentChunk> docs = service(0.40, 1).retrieveDocuments("电压合规范围", false, 3);

        assertEquals(1, docs.size());
        assertFalse(RagAppService.isAllBelowThreshold(docs));
    }

    @Test
    @DisplayName("设了阈值后「无命中」是正确结果，不能回退到不带阈值的老接口把结果捞回来")
    void emptyResultDoesNotFallBackToUnfilteredSearch() {
        // 【踩过的坑】原实现：新接口返回空 → 认为「新接口有问题」→ 回退 vectorStorePort.similaritySearch()，
        // 而后者不带任何阈值，刚被过滤掉的无关文档又原样回来了，阈值形同虚设。
        vectorStore.results = List.of();
        vectorStorePort.docs = List.of(new DocumentChunk("被阈值过滤掉的无关文档", Map.of()));

        List<DocumentChunk> docs = service(0.40, 1).retrieveDocuments("你好", false, 3);

        assertTrue(docs.isEmpty(), "空结果被无阈值接口兜回来了");
        assertEquals(0, vectorStorePort.similaritySearchCalls, "不该回退到无阈值接口");
    }

    @Test
    @DisplayName("向量层判定不相关时，关键词层不许单独把无关文档兜回来")
    void keywordLegCannotRescueIrrelevantQuery() {
        // 关键词打分对问句里每个汉字只要在文档中出现就 +0.08，中文场景下这种单字巧合接近噪声；
        // 而关键词命中的文档原先是无条件并入结果的，不过任何相似度闸门。
        vectorStore.results = List.of(doc("1", "电能质量 FAQ：电压、频率、谐波", 0.11));
        vectorStorePort.docs = List.of(
                new DocumentChunk("电能质量 FAQ：电压、频率、谐波", Map.of()),
                new DocumentChunk("通用业务规则：不平衡度阈值", Map.of()));

        List<DocumentChunk> docs = service(0.40, 0).retrieveDocuments("你好", true, 3);

        assertTrue(docs.isEmpty(), "关键词层把无关问句的结果兜回来了");
    }

    // ==================== 闲聊短路 ====================

    @Test
    @DisplayName("闲聊/问候不检索、不召回，直接回问候")
    void smallTalkSkipsRetrievalEntirely() {
        vectorStore.results = List.of(doc("1", "电能质量 FAQ：电压合规范围 90%~110%", 0.95));

        Map<String, Object> result = service(0.40, 1).askWithOptions("你好", true, 3, "s1");

        assertTrue(vectorStore.requests.isEmpty(), "问候不该触发向量检索");
        assertEquals(List.of(), result.get("sources"), "问候不该带出知识库来源");
        assertFalse(String.valueOf(result.get("answer")).isBlank());
    }

    @Test
    @DisplayName("知识库问题不会被闲聊短路误伤")
    void knowledgeQuestionStillRetrieves() {
        vectorStore.results = List.of(doc("1", "电压合规范围为额定电压的 90%~110%", 0.82));

        service(0.40, 1).askWithOptions("解释下什么是电压合规范围", true, 3, "s1");

        assertFalse(vectorStore.requests.isEmpty(), "知识库问题必须走检索");
    }

    // ==================== 提示词降级 ====================

    @Test
    @DisplayName("无任何达标召回时给确定性回答，不调 LLM 让它对着空上下文脑补")
    void emptyContextAnswersDeterministically() {
        vectorStore.results = List.of(doc("1", "完全无关的内容", 0.05));

        Map<String, Object> result = service(0.40, 0).askWithOptions("公司年会在哪开", true, 3, null);

        assertEquals(RagAppService.NO_RELEVANT_INFO_ANSWER, result.get("answer"));
        assertEquals(0, llm.calls, "空上下文不该发起 LLM 调用");
    }

    @Test
    @DisplayName("只剩保底块时降级成「不许强行总结」的低置信度提示词")
    void lowConfidencePromptIsUsedForFallbackChunks() {
        vectorStore.results = List.of(doc("1", "电能质量 FAQ：电压、频率、谐波", 0.12));

        service(0.40, 1).askWithOptions("公司年会在哪开", true, 3, null);

        assertEquals(1, llm.calls);
        assertTrue(llm.systemPrompts.get(0).contains("相关性很低"),
                "仍在用默认的「请根据上下文回答」提示词，模型会顺从地总结无关文档");
    }

    // ==================== 多轮历史 ====================

    @Test
    @DisplayName("纯 RAG 链路必须写入会话历史，否则该模式下的 Query 改写永远拿不到上下文")
    void ragChainPersistsConversationHistory() {
        vectorStore.results = List.of(doc("1", "电压合规范围为额定电压的 90%~110%", 0.82));
        RagAppService svc = service(0.40, 1);

        svc.askWithOptions("电压合规范围是多少", true, 3, "s1");

        assertEquals(1, conversations.turns.size(), "这一轮问答没有落进会话历史");
        assertEquals("电压合规范围是多少", conversations.turns.get(0).getKey());
    }

    @Test
    @DisplayName("检索不到相关内容的那一轮同样要落历史，历史不能有空洞")
    void noResultTurnIsAlsoPersisted() {
        vectorStore.results = List.of();
        service(0.40, 0).askWithOptions("公司年会在哪开", true, 3, "s1");

        assertEquals(1, conversations.turns.size());
        assertEquals(RagAppService.NO_RELEVANT_INFO_ANSWER, conversations.turns.get(0).getValue());
    }

    @Test
    @DisplayName("召回达标时使用常规 RAG 提示词")
    void normalPromptIsUsedForRelevantChunks() {
        vectorStore.results = List.of(doc("1", "电压合规范围为额定电压的 90%~110%", 0.82));

        service(0.40, 1).askWithOptions("电压合规范围是多少", true, 3, null);

        assertEquals(1, llm.calls);
        assertFalse(llm.systemPrompts.get(0).contains("相关性很低"));
    }
}
