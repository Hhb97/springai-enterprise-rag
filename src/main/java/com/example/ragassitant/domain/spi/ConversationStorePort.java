package com.example.ragassitant.domain.spi;

import java.util.List;
import java.util.Map;

public interface ConversationStorePort {

    void saveUser(String sessionId, String userMessage);

    void saveAssistant(String sessionId, String assistantMessage);

    List<Map.Entry<String, String>> getHistory(String sessionId, int maxPairs);

    void clearExpired();

    /**
     * 各会话的滚动摘要（sessionId → 摘要内容），供审计页面查询验证。
     *
     * <p>默认返回空 Map：未实现滚动摘要的存储实现（如旧版
     * {@code InMemoryConversationStore}）无需任何改动。
     */
    default Map<String, String> getSummaries() {
        return Map.of();
    }

    /**
     * 所有存活会话当前窗口内的完整问答历史（sessionId → 按时间正序的 user/assistant 对），
     * 供审计页面直接查看"模型下次请求能带上的上下文到底是什么"。
     *
     * <p>默认返回空 Map，与 {@link #getSummaries()} 同理。
     */
    default Map<String, List<Map.Entry<String, String>>> getAllSessionHistories() {
        return Map.of();
    }
}