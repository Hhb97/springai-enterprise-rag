package com.example.ragassitant.interfaces.rest;

import com.example.ragassitant.application.service.DocumentAppService;
import com.example.ragassitant.application.service.IngestAppService;
import com.example.ragassitant.infrastructure.persistence.jpa.DocumentVersionJpaEntity;
import com.example.ragassitant.infrastructure.persistence.jpa.DocumentVersionJpaEntity.DocumentStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/docs")
public class DocumentController {

    private final DocumentAppService documentAppService;
    private final IngestAppService ingestAppService;

    public DocumentController(DocumentAppService documentAppService,
                               IngestAppService ingestAppService) {
        this.documentAppService = documentAppService;
        this.ingestAppService = ingestAppService;
    }

    @PostMapping("/upload")
    public ResponseEntity<DocumentVersionJpaEntity> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "createdBy", defaultValue = "system") String createdBy) {
        try {
            DocumentVersionJpaEntity saved = documentAppService.uploadDocument(file, createdBy);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/{documentId}/update")
    public ResponseEntity<DocumentVersionJpaEntity> updateDocument(
            @PathVariable String documentId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "createdBy", defaultValue = "system") String createdBy) {
        try {
            DocumentVersionJpaEntity saved = documentAppService.updateDocument(documentId, file, createdBy);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/versions/{versionId}/publish")
    public ResponseEntity<?> publishDocument(@PathVariable Long versionId) {
        try {
            DocumentVersionJpaEntity published = documentAppService.publishDocument(versionId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "发布成功并已入库",
                    "data", published
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.status(404).body(Map.of(
                    "success", false,
                    "message", "版本不存在: ID=" + versionId,
                    "hint", "请先上传文档，然后再发布"
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "发布失败: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/versions/{versionId}/archive")
    public ResponseEntity<DocumentVersionJpaEntity> archiveDocument(@PathVariable Long versionId) {
        try {
            DocumentVersionJpaEntity archived = documentAppService.archiveDocument(versionId);
            return ResponseEntity.ok(archived);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 单文档加入知识库（前端 docs.html 「加入知识库」按钮）
     * 对指定已发布/归档版本做重新切分 + 向量化，重新替换掉该文档的旧向量。
     */
    @PostMapping("/versions/{versionId}/add-to-kb")
    public ResponseEntity<?> addToKnowledgeBase(@PathVariable Long versionId) {
        try {
            int chunks = ingestAppService.ingestDocument(versionId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "已入库 " + chunks + " 个向量块",
                    "chunkCount", chunks
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.status(404).body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "入库失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 重建整个知识库（前端 docs.html 「重建知识库」按钮）
     * 清空向量库 → 重新切分 classpath:docs 和所有 ACTIVE 版本文档 → 入库 → 刷新检索器。
     */
    @PostMapping("/ingest-all")
    public ResponseEntity<?> rebuildKnowledgeBase() {
        try {
            int chunks = ingestAppService.ingestAll();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "知识库已重建，共 " + chunks + " 个向量块",
                    "chunkCount", chunks
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "重建失败: " + e.getMessage()
            ));
        }
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<?> deleteDocument(@PathVariable String documentId) {
        try {
            documentAppService.deleteDocument(documentId);
            return ResponseEntity.ok(Map.of("success", true, "message", "文档已删除（包含所有版本）"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "删除失败: " + e.getMessage()
            ));
        }
    }

    @DeleteMapping("/versions/{versionId}")
    public ResponseEntity<?> deleteVersion(@PathVariable Long versionId) {
        try {
            documentAppService.deleteVersion(versionId);
            return ResponseEntity.ok(Map.of("success", true, "message", "版本已删除"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(404).body(Map.of(
                    "success", false,
                    "message", "版本不存在: " + versionId
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "删除失败: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<DocumentVersionJpaEntity> getLatestVersion(@PathVariable String documentId) {
        return documentAppService.getLatestVersion(documentId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{documentId}/versions")
    public ResponseEntity<List<DocumentVersionJpaEntity>> getDocumentHistory(@PathVariable String documentId) {
        return ResponseEntity.ok(documentAppService.getDocumentHistory(documentId));
    }

    @GetMapping("/{documentId}/versions/{version}")
    public ResponseEntity<DocumentVersionJpaEntity> getVersion(@PathVariable String documentId,
                                                               @PathVariable Integer version) {
        try {
            DocumentVersionJpaEntity docVersion = documentAppService.getVersion(documentId, version);
            return ResponseEntity.ok(docVersion);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/list")
    public ResponseEntity<List<DocumentVersionJpaEntity>> listDocuments(
            @RequestParam(value = "status", required = false) DocumentStatus status) {
        return ResponseEntity.ok(documentAppService.listDocuments(status));
    }

    @GetMapping("/search")
    public ResponseEntity<List<DocumentVersionJpaEntity>> searchDocuments(@RequestParam("keyword") String keyword) {
        return ResponseEntity.ok(documentAppService.searchDocuments(keyword));
    }
}