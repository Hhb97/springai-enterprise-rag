package com.example.ragassitant.application.service;

import com.example.ragassitant.domain.model.DocumentChunk;
import com.example.ragassitant.domain.model.RagAnswer;
import com.example.ragassitant.domain.service.PromptBuilder;
import com.example.ragassitant.domain.spi.LlmPort;
import com.example.ragassitant.domain.spi.VectorStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class RagAppService {

    private static final Logger log = LoggerFactory.getLogger(RagAppService.class);

    private final LlmPort llmPort;
    private final VectorStorePort vectorStorePort;
    private final PromptBuilder promptBuilder;
    private final int topK;
    private final double keywordWeight;
    private final double vectorWeight;

    public RagAppService(LlmPort llmPort,
                         VectorStorePort vectorStorePort,
                         PromptBuilder promptBuilder,
                         @Value("${rag.top-k:3}") int topK,
                         @Value("${rag.hybrid-search.keyword-weight:0.3}") double keywordWeight,
                         @Value("${rag.hybrid-search.vector-weight:0.7}") double vectorWeight) {
        this.llmPort = llmPort;
        this.vectorStorePort = vectorStorePort;
        this.promptBuilder = promptBuilder;
        this.topK = topK;
        this.keywordWeight = keywordWeight;
        this.vectorWeight = vectorWeight;
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
        log.info("📨 执行向量检索...");
        return vectorStorePort.similaritySearch(question, topK);
    }

    private List<DocumentChunk> hybridSearch(String question, int topK) {
        log.info("📨 执行混合检索 (keyword:{}, vector:{})...", keywordWeight, vectorWeight);
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
                    return doc;
                })
                .collect(Collectors.toList());
    }

    private List<Map.Entry<DocumentChunk, Double>> keywordSearchWithScore(String question, int topK) {
        log.info("📨 执行关键词检索...");
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