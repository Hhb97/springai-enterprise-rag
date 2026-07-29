package com.example.ragassitant.infrastructure.persistence.jpa;

import com.example.ragassitant.infrastructure.persistence.jpa.DocumentVersionJpaEntity.DocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentVersionJpaRepository extends JpaRepository<DocumentVersionJpaEntity, Long> {

    List<DocumentVersionJpaEntity> findByDocumentIdOrderByVersionDesc(String documentId);

    Optional<DocumentVersionJpaEntity> findByDocumentIdAndVersion(String documentId, Integer version);

    Optional<DocumentVersionJpaEntity> findTopByDocumentIdOrderByVersionDesc(String documentId);

    List<DocumentVersionJpaEntity> findByStatus(DocumentStatus status);

    List<DocumentVersionJpaEntity> findByTitleContainingIgnoreCase(String title);

    boolean existsByDocumentIdAndStatus(String documentId, DocumentStatus status);

    void deleteByDocumentId(String documentId);
}