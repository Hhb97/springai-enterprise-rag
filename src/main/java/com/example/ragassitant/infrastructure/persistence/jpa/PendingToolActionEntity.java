package com.example.ragassitant.infrastructure.persistence.jpa;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 待人工确认的危险操作。
 *
 * <p>Agent 想调用 DANGEROUS 级别的工具时不会直接执行，而是生成一条待确认记录并告知用户确认单号；
 * 有权限的人在管理端确认后才真正执行，结果写回本记录。
 */
@Data
@Entity
@Table(name = "pending_tool_action", indexes = {
        @Index(name = "idx_pending_status", columnList = "status")
})
public class PendingToolActionEntity {

    public enum Status { PENDING, EXECUTED, REJECTED, EXPIRED, FAILED }

    @Id
    @Column(length = 36)
    private String id;

    @Column(length = 128, nullable = false)
    private String toolName;

    @Column(columnDefinition = "TEXT")
    private String arguments;

    @Column(length = 128)
    private String sessionId;

    @Column(length = 128)
    private String requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private Status status;

    @Column(columnDefinition = "TEXT")
    private String result;

    @Column(length = 128)
    private String decidedBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;

    private LocalDateTime decidedAt;
}
