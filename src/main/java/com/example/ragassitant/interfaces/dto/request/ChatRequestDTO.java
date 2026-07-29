package com.example.ragassitant.interfaces.dto.request;

import lombok.Data;

@Data
public class ChatRequestDTO {
    private String question;
    private Boolean enableHybridSearch;
    private Integer topK;
    private String sessionId;
}