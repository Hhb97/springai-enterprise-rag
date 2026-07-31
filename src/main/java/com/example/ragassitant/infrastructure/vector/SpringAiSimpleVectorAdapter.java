package com.example.ragassitant.infrastructure.vector;

import com.example.ragassitant.domain.model.ChunkMetaKeys;
import com.example.ragassitant.domain.model.DocumentChunk;
import com.example.ragassitant.domain.spi.VectorStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Component
public class SpringAiSimpleVectorAdapter implements VectorStorePort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiSimpleVectorAdapter.class);

    /**
     * 枚举全库时的取数上限。SimpleVectorStore 是纯内存实现，本就撑不到这个量级；
     * 一旦触顶会明确告警而不是静默截断（换 PGVector 后应改为按 metadata filter 删除，不再需要枚举）。
     */
    private static final int ENUMERATE_LIMIT = 10000;

    private final VectorStore vectorStore;
    private final String vectorStorePath;

    public SpringAiSimpleVectorAdapter(VectorStore vectorStore,
                                       @Value("${storage.upload-dir:./uploads}") String uploadDir) {
        this.vectorStore = vectorStore;
        this.vectorStorePath = uploadDir + "/vectorstore.json";
    }

    @Override
    public void add(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return;
        List<Document> docs = new ArrayList<>(chunks.size());
        for (DocumentChunk c : chunks) {
            Document d = new Document(c.getId() != null ? c.getId() : java.util.UUID.randomUUID().toString(),
                                      c.getText(),
                                      c.getMetadata() != null ? c.getMetadata() : new HashMap<>());
            docs.add(d);
        }
        vectorStore.add(docs);
    }

    @Override
    public List<DocumentChunk> similaritySearch(String query, int topK) {
        SearchRequest req = SearchRequest.builder().query(query == null ? "" : query).topK(topK).build();
        List<Document> docs = vectorStore.similaritySearch(req);
        return convert(docs);
    }

    @Override
    public List<DocumentChunk> listAll(int limit) {
        SearchRequest req = SearchRequest.builder().query(" ").topK(Math.max(1, limit)).build();
        return convert(vectorStore.similaritySearch(req));
    }

    /**
     * 按 id 删除。
     *
     * <p><b>注意 Spring AI 的 API 陷阱</b>：{@code VectorStore.delete(String)} 不是「按 id 删除」，
     * 它把入参当作<b>过滤表达式 DSL</b> 解析（内部走 {@code SearchRequest.filterExpression(String)}），
     * 传 UUID 会直接抛 {@code FilterExpressionParseException}。按 id 删除必须用 {@code delete(List<String>)}。
     */
    @Override
    public void delete(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) return;
        vectorStore.delete(List.of(chunkId));
    }

    /**
     * 按 documentId 删除该文档的全部向量块。
     * 直接下推 metadata 过滤表达式，不再全表扫描，也没有条数上限。
     */
    @Override
    public int deleteByDocumentId(String documentId) {
        if (documentId == null || documentId.isBlank()) return 0;

        int before = countAll();
        Filter.Expression expression = new FilterExpressionBuilder()
                .eq(ChunkMetaKeys.DOCUMENT_ID, documentId)
                .build();
        vectorStore.delete(expression);
        int deleted = before - countAll();

        log.info("🗑️ 已按 documentId={} 删除 {} 条向量块", documentId, deleted);
        return deleted;
    }

    @Override
    public int deleteAll() {
        List<DocumentChunk> allChunks = listAll(ENUMERATE_LIMIT);
        if (allChunks.isEmpty()) return 0;
        if (allChunks.size() >= ENUMERATE_LIMIT) {
            log.warn("⚠️ 枚举全库时触顶（上限 {} 条），本次可能未清理干净，请改用支持 metadata 过滤删除的向量库（如 PGVector）",
                    ENUMERATE_LIMIT);
        }

        List<String> ids = allChunks.stream()
                .map(DocumentChunk::getId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        if (ids.isEmpty()) return 0;

        // 一次批量删除，避免逐条调用放大失败面
        vectorStore.delete(ids);
        log.info("🗑️ 已清空向量库，共删除 {} 条", ids.size());
        return ids.size();
    }

    @Override
    public void persist() {
        if (vectorStore instanceof SimpleVectorStore svs) {
            try {
                svs.save(new File(vectorStorePath));
            } catch (Exception e) {
                throw new RuntimeException("保存向量库失败", e);
            }
        }
    }

    /** SimpleVectorStore 没有 count API，只能枚举后取数量 */
    private int countAll() {
        return listAll(ENUMERATE_LIMIT).size();
    }

    private List<DocumentChunk> convert(List<Document> docs) {
        List<DocumentChunk> result = new ArrayList<>();
        if (docs == null) return result;
        for (Document d : docs) {
            DocumentChunk c = new DocumentChunk();
            c.setId(d.getId());
            c.setText(d.getText());
            c.setMetadata(d.getMetadata() != null ? new HashMap<>(d.getMetadata()) : new HashMap<>());
            c.setScore(d.getScore() != null ? d.getScore().doubleValue() : null);
            result.add(c);
        }
        return result;
    }
}
