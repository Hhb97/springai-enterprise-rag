package com.example.ragassitant.domain.spi;

import java.util.List;

public interface LlmPort {

    String chat(List<String> systemPrompts, List<String> historyUser, List<String> historyAssistant, String currentUser);

    String chatWithRawMessages(List<MessageDTO> messages);

    default String chat(String systemPrompt, String userQuestion) {
        return chat(List.of(systemPrompt), List.of(), List.of(), userQuestion);
    }

    class MessageDTO {
        public final Role role;
        public final String content;

        public MessageDTO(Role role, String content) {
            this.role = role;
            this.content = content;
        }

        public enum Role { SYSTEM, USER, ASSISTANT }
    }
}