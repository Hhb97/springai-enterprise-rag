package com.example.ragassitant.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ToolAuditLogRepository extends JpaRepository<ToolAuditLogEntity, Long> {

    List<ToolAuditLogEntity> findTop100ByOrderByCreatedAtDesc();
}
