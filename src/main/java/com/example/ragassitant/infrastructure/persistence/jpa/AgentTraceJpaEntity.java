package com.example.ragassitant.infrastructure.persistence.jpa;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_traces", indexes = {
        @Index(name = "idx_session", columnList = "sessionId"),
        @Index(name = "idx_created", columnList = "createdAt")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentTraceJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", length = 128)
    private String sessionId;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "round_no")
    private Integer roundNo;

    @Column(name = "stage", length = 32)
    private String stage;

    @Column(name = "tool_name", length = 64)
    private String toolName;

    @Lob
    @Column(name = "tool_args", columnDefinition = "TEXT")
    private String toolArguments;

    @Lob
    @Column(name = "tool_result", columnDefinition = "TEXT")
    private String toolResult;

    @Lob
    @Column(name = "llm_input", columnDefinition = "TEXT")
    private String llmInput;

    @Lob
    @Column(name = "llm_output", columnDefinition = "TEXT")
    private String llmOutput;

    @Column(name = "heuristic_hit")
    private Boolean heuristicHit;

    @Column(name = "heuristic_strategy", length = 32)
    private String heuristicStrategy;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "status", length = 16)
    private String status;

    @Lob
    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        // 异步批量写入时，createdAt 在事件发生时就已设置；只有没设置时才用落库时间，
        // 否则同一请求内各阶段的先后顺序会被批量写入的时间抹平
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = "OK";
    }
}