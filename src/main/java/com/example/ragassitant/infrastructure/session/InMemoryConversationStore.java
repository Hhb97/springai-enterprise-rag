package com.example.ragassitant.infrastructure.session;

import com.example.ragassitant.domain.spi.ConversationStorePort;
import org.springframework.stereotype.Component;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryConversationStore implements ConversationStorePort {

    private static final int DEFAULT_MAX_PAIRS = 10;

    private final Map<String, Deque<String>> userMsgs = new ConcurrentHashMap<>();
    private final Map<String, Deque<String>> asstMsgs = new ConcurrentHashMap<>();

    @Override
    public void saveUser(String sessionId, String userMessage) {
        if (sessionId == null || sessionId.isBlank()) return;
        Deque<String> q = userMsgs.computeIfAbsent(sessionId, k -> new LinkedList<>());
        synchronized (q) {
            q.addLast(userMessage == null ? "" : userMessage);
            while (q.size() > DEFAULT_MAX_PAIRS) q.removeFirst();
        }
    }

    @Override
    public void saveAssistant(String sessionId, String assistantMessage) {
        if (sessionId == null || sessionId.isBlank()) return;
        Deque<String> q = asstMsgs.computeIfAbsent(sessionId, k -> new LinkedList<>());
        synchronized (q) {
            q.addLast(assistantMessage == null ? "" : assistantMessage);
            while (q.size() > DEFAULT_MAX_PAIRS) q.removeFirst();
        }
    }

    @Override
    public List<Map.Entry<String, String>> getHistory(String sessionId, int maxPairs) {
        List<Map.Entry<String, String>> result = new ArrayList<>();
        if (sessionId == null || sessionId.isBlank()) return result;
        Deque<String> u = userMsgs.get(sessionId);
        Deque<String> a = asstMsgs.get(sessionId);
        if (u == null || a == null) return result;
        int pairs = Math.min(Math.min(maxPairs <= 0 ? DEFAULT_MAX_PAIRS : maxPairs, u.size()), a.size());
        List<String> ul = new ArrayList<>(u);
        List<String> al = new ArrayList<>(a);
        for (int i = 0; i < pairs; i++) {
            result.add(new AbstractMap.SimpleEntry<>(ul.get(i), al.get(i)));
        }
        return result;
    }

    @Override
    public void clearExpired() {
        // 简单实现：保留最近 100 个 session
        int keep = 100;
        if (userMsgs.size() > keep * 2) {
            userMsgs.keySet().stream().skip(keep).toList().forEach(userMsgs::remove);
            asstMsgs.keySet().stream().skip(keep).toList().forEach(asstMsgs::remove);
        }
    }
}