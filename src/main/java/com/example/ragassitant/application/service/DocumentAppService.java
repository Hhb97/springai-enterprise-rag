package com.example.ragassitant.application.service;

import com.example.ragassitant.common.exception.BizException;
import com.example.ragassitant.domain.spi.VectorStorePort;
import com.example.ragassitant.infrastructure.persistence.jpa.DocumentVersionJpaEntity;
import com.example.ragassitant.infrastructure.persistence.jpa.DocumentVersionJpaEntity.DocumentStatus;
import com.example.ragassitant.infrastructure.persistence.jpa.DocumentVersionJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DocumentAppService {

    private static final Logger log = LoggerFactory.getLogger(DocumentAppService.class);

    private final DocumentVersionJpaRepository repository;
    private final IngestAppService ingestAppService;
    private final VectorStorePort vectorStorePort;
    private final Path uploadPath;

    public DocumentAppService(DocumentVersionJpaRepository repository,
                              IngestAppService ingestAppService,
                              VectorStorePort vectorStorePort,
                              @Value("${storage.upload-dir:./uploads}") String uploadDir) throws IOException {
        this.repository = repository;
        this.ingestAppService = ingestAppService;
        this.vectorStorePort = vectorStorePort;
        this.uploadPath = Paths.get(uploadDir);
        Files.createDirectories(this.uploadPath);
    }

    public DocumentVersionJpaEntity uploadDocument(MultipartFile file, String createdBy) throws IOException {
        String documentId = UUID.randomUUID().toString();
        return saveNewVersion(documentId, file, createdBy);
    }

    public DocumentVersionJpaEntity updateDocument(String documentId, MultipartFile file, String createdBy) throws IOException {
        Optional<DocumentVersionJpaEntity> latestOpt = repository.findTopByDocumentIdOrderByVersionDesc(documentId);
        int newVersion = latestOpt.map(v -> v.getVersion() + 1).orElse(1);
        return saveVersion(documentId, newVersion, file, createdBy);
    }

    private DocumentVersionJpaEntity saveNewVersion(String documentId, MultipartFile file, String createdBy) throws IOException {
        return saveVersion(documentId, 1, file, createdBy);
    }

    private DocumentVersionJpaEntity saveVersion(String documentId, int version, MultipartFile file, String createdBy) throws IOException {
        String originalFileName = file.getOriginalFilename();
        String fileType = getFileType(originalFileName);
        String storedFileName = UUID.randomUUID() + "_v" + version + "_" + originalFileName;
        Path filePath = uploadPath.resolve(storedFileName);

        Files.copy(file.getInputStream(), filePath);

        DocumentVersionJpaEntity docVersion = new DocumentVersionJpaEntity();
        docVersion.setDocumentId(documentId);
        docVersion.setVersion(version);
        docVersion.setTitle(extractTitle(originalFileName));
        docVersion.setFileName(originalFileName);
        docVersion.setFilePath(filePath.toString());
        docVersion.setFileType(fileType);
        docVersion.setFileSize(file.getSize());
        docVersion.setStatus(DocumentStatus.DRAFT);
        docVersion.setCreatedBy(createdBy);

        DocumentVersionJpaEntity saved = repository.save(docVersion);
        log.info("✅ 文档版本已保存: documentId={}, version={}, fileName={}", documentId, version, originalFileName);
        return saved;
    }

    @Transactional
    public DocumentVersionJpaEntity publishDocument(Long versionId) {
        log.info("🔍 尝试发布版本: versionId={}", versionId);
        long count = repository.count();
        log.info("🔍 数据库中版本总数: {}", count);
        List<DocumentVersionJpaEntity> allVersions = repository.findAll();
        log.info("🔍 所有版本ID: {}", allVersions.stream().map(v -> v.getId()).toList());

        DocumentVersionJpaEntity docVersion = repository.findById(versionId)
                .orElseThrow(() -> new BizException("NOT_FOUND", "文档版本不存在: " + versionId));

        repository.findByDocumentIdOrderByVersionDesc(docVersion.getDocumentId())
                .forEach(v -> {
                    if (!v.getId().equals(versionId) && v.getStatus() == DocumentStatus.ACTIVE) {
                        v.setStatus(DocumentStatus.DRAFT);
                        repository.save(v);
                    }
                });

        docVersion.setStatus(DocumentStatus.ACTIVE);
        DocumentVersionJpaEntity saved = repository.save(docVersion);

        try {
            int n = ingestAppService.ingestDocument(versionId);
            log.info("✅ 文档版本 {} v{} 已自动入库到向量库，共 {} 条向量",
                    docVersion.getDocumentId(), docVersion.getVersion(), n);
        } catch (IOException e) {
            log.error("❌ 文档版本入库失败: {}", e.getMessage(), e);
        }

        return saved;
    }

    public DocumentVersionJpaEntity archiveDocument(Long versionId) {
        DocumentVersionJpaEntity docVersion = repository.findById(versionId)
                .orElseThrow(() -> new BizException("NOT_FOUND", "文档版本不存在: " + versionId));
        docVersion.setStatus(DocumentStatus.ARCHIVED);
        return repository.save(docVersion);
    }

    @Transactional
    public void deleteDocument(String documentId) {
        log.info("🔍 尝试删除文档: documentId={}", documentId);
        List<DocumentVersionJpaEntity> versions = repository.findByDocumentIdOrderByVersionDesc(documentId);
        log.info("🔍 找到 {} 个版本需要删除", versions.size());
        versions.forEach(v -> {
            try {
                Files.deleteIfExists(Paths.get(v.getFilePath()));
                log.info("✅ 删除文件: {}", v.getFilePath());
            } catch (IOException e) {
                log.warn("❌ 删除文件失败: {}", v.getFilePath(), e);
            }
        });
        repository.deleteByDocumentId(documentId);
        int deletedCount = vectorStorePort.deleteByDocumentId(documentId);
        log.info("✅ 已从向量库删除 {} 条相关向量", deletedCount);
        vectorStorePort.persist();
        log.info("✅ 文档已删除: documentId={}", documentId);
    }

    @Transactional
    public void deleteVersion(Long versionId) {
        log.info("🔍 尝试删除版本: versionId={}", versionId);
        DocumentVersionJpaEntity version = repository.findById(versionId)
                .orElseThrow(() -> new BizException("NOT_FOUND", "版本不存在: " + versionId));
        if (version.getStatus() == DocumentStatus.ACTIVE) {
            throw new BizException("不能删除活跃版本，请先归档或发布其他版本");
        }
        try {
            Files.deleteIfExists(Paths.get(version.getFilePath()));
            log.info("✅ 删除文件: {}", version.getFilePath());
        } catch (IOException e) {
            log.warn("❌ 删除文件失败: {}", version.getFilePath(), e);
        }
        repository.deleteById(versionId);
        log.info("✅ 版本已删除: versionId={}", versionId);
    }

    public Optional<DocumentVersionJpaEntity> getLatestVersion(String documentId) {
        return repository.findTopByDocumentIdOrderByVersionDesc(documentId);
    }

    public List<DocumentVersionJpaEntity> getDocumentHistory(String documentId) {
        return repository.findByDocumentIdOrderByVersionDesc(documentId);
    }

    public DocumentVersionJpaEntity getVersion(String documentId, Integer version) {
        return repository.findByDocumentIdAndVersion(documentId, version)
                .orElseThrow(() -> new BizException("NOT_FOUND", "文档版本不存在: " + documentId + " v" + version));
    }

    public List<DocumentVersionJpaEntity> listDocuments(DocumentStatus status) {
        if (status == null) return repository.findAll();
        return repository.findByStatus(status);
    }

    public List<DocumentVersionJpaEntity> searchDocuments(String keyword) {
        return repository.findByTitleContainingIgnoreCase(keyword);
    }

    private String getFileType(String fileName) {
        if (fileName == null) return "unknown";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".md")) return "markdown";
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return "word";
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return "excel";
        if (lower.endsWith(".txt")) return "text";
        return "other";
    }

    private String extractTitle(String fileName) {
        if (fileName == null) return "Untitled";
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }
}