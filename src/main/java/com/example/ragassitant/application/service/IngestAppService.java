package com.example.ragassitant.application.service;

import com.example.ragassitant.domain.model.DocumentChunk;
import com.example.ragassitant.domain.spi.DocumentReaderPort;
import com.example.ragassitant.domain.spi.VectorStorePort;
import com.example.ragassitant.infrastructure.persistence.jpa.DocumentVersionJpaEntity;
import com.example.ragassitant.infrastructure.persistence.jpa.DocumentVersionJpaEntity.DocumentStatus;
import com.example.ragassitant.infrastructure.persistence.jpa.DocumentVersionJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Service
public class IngestAppService {

    private static final Logger log = LoggerFactory.getLogger(IngestAppService.class);

    private final VectorStorePort vectorStorePort;
    private final DocumentReaderPort documentReaderPort;
    private final DocumentVersionJpaRepository documentVersionRepository;

    public IngestAppService(VectorStorePort vectorStorePort,
                            DocumentReaderPort documentReaderPort,
                            DocumentVersionJpaRepository documentVersionRepository) {
        this.vectorStorePort = vectorStorePort;
        this.documentReaderPort = documentReaderPort;
        this.documentVersionRepository = documentVersionRepository;
    }

    public int ingestAll() throws IOException {
        log.info("🧹 清空向量库...");
        int deleted = vectorStorePort.deleteAll();
        log.info("✅ 已删除 {} 条历史向量", deleted);

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] mdResources = resolver.getResources("classpath:docs/**/*.md");
        Resource[] pdfResources = resolver.getResources("classpath:docs/**/*.pdf");
        Resource[] otherResources = resolver.getResources("classpath:docs/**/*.{doc,docx,xls,xlsx,txt}");

        List<DocumentChunk> allChunks = new ArrayList<>();
        for (Resource resource : mdResources) allChunks.addAll(readResourceChunks(resource, "markdown"));
        for (Resource resource : pdfResources) allChunks.addAll(readResourceChunks(resource, "pdf"));
        for (Resource resource : otherResources) allChunks.addAll(readResourceChunks(resource, "other"));

        List<DocumentVersionJpaEntity> activeDocs = documentVersionRepository.findByStatus(DocumentStatus.ACTIVE);
        for (DocumentVersionJpaEntity docVersion : activeDocs) {
            Path filePath = Paths.get(docVersion.getFilePath());
            if (Files.exists(filePath)) {
                List<DocumentChunk> chunks = readFileChunks(filePath.toString(), docVersion.getFileType(), docVersion.getFileName());
                chunks.forEach(c -> {
                    if (c.getMetadata() == null) c.setMetadata(new HashMap<>());
                    c.getMetadata().put("documentId", docVersion.getDocumentId());
                    c.getMetadata().put("version", docVersion.getVersion());
                    c.getMetadata().put("fileName", docVersion.getFileName());
                });
                allChunks.addAll(chunks);
            }
        }

        if (allChunks.isEmpty()) {
            log.warn("⚠️ 没有找到可处理的文档");
            return 0;
        }

        log.info("✅ 加载了 {} 个文档块，准备入库", allChunks.size());
        vectorStorePort.add(allChunks);
        log.info("✅ 向量库已构建，共 {} 条记录", allChunks.size());

        vectorStorePort.persist();
        return allChunks.size();
    }

    public int ingestDocument(Long versionId) throws IOException {
        DocumentVersionJpaEntity docVersion = documentVersionRepository.findById(versionId)
                .orElseThrow(() -> new RuntimeException("文档版本不存在: " + versionId));

        Path filePath = Paths.get(docVersion.getFilePath());
        if (!Files.exists(filePath)) {
            throw new RuntimeException("文件不存在: " + docVersion.getFilePath());
        }

        List<DocumentChunk> chunks = readFileChunks(docVersion.getFilePath(), docVersion.getFileType(), docVersion.getFileName());
        chunks.forEach(c -> {
            if (c.getMetadata() == null) c.setMetadata(new HashMap<>());
            c.getMetadata().put("documentId", docVersion.getDocumentId());
            c.getMetadata().put("version", docVersion.getVersion());
            c.getMetadata().put("fileName", docVersion.getFileName());
        });

        log.info("✅ 加载文档: {} v{}，共 {} 个块", docVersion.getFileName(), docVersion.getVersion(), chunks.size());

        log.info("📥 删除该文档的旧向量...");
        int deletedCount = vectorStorePort.deleteByDocumentId(docVersion.getDocumentId());
        log.info("📥 已删除 {} 个旧向量块", deletedCount);

        log.info("📥 正在添加 {} 个向量块到向量库...", chunks.size());
        vectorStorePort.add(chunks);
        log.info("📥 向量块添加完成");

        docVersion.setChunkCount(chunks.size());
        documentVersionRepository.save(docVersion);

        vectorStorePort.persist();
        log.info("✅ 文档版本 {} v{} 已入库，共 {} 条向量", docVersion.getDocumentId(), docVersion.getVersion(), chunks.size());
        return chunks.size();
    }

    private List<DocumentChunk> readResourceChunks(Resource resource, String fileType) {
        String fileName = resource.getFilename();
        try (InputStream is = resource.getInputStream()) {
            return readChunks(is, fileType, fileName);
        } catch (Exception e) {
            log.warn("⚠️ 读取资源文件失败: {}", fileName, e);
            return List.of();
        }
    }

    private List<DocumentChunk> readFileChunks(String filePath, String fileType, String fileName) {
        try (InputStream is = new FileInputStream(filePath)) {
            return readChunks(is, fileType, fileName);
        } catch (Exception e) {
            log.warn("⚠️ 读取文件失败: {}", filePath, e);
            return List.of();
        }
    }

    private List<DocumentChunk> readChunks(InputStream input, String fileType, String fileName) {
        List<String> texts = switch (fileType == null ? "other" : fileType.toLowerCase()) {
            case "markdown" -> documentReaderPort.readMarkdown(input, fileName);
            case "pdf" -> documentReaderPort.readPdf(input, fileName);
            default -> documentReaderPort.readGeneric(input, fileName);
        };
        List<DocumentChunk> chunks = new ArrayList<>(texts.size());
        for (String text : texts) {
            if (text == null || text.isBlank()) continue;
            DocumentChunk c = new DocumentChunk();
            c.setId(UUID.randomUUID().toString());
            c.setText(text);
            HashMap<String, Object> meta = new HashMap<>();
            meta.put("fileName", fileName);
            meta.put("title", fileName);
            c.setMetadata(meta);
            chunks.add(c);
        }
        return chunks;
    }
}