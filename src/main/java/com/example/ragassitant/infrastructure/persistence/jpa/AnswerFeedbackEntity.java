package com.example.ragassitant.infrastructure.persistence.jpa;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户对单条回答的 👍/👎 反馈。👎 样本是评测集最有价值的来源：它们是真实用户踩到的坑。 */
@Data
@Entity
@Table(name = "answer_feedback", indexes = {
        @Index(name = "idx_feedback_rating", columnList = "rating"),
        @Index(name = "idx_feedback_created", columnList = "createdAt")
})
public class AnswerFeedbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 128)
    private String sessionId;

    @Column(length = 64)
    private String traceId;

    @Column(length = 128)
    private String username;

    /** up / down */
    @Column(length = 8, nullable = false)
    private String rating;

    @Column(length = 32)
    private String mode;

    @Column(columnDefinition = "TEXT")
    private String question;

    @Column(columnDefinition = "TEXT")
    private String answer;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
