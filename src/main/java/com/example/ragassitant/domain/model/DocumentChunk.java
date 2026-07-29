package com.example.ragassitant.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunk {

    private String id;
    private String text;
    private Map<String, Object> metadata;
    private Double score;

    public DocumentChunk(String text, Map<String, Object> metadata) {
        this.text = text;
        this.metadata = metadata;
    }
}