package com.example.ragassitant.infrastructure.llm;

import com.example.ragassitant.domain.spi.LlmPort;
import com.example.ragassitant.domain.spi.LlmPort.MessageDTO.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SpringAiOllamaAdapter implements LlmPort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiOllamaAdapter.class);
    private final ChatClient chatClient;

    public SpringAiOllamaAdapter(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String chat(List<String> systemPrompts, List<String> historyUser, List<String> historyAssistant, String currentUser) {
        try {
            List<Message> messages = new ArrayList<>();
            if (systemPrompts != null) {
                for (String sp : systemPrompts) {
                    if (sp != null && !sp.isBlank()) messages.add(new SystemMessage(sp));
                }
            }
            int pairs = Math.min(historyUser == null ? 0 : historyUser.size(),
                                 historyAssistant == null ? 0 : historyAssistant.size());
            for (int i = 0; i < pairs; i++) {
                messages.add(new UserMessage(historyUser.get(i)));
                messages.add(new AssistantMessage(historyAssistant.get(i)));
            }
            if (currentUser != null) messages.add(new UserMessage(currentUser));

            return chatClient.prompt().messages(messages).call().content();
        } catch (Exception e) {
            log.error("❌ LLM 调用失败", e);
            return null;
        }
    }

    @Override
    public String chatWithRawMessages(List<MessageDTO> messages) {
        try {
            List<Message> springMessages = new ArrayList<>();
            if (messages != null) {
                for (MessageDTO dto : messages) {
                    if (dto == null || dto.content == null) continue;
                    switch (dto.role == null ? Role.USER : dto.role) {
                        case SYSTEM -> springMessages.add(new SystemMessage(dto.content));
                        case ASSISTANT -> springMessages.add(new AssistantMessage(dto.content));
                        case USER -> springMessages.add(new UserMessage(dto.content));
                    }
                }
            }
            return chatClient.prompt().messages(springMessages).call().content();
        } catch (Exception e) {
            log.error("❌ LLM RawMessages 调用失败", e);
            return null;
        }
    }
}