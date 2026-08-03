package com.example.ragassitant.infrastructure.persistence.jpa;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * 按 status 聚合某个阶段的记录数。
     *
     * <p>给影子路由用：{@code stage='SHADOW_ROUTE'} 的 status 存的就是比对判定
     * （AGREE / TOOL_DIFF / HEURISTIC_ONLY / ...），一条 SQL 就能算出一致率。
     *
     * @return 每行 {@code [status, count]}
     */
    @Query("select t.status, count(t) from AgentTraceJpaEntity t "
            + "where t.stage = :stage and t.createdAt > :since group by t.status")
    List<Object[]> countByStatusForStage(@Param("stage") String stage, @Param("since") LocalDateTime since);

    List<AgentTraceJpaEntity> findByStageAndStatusNotInOrderByCreatedAtDesc(
            String stage, List<String> excludedStatuses, Pageable pageable);
}