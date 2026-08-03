package com.example.ragassitant.application.service;

import com.example.ragassitant.domain.model.ChunkMetaKeys;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class IngestAppService {

    private static final Logger log = LoggerFactory.getLogger(IngestAppService.class);

    private final VectorStorePort vectorStorePort;
    private final DocumentReaderPort documentReaderPort;
    private final DocumentVersionJpaRepository documentVersionRepository;
    private final RagAppService ragAppService;

    public IngestAppService(VectorStorePort vectorStorePort,
                            DocumentReaderPort documentReaderPort,
                            DocumentVersionJpaRepository documentVersionRepository,
                            RagAppService ragAppService) {
        this.vectorStorePort = vectorStorePort;
        this.documentReaderPort = documentReaderPort;
        this.documentVersionRepository = documentVersionRepository;
        this.ragAppService = ragAppService;
    }

    public int ingestAll() throws IOException {
        log.info("🧹 清空向量库...");
        int deleted = vectorStorePort.deleteAll();
        log.info("✅ 已删除 {} 条历史向量", deleted);

        // 单次枚举后按扩展名分派，保证每个文件只被读一次。
        // 改造前用三条 glob 分别取 md / pdf / {doc,docx,xls,xlsx,txt}，其中花括号写法是错的：
        // Spring 的 AntPathMatcher 不支持 shell 的 {a,b} 展开，会把 {...} 当成 URI 模板变量编译为 (.*)，
        // 于是 "*.{doc,docx,...}" 实际等价于 "*.任意后缀"，把 .md/.pdf 也匹配上 ——
        // 同一份文档既被 Markdown 解析器读一次、又被 Tika 读一次，重复内容直接挤占检索 topK。
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<DocumentChunk> allChunks = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        for (Resource resource : resolver.getResources("classpath:docs/**/*")) {
            if (!resource.isReadable()) continue;   // 跳过目录等不可读条目
            String fileName = resource.getFilename();
            String fileType = detectFileType(fileName);
            if (fileType == null) {
                log.debug("⏭️ 跳过不支持的文件: {}", fileName);
                continue;
            }
            // 同一物理资源出现在多个 classpath 根下时只处理一次
            String key = safeUri(resource);
            if (key != null && !visited.add(key)) {
                log.warn("⚠️ 跳过重复资源: {}", key);
                continue;
            }
            allChunks.addAll(readResourceChunks(resource, fileType));
        }

        List<DocumentVersionJpaEntity> activeDocs = documentVersionRepository.findByStatus(DocumentStatus.ACTIVE);
        for (DocumentVersionJpaEntity docVersion : activeDocs) {
            Path filePath = Paths.get(docVersion.getFilePath());
            if (Files.exists(filePath)) {
                List<DocumentChunk> chunks = readFileChunks(filePath.toString(), docVersion.getFileType(), docVersion.getFileName());
                chunks.forEach(c -> applyVersionMeta(c, docVersion));
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
        log.info("🔄 重新绑定向量检索器...");
        try {
            ragAppService.rebuildRetriever();
        } catch (Exception e) {
            log.warn("⚠️ 检索器重新绑定失败（不影响主流程，下次查询仍走旧检索器）: {}", e.getMessage());
        }
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
        chunks.forEach(c -> applyVersionMeta(c, docVersion));

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
        log.info("🔄 重新绑定向量检索器...");
        try {
            ragAppService.rebuildRetriever();
        } catch (Exception e) {
            log.warn("⚠️ 检索器重新绑定失败（不影响主流程）: {}", e.getMessage());
        }
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

    /**
     * 按扩展名判定解析通道，返回 null 表示不支持、应跳过。
     * <p>包级可见以便单测直接覆盖分派规则。
     */
    static String detectFileType(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        return switch (fileName.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "md", "markdown" -> "markdown";
            case "pdf" -> "pdf";
            case "doc", "docx", "xls", "xlsx", "txt" -> "other";
            default -> null;
        };
    }

    private String safeUri(Resource resource) {
        try {
            return resource.getURI().toString();
        } catch (IOException e) {
            return null;
        }
    }

    /** 补充版本管理相关的业务元数据（fileName 用版本表里的值覆盖，其余结构元数据保持解析器产出） */
    private void applyVersionMeta(DocumentChunk chunk, DocumentVersionJpaEntity docVersion) {
        if (chunk.getMetadata() == null) chunk.setMetadata(new HashMap<>());
        chunk.getMetadata().put(ChunkMetaKeys.DOCUMENT_ID, docVersion.getDocumentId());
        chunk.getMetadata().put(ChunkMetaKeys.VERSION, docVersion.getVersion());
        chunk.getMetadata().put(ChunkMetaKeys.FILE_NAME, docVersion.getFileName());
    }

    /**
     * 直接透传解析器产出的 chunk（含 headingPath / title / pageNumber 等结构元数据）。
     * 改造前这里会用 fileName 覆盖 title，导致全库 title 同值、检索侧的 title 加权形同虚设。
     */
    private List<DocumentChunk> readChunks(InputStream input, String fileType, String fileName) {
        List<DocumentChunk> chunks = switch (fileType == null ? "other" : fileType.toLowerCase()) {
            case "markdown" -> documentReaderPort.readMarkdown(input, fileName);
            case "pdf" -> documentReaderPort.readPdf(input, fileName);
            default -> documentReaderPort.readGeneric(input, fileName);
        };
        List<DocumentChunk> result = new ArrayList<>(chunks.size());
        for (DocumentChunk c : chunks) {
            if (c == null || c.getText() == null || c.getText().isBlank()) continue;
            if (c.getId() == null) c.setId(UUID.randomUUID().toString());
            if (c.getMetadata() == null) c.setMetadata(new HashMap<>());
            c.getMetadata().putIfAbsent(ChunkMetaKeys.FILE_NAME, fileName);
            result.add(c);
        }
        return result;
    }
}