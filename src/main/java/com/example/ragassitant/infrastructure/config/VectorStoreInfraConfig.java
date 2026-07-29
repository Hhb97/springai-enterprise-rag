package com.example.ragassitant.infrastructure.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.util.List;

@Configuration
public class VectorStoreInfraConfig {

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel,
                                   @Value("${storage.upload-dir:./uploads}") String uploadDir) {
        File vectorStoreFile = new File(uploadDir, "vectorstore.json");
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();

        if (vectorStoreFile.exists() && vectorStoreFile.length() > 2) {
            try {
                vectorStore.load(vectorStoreFile);
                long docCount = vectorStore.similaritySearch(SearchRequest.builder().query("").topK(1).build()).size();
                System.out.println("✅ 已从文件加载向量库: " + vectorStoreFile.getAbsolutePath() + ", 文档数: " + docCount);
            } catch (Exception e) {
                System.err.println("❌ 加载向量库文件失败: " + e.getMessage());
            }
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                SearchRequest emptyRequest = SearchRequest.builder().query("").topK(1).build();
                List<Document> docs = vectorStore.similaritySearch(emptyRequest);

                if (docs != null && !docs.isEmpty()) {
                    vectorStore.save(vectorStoreFile);
                    System.out.println("✅ 向量库已保存到文件: " + vectorStoreFile.getAbsolutePath());
                } else {
                    System.out.println("ℹ️ 向量库为空，跳过保存");
                }
            } catch (Exception e) {
                System.err.println("❌ 保存向量库失败: " + e.getMessage());
            }
        }));

        return vectorStore;
    }
}