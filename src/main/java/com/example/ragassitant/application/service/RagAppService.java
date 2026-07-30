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
                .topK(topK * 2)
                .build();
        log.info("✅ Spring AI 2.0 DocumentRetriever 初始化完成: VectorStoreDocumentRetriever");
    }

    public void rebuildBm25Index() {
        log.info("🔄 向量库数据有更新，重新初始化 Spring AI 2.0 DocumentRetriever...");
        this.documentRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(springAiVectorStore)
                .topK(topK * 2)
                .build();
        log.info("✅ DocumentRetriever 重新绑定完成");
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

    private List<Map.Entry<DocumentChunk, Double>> keywordSearchWithScore(String question, int topK) {
        List<DocumentChunk> allDocs = vectorStorePort.listAll(topK * 5);
        List<Map.Entry<DocumentChunk, Double>> scoredDocs = new ArrayList<>();
        String lowerQuestion = question.toLowerCase();

        for (DocumentChunk doc : allDocs) {
            String lowerText = doc.getText().toLowerCase();
            double score = 0;
            String[] keywords = lowerQuestion.split("\\s+");
            for (String keyword : keywords) {
                if (lowerText.contains(keyword)) score += 1.0 / keywords.length;
            }
            Object titleMeta = doc.getMetadata() == null ? null : doc.getMetadata().get("title");
            if (titleMeta != null && titleMeta.toString().toLowerCase().contains(lowerQuestion)) score += 0.3;
            if (score > 0) scoredDocs.add(new AbstractMap.SimpleEntry<>(doc, score));
        }
        return scoredDocs.stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .collect(Collectors.toList());
    }
}