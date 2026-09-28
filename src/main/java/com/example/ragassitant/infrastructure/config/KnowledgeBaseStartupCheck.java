package com.example.ragassitant.infrastructure.config;

import com.example.ragassitant.infrastructure.persistence.jpa.DocumentVersionJpaEntity;
import com.example.ragassitant.infrastructure.persistence.jpa.DocumentVersionJpaEntity.DocumentStatus;
import com.example.ragassitant.infrastructure.persistence.jpa.DocumentVersionJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

/**
 * 启动时检查知识库入库状态。
 *
 * <p>【为什么需要】线上排查召回率时发现：向量库里只有 3 个 chunk（1387 字符），
 * 而磁盘上的语料有 11402 字符 —— 最重要的电能质量文档根本没入库，相关问题召回率为 0。
 * 原因是上传的文档停在 DRAFT 状态：{@code ingestAll} 只索引 ACTIVE 版本，DRAFT 静默跳过。
 * 这类问题在任何日志里都不会报错，只会表现为「模型答不上来」。
 *
 * <p>只查数据库状态和向量库文件大小，不调用 embedding 模型（启动时模型服务可能还没就绪）。
 */
@Component
public class KnowledgeBaseStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseStartupCheck.class);

    private final DocumentVersionJpaRepository repository;
    private final String uploadDir;

    public KnowledgeBaseStartupCheck(DocumentVersionJpaRepository repository,
                                     @Value("${storage.upload-dir:./uploads}") String uploadDir) {
        this.repository = repository;
        this.uploadDir = uploadDir;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        try {
            List<DocumentVersionJpaEntity> drafts = repository.findByStatus(DocumentStatus.DRAFT);
            List<DocumentVersionJpaEntity> active = repository.findByStatus(DocumentStatus.ACTIVE);
            File store = new File(uploadDir, "vectorstore.json");
            long storeBytes = store.exists() ? store.length() : 0;

            log.info("📚 知识库状态: ACTIVE 文档 {} 份，DRAFT 文档 {} 份，向量库文件 {} KB",
                    active.size(), drafts.size(), storeBytes / 1024);

            if (!drafts.isEmpty()) {
                log.warn("📚 ⚠️ 有 {} 份文档处于 DRAFT 状态，【不会被检索到】。发布后才会入库：" +
                        "POST /api/docs/versions/<versionId>/publish", drafts.size());
                drafts.forEach(d -> log.warn("   · versionId={} 《{}》 v{}", d.getId(), d.getFileName(), d.getVersion()));
            }
            if (!active.isEmpty() && storeBytes < 2) {
                log.warn("📚 ⚠️ 有 ACTIVE 文档但向量库为空，请执行 POST /ai/ingest 重建索引");
            }
        } catch (Exception e) {
            log.warn("📚 知识库状态检查失败（不影响启动）: {}", e.getMessage());
        }
    }
}
