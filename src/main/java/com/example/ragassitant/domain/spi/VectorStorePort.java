package com.example.ragassitant.domain.spi;

import com.example.ragassitant.domain.model.DocumentChunk;

import java.util.List;

public interface VectorStorePort {

    void add(List<DocumentChunk> chunks);

    List<DocumentChunk> similaritySearch(String query, int topK);

    List<DocumentChunk> listAll(int limit);

    void delete(String chunkId);

    int deleteByDocumentId(String documentId);

    int deleteAll();

    void persist();
}