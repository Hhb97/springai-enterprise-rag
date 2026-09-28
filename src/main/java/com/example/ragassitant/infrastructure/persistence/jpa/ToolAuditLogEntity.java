package com.example.ragassitant.infrastructure.persistence.jpa;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具调用审计：谁、在什么时候、让 Agent 以什么参数调了什么工具、结果如何。
 *
 * <p>与 AgentTrace 的区别：trace 是为调试服务的（可丢弃、有保留期、异步写），
 * 审计是为追责服务的（写操作必须留痕、同步写、不随 trace 清理）。
 */
@Data
@Entity
@Table(name = "tool_audit_log", indexes = {
        @Index(name = "idx_audit_created", columnList = "createdAt"),
        @Index(name = "idx_audit_tool", columnList = "toolName")
})
public class ToolAuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 128)
    private String toolName;

    @Column(length = 16)
    private String risk;

    /** 调用来源：native（LLM 原生 tool_calls）/ registry（启发式、Plan、ReAct 等）/ approval（人工确认后执行）。 */
    @Column(length = 16)
    private String source;

    @Column(columnDefinition = "TEXT")
    private String arguments;

    @Column(length = 128)
    private String username;

    @Column(length = 128)
    private String sessionId;

    @Column(length = 64)
    private String traceId;

    /** OK / ERROR / DENIED / PENDING_APPROVAL / DUPLICATE。 */
    @Column(length = 32)
    private String outcome;

    @Column(columnDefinition = "TEXT")
    private String resultPreview;

    private Long durationMs;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
