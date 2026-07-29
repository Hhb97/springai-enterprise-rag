package com.example.ragassitant.interfaces.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponseDTO {

    private String answer;
    private List<SourceDoc> sources;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceDoc {
        private String content;
        private String metadata;
        private Double score;

        public SourceDoc(String content, String metadata) {
            this.content = content;
            this.metadata = metadata;
            this.score = 0.0;
        }
    }
}