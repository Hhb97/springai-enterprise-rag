package com.example.ragassitant.infrastructure.vector;

import com.example.ragassitant.domain.model.DocumentChunk;
import com.example.ragassitant.domain.spi.VectorStorePort;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Component
public class SpringAiSimpleVectorAdapter implements VectorStorePort {

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

    @Override
    public void delete(String chunkId) {
        if (chunkId != null) vectorStore.delete(chunkId);
    }

    @Override
    public int deleteByDocumentId(String documentId) {
        if (documentId == null || documentId.isBlank()) return 0;
        int deleted = 0;
        try {
            List<DocumentChunk> allChunks = listAll(1000);
            for (DocumentChunk chunk : allChunks) {
                Object docIdMeta = chunk.getMetadata() == null ? null : chunk.getMetadata().get("documentId");
                if (documentId.equals(docIdMeta != null ? docIdMeta.toString() : null)) {
                    if (chunk.getId() != null) {
                        vectorStore.delete(chunk.getId());
                        deleted++;
                    }
                }
            }
        } catch (Exception e) {
            // 向量库为空时可能抛异常，忽略
        }
        return deleted;
    }

    @Override
    public int deleteAll() {
        int deleted = 0;
        try {
            List<DocumentChunk> allChunks = listAll(10000);
            for (DocumentChunk chunk : allChunks) {
                if (chunk.getId() != null) {
                    vectorStore.delete(chunk.getId());
                    deleted++;
                }
            }
        } catch (Exception e) {
            // 向量库为空时可能抛异常，忽略
        }
        return deleted;
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