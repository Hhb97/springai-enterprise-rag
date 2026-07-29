package com.example.ragassitant.domain.spi;

import java.util.List;
import java.util.Map;

public interface ConversationStorePort {

    void saveUser(String sessionId, String userMessage);

    void saveAssistant(String sessionId, String assistantMessage);

    List<Map.Entry<String, String>> getHistory(String sessionId, int maxPairs);

    void clearExpired();
}