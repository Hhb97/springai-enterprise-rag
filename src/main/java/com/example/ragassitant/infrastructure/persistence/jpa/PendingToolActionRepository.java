package com.example.ragassitant.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PendingToolActionRepository extends JpaRepository<PendingToolActionEntity, String> {

    List<PendingToolActionEntity> findByStatusOrderByCreatedAtDesc(PendingToolActionEntity.Status status);

    List<PendingToolActionEntity> findTop100ByOrderByCreatedAtDesc();
}
