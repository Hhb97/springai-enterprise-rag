package com.example.ragassitant.application.service;

import com.example.ragassitant.domain.model.DocumentChunk;
import com.example.ragassitant.domain.model.RagAnswer;
import com.example.ragassitant.domain.service.PromptBuilder;
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

    private final LlmPort llmPort;
    private final VectorStorePort vectorStorePort;
    private final VectorStore springAiVectorStore;
    private final PromptBuilder promptBuilder;
    private final int topK;
    private final double keywordWeight;
    private final double vectorWeight;

    private volatile DocumentRetriever documentRetriever;

    public RagAppService(LlmPort llmPort,
                         VectorStorePort vectorStorePort,
                         VectorStore springAiVectorStore,
                         PromptBuilder promptBuilder,
                         @Value("${rag.top-k:3}") int topK,
                         @Value("${rag.hybrid-search.keyword-weight:0.3}") double keywordWeight,
                         @Value("${rag.hybrid-search.vector-weight:0.7}") double vectorWeight) {
        this.llmPort = llmPort;
        this.vectorStorePort = vectorStorePort;
        this.springAiVectorStore = springAiVectorStore;
        this.promptBuilder = promptBuilder;
        this.topK = topK;
        this.keywordWeight = keywordWeight;
        this.vectorWeight = vectorWeight;
    }

    @PostConstruct
    public void initRetrievers() {
        log.info("🔧 初始化 Spring AI 2.0 官方 DocumentRetriever (Spring AI 2.0 GA VectorStoreDocumentRetriever)");
        log.info("   * 说明：Spring AI 2.0.0 GA 未内置 BM25Retriever / EnsembleRetriever，混合检索继续采用项目原有的线性加权实现");
        log.info("   * 待 Spring AI 后续版本加回 BM25/RRF 后，可在本类中无侵入切换");
        this.documentRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(springAiVectorStore)
                .topK(topK * 5)
                .build();
        log.info("✅ Spring AI 2.0 DocumentRetriever 初始化完成: VectorStoreDocumentRetriever");
    }

    public void rebuildBm25Index() {
        log.info("🔄 向量库数据有更新，重新初始化 Spring AI 2.0 DocumentRetriever...");
        this.documentRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(springAiVectorStore)
                .topK(topK * 5)
                .build();
        log.info("✅ DocumentRetriever 重新绑定完成");
    }

    public List<DocumentChunk> retrieveDocuments(String question, boolean enableHybridSearch, int customTopK) {
        int effectiveTopK = customTopK > 0 ? customTopK : topK;
        return enableHybridSearch ? hybridSearch(question, effectiveTopK) : vectorSearch(question, effectiveTopK);
    }

    public Map<String, Object> ask(String question) {
        return askWithOptions(question, null, null);
    }

    public Map<String, Object> askWithOptions(String question, Boolean enableHybridSearch, Integer customTopK) {
        log.info("🔵 收到问题: {}", question);
        long start = System.currentTimeMillis();

        int effectiveTopK = customTopK != null ? customTopK : topK;
        boolean useHybrid = enableHybridSearch != null ? enableHybridSearch : true;

        List<DocumentChunk> docs = useHybrid ? hybridSearch(question, effectiveTopK) : vectorSearch(question, effectiveTopK);

        log.info("🔵 检索到 {} 个相关文档块", docs.size());
        for (int i = 0; i < docs.size(); i++) {
            DocumentChunk doc = docs.get(i);
            String preview = doc.getText().substring(0, Math.min(150, doc.getText().length()));
            log.info("📄 文档[{}]: {}...", i, preview);
        }

        String context = docs.stream().map(DocumentChunk::getText)
                .reduce((a, b) -> a + "\n\n---\n\n" + b).orElse("");

        String systemPrompt = promptBuilder.buildRagSystemPrompt(context);

        log.info("📨 LLM 请求入参:");
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

        return Map.of("answer", answer == null ? "" : answer, "sources", sources, "hybridSearch", useHybrid, "topK", effectiveTopK);
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
                log.warn("⚠️ Spring AI 2.0 新接口无结果，回退到 VectorStorePort 原生接口");
                return vectorStorePort.similaritySearch(question, topK);
            }
            return results.stream()
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
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("⚠️ Spring AI 2.0 新接口异常，回退到 VectorStorePort: {}", e.getMessage());
            return vectorStorePort.similaritySearch(question, topK);
        }
    }

    private List<DocumentChunk> hybridSearch(String question, int topK) {
        log.info("📨 执行混合检索 [线性加权: 向量权重{} / 关键词权重{}]", vectorWeight, keywordWeight);
        log.info("   * 说明：待 Spring AI 后续版本内置 BM25Retriever + EnsembleRetriever(RRF) 后自动升级，无需改接口");
        return linearWeightHybridSearch(question, topK);
    }

    private List<DocumentChunk> linearWeightHybridSearch(String question, int topK) {
        List<DocumentChunk> vectorResults = vectorSearch(question, topK * 2);
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