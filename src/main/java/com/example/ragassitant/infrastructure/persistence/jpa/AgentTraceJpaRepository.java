package com.example.ragassitant.infrastructure.persistence.jpa;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AgentTraceJpaRepository extends JpaRepository<AgentTraceJpaEntity, Long> {

    List<AgentTraceJpaEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    List<AgentTraceJpaEntity> findBySessionIdAndRoundNoOrderByCreatedAtAsc(String sessionId, Integer roundNo);

    List<AgentTraceJpaEntity> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime since, Pageable pageable);

    List<AgentTraceJpaEntity> findByToolNameOrderByCreatedAtDesc(String toolName, Pageable pageable);

    long countByCreatedAtAfter(LocalDateTime since);
}