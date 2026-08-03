package com.example.ragassitant.application.service;

import com.example.ragassitant.domain.model.DocumentChunk;
import com.example.ragassitant.domain.model.RagAnswer;
import com.example.ragassitant.domain.service.HeuristicRouter;
import com.example.ragassitant.domain.service.PromptBuilder;
import com.example.ragassitant.domain.spi.ConversationStorePort;
import com.example.ragassitant.domain.spi.LlmPort;
import com.example.ragassitant.domain.spi.VectorStorePort;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class RagAppService {

    private static final Logger log = LoggerFactory.getLogger(RagAppService.class);

    /** 检索无达标结果时的确定性回答：不发起 LLM 调用，从根上断掉「空上下文脑补」。 */
    static final String NO_RELEVANT_INFO_ANSWER = "抱歉，知识库中没有找到与该问题相关的信息。";

    /** LLM 不可用时的问候兜底，保证闲聊短路不会返回空串。 */
    static final String SMALL_TALK_FALLBACK =
            "您好！我是智能运维助手，可以帮您查询设备清单与详情、分析能耗数据，也能基于知识库回答业务问题。请问需要什么帮助？";

    private final LlmPort llmPort;
    private final VectorStorePort vectorStorePort;
    private final VectorStore springAiVectorStore;
    private final PromptBuilder promptBuilder;
    private final QueryRewriteService queryRewriteService;
    private final ConversationStorePort conversationStorePort;
    private final int topK;
    private final double keywordWeight;
    private final double vectorWeight;
    private final double similarityThreshold;
    private final int minKeepChunks;

    private volatile DocumentRetriever documentRetriever;

    public RagAppService(LlmPort llmPort,
                         VectorStorePort vectorStorePort,
                         VectorStore springAiVectorStore,
                         PromptBuilder promptBuilder,
                         QueryRewriteService queryRewriteService,
                         ConversationStorePort conversationStorePort,
                         @Value("${rag.top-k:3}") int topK,
                         @Value("${rag.hybrid-search.keyword-weight:0.3}") double keywordWeight,
                         @Value("${rag.hybrid-search.vector-weight:0.7}") double vectorWeight,
                         @Value("${rag.similarity-threshold:0.0}") double similarityThreshold,
                         @Value("${rag.min-keep-chunks:0}") int minKeepChunks) {
        this.llmPort = llmPort;
        this.vectorStorePort = vectorStorePort;
        this.springAiVectorStore = springAiVectorStore;
        this.promptBuilder = promptBuilder;
        this.queryRewriteService = queryRewriteService;
        this.conversationStorePort = conversationStorePort;
        this.topK = topK;
        this.keywordWeight = keywordWeight;
        this.vectorWeight = vectorWeight;
        this.similarityThreshold = similarityThreshold;
        this.minKeepChunks = Math.max(0, minKeepChunks);
        log.info("🔧 RAG 检索配置: topK={}, 相似度阈值={}, 低于阈值时保底保留={} 块, 混合权重(向量/关键词)={}/{}",
                topK, similarityThreshold, this.minKeepChunks, vectorWeight, keywordWeight);
    }

    @PostConstruct
    public void initRetrievers() {
        log.info("🔧 初始化 Spring AI 2.0 官方 DocumentRetriever (Spring AI 2.0 GA VectorStoreDocumentRetriever)");
        log.info("   * 说明：Spring AI 2.0.0 GA 未内置 BM25Retriever / EnsembleRetriever，混合检索继续采用项目原有的线性加权实现");
        log.info("   * 待 Spring AI 后续版本加回 BM25/RRF 后，可在本类中无侵入切换");
        this.documentRetriever = buildRetriever();
        log.info("✅ Spring AI 2.0 DocumentRetriever 初始化完成: VectorStoreDocumentRetriever (similarityThreshold={})",
                similarityThreshold);
    }

    /**
     * 向量库数据变更后重新绑定检索器。
     *
     * <p>原名 {@code rebuildBm25Index}，但方法体里没有任何 BM25 —— Spring AI 2.0 GA 不含 BM25Retriever，
     * 混合检索的关键词那一路是本类自己的线性加权实现，且它是每次查询实时算的、不存在需要重建的索引。
     * 名字和日志都写着「刷新 BM25 倒排索引」，排障时会让人去找一个根本不存在的索引。
     */
    public void rebuildRetriever() {
        log.info("🔄 向量库数据有更新，重新初始化 Spring AI 2.0 DocumentRetriever...");
        this.documentRetriever = buildRetriever();
        log.info("✅ DocumentRetriever 重新绑定完成");
    }

    /**
     * 【幻觉防护】必须显式设置 similarityThreshold。
     *
     * <p>{@code VectorStoreDocumentRetriever} 的默认阈值是 {@code 0.0}，含义是「库里有文档就一定返回 topK 条」——
     * 一句「你好」和电能质量 FAQ 的余弦相似度只有 0.1 也照样被召回并注入提示词，模型于是开始总结 FAQ。
     * 阈值一度只写在 application.yml 里而没有任何代码读取，等于完全没有防护。
     */
    private DocumentRetriever buildRetriever() {
        return VectorStoreDocumentRetriever.builder()
                .vectorStore(springAiVectorStore)
                .similarityThreshold(similarityThreshold)
                .topK(topK * 5)
                .build();
    }

    public List<DocumentChunk> retrieveDocuments(String question, boolean enableHybridSearch, int customTopK) {
        int effectiveTopK = customTopK > 0 ? customTopK : topK;
        return enableHybridSearch ? hybridSearch(question, effectiveTopK) : vectorSearch(question, effectiveTopK);
    }

    /**
     * 相似度闸门。返回的块可能带 {@code belowThreshold=true} 标记（{@code min-keep-chunks} 的保底产物）。
     *
     * <p>保底保留是为了「不给模型完全空的上下文」，但保底块<b>不能</b>和达标块一样使用 ——
     * 它本质上是「最不差的无关文档」。调用方必须按标记区分：
     * Agent 链路直接丢弃，纯 RAG 链路降级成低置信度提示词（见 {@code PromptBuilder#buildRagLowConfidenceSystemPrompt}）。
     */
    private List<DocumentChunk> applyThreshold(List<DocumentChunk> docs) {
        if (docs == null || docs.isEmpty() || similarityThreshold <= 0) return docs;

        List<DocumentChunk> passed = docs.stream()
                .filter(d -> score(d) >= similarityThreshold)
                .collect(Collectors.toList());
        if (!passed.isEmpty()) return passed;

        double top = docs.stream().mapToDouble(this::score).max().orElse(0.0);
        if (minKeepChunks <= 0) {
            log.info("🚧 检索结果全部低于相似度阈值 {}（最高 {}），已全部丢弃，上下文为空", similarityThreshold, top);
            return List.of();
        }
        List<DocumentChunk> kept = docs.stream()
                .sorted((a, b) -> Double.compare(score(b), score(a)))
                .limit(minKeepChunks)
                .peek(d -> {
                    if (d.getMetadata() == null) d.setMetadata(new HashMap<>());
                    d.getMetadata().put("belowThreshold", true);
                })
                .collect(Collectors.toList());
        log.info("🚧 检索结果全部低于相似度阈值 {}（最高 {}），保底保留 {} 块并标记 belowThreshold",
                similarityThreshold, top, kept.size());
        return kept;
    }

    /**
     * 闸门只看<b>向量余弦相似度</b>，不看混合融合分。
     *
     * <p>融合分 = 向量分×0.7 + 关键词分×0.3，而关键词分是本项目自定义的加权计数，量纲和余弦相似度不同，
     * 拿 0.40 这种「余弦经验值」去卡融合分会把好文档误杀、把巧合命中的坏文档放过。
     */
    private double score(DocumentChunk doc) {
        return doc.getScore() == null ? 0.0 : doc.getScore();
    }

    /** 是否所有召回块都是低于阈值的保底产物（调用方据此降级提示词）。 */
    public static boolean isAllBelowThreshold(List<DocumentChunk> docs) {
        if (docs == null || docs.isEmpty()) return false;
        return docs.stream().allMatch(d -> d.getMetadata() != null
                && Boolean.TRUE.equals(d.getMetadata().get("belowThreshold")));
    }

    public Map<String, Object> ask(String question) {
        return askWithOptions(question, null, null, null);
    }

    public Map<String, Object> askWithOptions(String question, Boolean enableHybridSearch, Integer customTopK) {
        return askWithOptions(question, enableHybridSearch, customTopK, null);
    }

    /**
     * @param sessionId 用于多轮指代消解；为空则跳过 Query 改写，行为与单轮一致
     */
    public Map<String, Object> askWithOptions(String question, Boolean enableHybridSearch,
                                              Integer customTopK, String sessionId) {
        log.info("🔵 收到问题: {}", question);
        long start = System.currentTimeMillis();

        int effectiveTopK = customTopK != null ? customTopK : topK;
        boolean useHybrid = enableHybridSearch != null ? enableHybridSearch : true;

        // ===== 闲聊短路：和 Agent 链路同一套判定 =====
        // 纯 RAG 链路同样会被「你好」击中：检索必然召回文档，提示词又写着「请根据上下文回答」，
        // 模型只能去总结那段无关文档。问候没有可检索的语义，直接用不带上下文的提示词回一句。
        if (HeuristicRouter.isSmallTalk(question)) {
            log.info("💬 闲聊/问候短路（RAG 链路）：跳过 Query 改写与向量检索 → question={}", question);
            String reply = llmPort.chat(promptBuilder.buildSmallTalkSystemPrompt(), question);
            if (reply == null || reply.isBlank()) reply = SMALL_TALK_FALLBACK;
            return finish(sessionId, question, reply, List.of(), useHybrid, effectiveTopK);
        }

        // 检索用改写后的 query；送给 LLM 的仍是用户原话
        String retrievalQuery = question;
        if (sessionId != null && !sessionId.isBlank()) {
            retrievalQuery = queryRewriteService.rewrite(
                    question, conversationStorePort.getHistory(sessionId, 10), sessionId).get(0);
        }

        List<DocumentChunk> docs = useHybrid
                ? hybridSearch(retrievalQuery, effectiveTopK)
                : vectorSearch(retrievalQuery, effectiveTopK);

        log.info("🔵 检索到 {} 个相关文档块", docs.size());
        for (int i = 0; i < docs.size(); i++) {
            DocumentChunk doc = docs.get(i);
            String preview = doc.getText().substring(0, Math.min(150, doc.getText().length()));
            log.info("📄 文档[{}]: {}...", i, preview);
        }

        // 上下文为空就没必要发起 LLM 调用：让小模型对着空上下文「自由发挥」是纯粹的幻觉风险，
        // 直接给确定性回答，同时省掉一次推理延迟。
        if (docs.isEmpty()) {
            log.info("🚧 无任何达标召回，直接返回「知识库无相关信息」，不调用 LLM");
            return finish(sessionId, question, NO_RELEVANT_INFO_ANSWER, List.of(), useHybrid, effectiveTopK);
        }

        String context = docs.stream().map(DocumentChunk::getText)
                .reduce((a, b) -> a + "\n\n---\n\n" + b).orElse("");

        // 全是 min-keep-chunks 的保底块 → 换成「不许强行总结」的低置信度提示词
        boolean lowConfidence = isAllBelowThreshold(docs);
        String systemPrompt = lowConfidence
                ? promptBuilder.buildRagLowConfidenceSystemPrompt(context)
                : promptBuilder.buildRagSystemPrompt(context);

        log.info("📨 LLM 请求入参: 置信度={}", lowConfidence ? "LOW(保底块)" : "NORMAL");
        log.info("   context: {}...", context.substring(0, Math.min(300, context.length())));
        log.info("   input: {}", question);

        String answer = llmPort.chat(systemPrompt, question);

        long elapsed = System.currentTimeMillis() - start;
        log.info("🟢 LLM 返回完成 | 耗时: {}ms", elapsed);
        log.info("🟢 回答: {}", answer);

        List<Map<String, Object>> sources = docs.stream().map(doc -> {
            Object hybridScore = doc.getMetadata() == null ? null : doc.getMetadata().get("hybridScore");
            double score = 0.0;
            if (hybridScore instanceof Number) score = ((Number) hybridScore).doubleValue();
            else if (doc.getScore() != null) score = doc.getScore();
            String content = doc.getText().substring(0, Math.min(200, doc.getText().length()));
            return Map.<String, Object>of("content", content, "metadata", doc.getMetadata() == null ? Map.of() : doc.getMetadata(), "score", score);
        }).toList();

        return finish(sessionId, question, answer == null ? "" : answer, sources, useHybrid, effectiveTopK);
    }

    /**
     * 统一出口：落一轮对话历史再返回。
     *
     * <p>【原来漏了什么】本方法上游读了 {@code getHistory(sessionId, 10)} 去做 Query 改写，
     * 但纯 RAG 链路从来没有<b>写</b>过历史（写入只发生在 AgentAppService）。
     * 于是「知识库」模式下会话历史恒为空，改写永远拿不到上下文 ——
     * 「那结算周期呢」这类追问的指代消解在该模式下实际上从未生效过。
     */
    private Map<String, Object> finish(String sessionId, String question, String answer,
                                       List<Map<String, Object>> sources, boolean useHybrid, int effectiveTopK) {
        if (sessionId != null && !sessionId.isBlank()) {
            conversationStorePort.saveUser(sessionId, question);
            conversationStorePort.saveAssistant(sessionId, answer);
        }
        return Map.of("answer", answer, "sources", sources,
                "hybridSearch", useHybrid, "topK", effectiveTopK);
    }

    public RagAnswer.SourceDocument toSourceDoc(DocumentChunk chunk) {
        Object title = chunk.getMetadata() == null ? null : chunk.getMetadata().get("title");
        Object fileName = chunk.getMetadata() == null ? null : chunk.getMetadata().get("fileName");
        Double score = chunk.getScore();
        return new RagAnswer.SourceDocument(
                title != null ? title.toString() : (fileName != null ? fileName.toString() : "文档块"),
                fileName != null ? fileName.toString() : "",
                score
        );
    }

    private List<DocumentChunk> vectorSearch(String question, int topK) {
        log.info("📨 执行向量检索 [Spring AI 2.0 VectorStoreDocumentRetriever]...");
        try {
            Query query = new Query(question);
            List<Document> results = documentRetriever.retrieve(query);
            if (results == null || results.isEmpty()) {
                // 【坑】设了阈值以后，「空结果」是正确结果而不是故障。
                // 原来这里无条件回退到 vectorStorePort.similaritySearch()（该实现不带阈值），
                // 等于把刚被阈值过滤掉的无关文档又原样捞回来，阈值形同虚设。
                if (similarityThreshold > 0) {
                    log.info("🚧 向量检索无命中（相似度均低于阈值 {}），不回退无阈值接口", similarityThreshold);
                    return List.of();
                }
                log.warn("⚠️ Spring AI 2.0 新接口无结果，回退到 VectorStorePort 原生接口");
                return applyThreshold(vectorStorePort.similaritySearch(question, topK));
            }
            return applyThreshold(results.stream()
                    .limit(topK)
                    .map(d -> {
                        DocumentChunk c = new DocumentChunk();
                        c.setId(d.getId());
                        c.setText(d.getText());
                        c.setScore(d.getScore() != null ? d.getScore().doubleValue() : null);
                        c.setMetadata(d.getMetadata() != null ? new HashMap<>(d.getMetadata()) : new HashMap<>());
                        if (c.getMetadata() != null) c.getMetadata().put("retrievalMode", "SPRING_AI_2_VECTORSTORE_DOC_RETRIEVER");
                        return c;
                    })
                    .collect(Collectors.toList()));
        } catch (Exception e) {
            log.warn("⚠️ Spring AI 2.0 新接口异常，回退到 VectorStorePort: {}", e.getMessage());
            return applyThreshold(vectorStorePort.similaritySearch(question, topK));
        }
    }

    private List<DocumentChunk> hybridSearch(String question, int topK) {
        log.info("📨 执行混合检索 [线性加权: 向量权重{} / 关键词权重{}]", vectorWeight, keywordWeight);
        log.info("   * 说明：待 Spring AI 后续版本内置 BM25Retriever + EnsembleRetriever(RRF) 后自动升级，无需改接口");
        return linearWeightHybridSearch(question, topK);
    }

    private List<DocumentChunk> linearWeightHybridSearch(String question, int topK) {
        List<DocumentChunk> vectorResults = vectorSearch(question, topK * 2);

        // 【幻觉防护】向量层认定「全库都和这个问题不相关」时，关键词层不许单独把结果救回来。
        // 原实现里关键词命中的文档是无条件并入的：它自身不过任何相似度闸门，而打分函数
        // 对问句里每个汉字只要在文档中出现就 +0.08（见 keywordSearchWithScore），
        // 中文场景下这种单字巧合几乎等于噪声 —— 于是无关问句照样能凑出一批「命中」文档。
        if (similarityThreshold > 0 && (vectorResults.isEmpty() || isAllBelowThreshold(vectorResults))) {
            log.info("🚧 向量层判定与问题不相关（阈值 {}），跳过关键词层兜底，混合检索返回 {} 块",
                    similarityThreshold, vectorResults.size());
            return vectorResults;
        }

        List<Map.Entry<DocumentChunk, Double>> keywordResults = keywordSearchWithScore(question, topK * 2);

        Map<String, DocumentChunk> merged = new LinkedHashMap<>();
        Map<String, Double> scores = new HashMap<>();

        for (DocumentChunk doc : vectorResults) {
            String key = doc.getId() != null ? doc.getId() : String.valueOf(doc.getText().hashCode());
            if (!merged.containsKey(key)) {
                double score = doc.getScore() != null ? doc.getScore() * vectorWeight : 0.5 * vectorWeight;
                merged.put(key, doc);
                scores.put(key, score);
            }
        }

        for (Map.Entry<DocumentChunk, Double> entry : keywordResults) {
            DocumentChunk doc = entry.getKey();
            Double keywordScore = entry.getValue();
            String key = doc.getId() != null ? doc.getId() : String.valueOf(doc.getText().hashCode());
            if (merged.containsKey(key)) {
                double current = scores.getOrDefault(key, 0.0);
                scores.put(key, current + keywordScore * keywordWeight);
            } else {
                double score = keywordScore * keywordWeight;
                merged.put(key, doc);
                scores.put(key, score);
            }
        }

        return merged.entrySet().stream()
                .sorted((a, b) -> Double.compare(scores.get(b.getKey()), scores.get(a.getKey())))
                .limit(topK)
                .map(entry -> {
                    DocumentChunk doc = entry.getValue();
                    if (doc.getMetadata() == null) doc.setMetadata(new HashMap<>());
                    doc.getMetadata().put("hybridScore", scores.get(entry.getKey()));
                    doc.getMetadata().put("retrievalMode", "HYBRID_LINEAR_WEIGHT_SPRINGAI2");
                    return doc;
                })
                .collect(Collectors.toList());
    }

    private static final Map<String, String[]> SYNONYM_MAP = new HashMap<>();
    static {
        SYNONYM_MAP.put("账单", new String[]{"计费", "账单", "收费", "结算"});
        SYNONYM_MAP.put("计费", new String[]{"计费", "账单", "收费", "结算"});
        SYNONYM_MAP.put("收费", new String[]{"计费", "账单", "收费", "结算"});
        SYNONYM_MAP.put("结算", new String[]{"计费", "账单", "收费", "结算"});
        SYNONYM_MAP.put("流程", new String[]{"流程", "步骤", "过程", "操作"});
        SYNONYM_MAP.put("步骤", new String[]{"流程", "步骤", "过程", "操作"});
        SYNONYM_MAP.put("用户", new String[]{"用户", "账号", "账户", "成员"});
        SYNONYM_MAP.put("新增", new String[]{"新增", "添加", "创建", "新建"});
        SYNONYM_MAP.put("设备", new String[]{"设备", "装置", "仪器"});
        SYNONYM_MAP.put("能耗", new String[]{"能耗", "耗电", "用电", "消耗"});
    }

    private Set<String> expandChineseKeywords(String text) {
        Set<String> keywords = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return keywords;
        String lower = text.trim().toLowerCase();

        for (String word : lower.split("\\s+")) {
            if (word.isBlank()) continue;
            keywords.add(word);

            boolean hasChinese = false;
            for (char c : word.toCharArray()) {
                if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                    hasChinese = true;
                    break;
                }
            }
            if (!hasChinese) continue;

            for (int i = 0; i < word.length(); i++) {
                for (int j = i + 2; j <= Math.min(word.length(), i + 4); j++) {
                    keywords.add(word.substring(i, j));
                }
            }
            for (char c : word.toCharArray()) {
                if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                    keywords.add(String.valueOf(c));
                }
            }
            for (Map.Entry<String, String[]> entry : SYNONYM_MAP.entrySet()) {
                if (word.contains(entry.getKey())) {
                    for (String syn : entry.getValue()) {
                        keywords.add(syn);
                        for (int i = 0; i <= word.length() - entry.getKey().length(); i++) {
                            if (word.startsWith(entry.getKey(), i)) {
                                String replaced = word.substring(0, i) + syn + word.substring(i + entry.getKey().length());
                                if (!replaced.equals(word)) keywords.add(replaced);
                            }
                        }
                    }
                }
            }
        }
        keywords.removeIf(k -> k.length() == 1 && !SYNONYM_MAP.containsKey(k));
        return keywords;
    }

    private List<Map.Entry<DocumentChunk, Double>> keywordSearchWithScore(String question, int topK) {
        List<DocumentChunk> allDocs = vectorStorePort.listAll(topK * 10);
        List<Map.Entry<DocumentChunk, Double>> scoredDocs = new ArrayList<>();
        Set<String> keywords = expandChineseKeywords(question);
        String lowerQuestion = question.toLowerCase();

        if (keywords.isEmpty()) return scoredDocs;
        log.debug("🔍 关键词扩展结果: question={}, keywords={}", question, keywords);

        for (DocumentChunk doc : allDocs) {
            String lowerText = doc.getText().toLowerCase();
            double score = 0;
            int matched = 0;

            for (String kw : keywords) {
                if (kw.length() <= 1) continue;
                int count = 0, idx = 0;
                while ((idx = lowerText.indexOf(kw, idx)) != -1) {
                    count++;
                    idx += kw.length();
                }
                if (count > 0) {
                    double weight = kw.length() >= 3 ? 1.2 : (kw.length() == 2 ? 1.0 : 0.5);
                    score += weight * Math.min(count, 3) / keywords.size();
                    matched++;
                }
            }

            for (char c : lowerQuestion.toCharArray()) {
                if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                    if (lowerText.indexOf(c) >= 0) score += 0.08;
                }
            }

            Object titleMeta = doc.getMetadata() == null ? null : doc.getMetadata().get("title");
            if (titleMeta != null) {
                String title = titleMeta.toString().toLowerCase();
                if (title.contains(lowerQuestion)) score += 0.5;
                else for (String kw : keywords) {
                    if (kw.length() >= 2 && title.contains(kw)) score += 0.15;
                }
            }

            if (matched == 0 && score < 0.25) continue;
            scoredDocs.add(new AbstractMap.SimpleEntry<>(doc, score));
        }
        return scoredDocs.stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .collect(Collectors.toList());
    }
}