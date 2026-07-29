package com.example.ragassitant.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagAnswer {

    private String answer;
    private List<SourceDocument> sources;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceDocument {
        private String title;
        private String fileName;
        private Double score;
    }
}