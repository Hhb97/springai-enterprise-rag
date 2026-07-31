package com.example.ragassitant.infrastructure.vector;

import com.example.ragassitant.domain.model.ChunkMetaKeys;
import com.example.ragassitant.domain.model.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 向量库删除路径的回归测试。
 *
 * <p>背景：{@code VectorStore.delete(String)} 并不是「按 id 删除」，它把入参当作过滤表达式 DSL
 * 解析，传 UUID 会抛 FilterExpressionParseException。改造前的适配器正是这么调的，
 * 且用空 catch 吞掉异常，导致删除长期静默失效、脏向量不断堆积。
 *
 * <p>这里用真实的 SimpleVectorStore + 确定性桩 EmbeddingModel 跑通完整路径。
 */
class SpringAiSimpleVectorAdapterTest {

    /** 确定性桩：按文本 hash 生成固定向量，各分量恒为正，保证余弦相似度 > 0 不被阈值过滤 */
    static class StubEmbeddingModel implements EmbeddingModel {
        private static final int DIM = 16;

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }

        @Override
        public float[] embed(String text) {
            float[] v = new float[DIM];
            int h = text == null ? 0 : text.hashCode();
            for (int i = 0; i < DIM; i++) {
                v[i] = 0.5f + Math.abs((h >> i) % 7) / 10.0f;
            }
            return v;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> out = new ArrayList<>();
            List<String> inputs = request.getInstructions();
            for (int i = 0; i < inputs.size(); i++) {
                out.add(new Embedding(embed(inputs.get(i)), i));
            }
            return new EmbeddingResponse(out);
        }

        @Override
        public int dimensions() {
            return DIM;
        }
    }

    private SpringAiSimpleVectorAdapter adapter;

    @BeforeEach
    void setUp() {
        SimpleVectorStore store = SimpleVectorStore.builder(new StubEmbeddingModel()).build();
        adapter = new SpringAiSimpleVectorAdapter(store, "./target/test-vectorstore");
    }

    private DocumentChunk chunk(String id, String text, String documentId) {
        DocumentChunk c = new DocumentChunk();
        c.setId(id);
        c.setText(text);
        Map<String, Object> meta = new HashMap<>();
        meta.put(ChunkMetaKeys.FILE_NAME, documentId + ".md");
        if (documentId != null) meta.put(ChunkMetaKeys.DOCUMENT_ID, documentId);
        c.setMetadata(meta);
        return c;
    }

    @Test
    @DisplayName("按 id 删除真正生效（改造前会抛 FilterExpressionParseException）")
    void deleteByIdActuallyRemoves() {
        adapter.add(List.of(
                chunk("id-1", "第一段内容", "docA"),
                chunk("id-2", "第二段内容", "docA")));
        assertEquals(2, adapter.listAll(100).size());

        adapter.delete("id-1");

        List<DocumentChunk> rest = adapter.listAll(100);
        assertEquals(1, rest.size());
        assertEquals("id-2", rest.get(0).getId());
    }

    @Test
    @DisplayName("deleteAll 清空全库并返回真实删除条数")
    void deleteAllRemovesEverything() {
        adapter.add(List.of(
                chunk("id-1", "内容一", "docA"),
                chunk("id-2", "内容二", "docA"),
                chunk("id-3", "内容三", "docB")));

        int deleted = adapter.deleteAll();

        assertEquals(3, deleted, "返回的删除条数必须是真实值，不能像改造前那样恒为 0");
        assertTrue(adapter.listAll(100).isEmpty(), "库里仍有残留，脏向量会污染后续检索");
    }

    @Test
    @DisplayName("deleteByDocumentId 只删目标文档，不误伤其他文档")
    void deleteByDocumentIdIsScoped() {
        adapter.add(List.of(
                chunk("id-1", "A文档第一段", "docA"),
                chunk("id-2", "A文档第二段", "docA"),
                chunk("id-3", "B文档第一段", "docB")));

        int deleted = adapter.deleteByDocumentId("docA");

        assertEquals(2, deleted);
        List<DocumentChunk> rest = adapter.listAll(100);
        assertEquals(1, rest.size());
        assertEquals("docB", rest.get(0).getMetadata().get(ChunkMetaKeys.DOCUMENT_ID));
    }

    @Test
    @DisplayName("重复 ingest 不会堆积脏数据（deleteAll 后重新入库）")
    void reingestDoesNotAccumulate() {
        adapter.add(List.of(chunk("old-1", "旧版内容一", "docA"), chunk("old-2", "旧版内容二", "docA")));
        adapter.deleteAll();
        adapter.add(List.of(chunk("new-1", "新版内容", "docA")));

        List<DocumentChunk> all = adapter.listAll(100);
        assertEquals(1, all.size(), "实测线上出现过 50 条旧 schema + 11 条新 schema 并存的情况");
        assertEquals("new-1", all.get(0).getId());
    }

    @Test
    @DisplayName("空库上的删除操作安全返回 0")
    void deleteOnEmptyStoreIsSafe() {
        assertEquals(0, adapter.deleteAll());
        assertEquals(0, adapter.deleteByDocumentId("docA"));
        assertDoesNotThrow(() -> adapter.delete("nonexistent-id"));
        assertDoesNotThrow(() -> adapter.delete(null));
    }
}
