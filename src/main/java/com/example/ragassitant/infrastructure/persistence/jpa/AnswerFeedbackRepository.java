package com.example.ragassitant.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnswerFeedbackRepository extends JpaRepository<AnswerFeedbackEntity, Long> {

    List<AnswerFeedbackEntity> findTop200ByOrderByCreatedAtDesc();

    List<AnswerFeedbackEntity> findTop200ByRatingOrderByCreatedAtDesc(String rating);

    long countByRating(String rating);
}
