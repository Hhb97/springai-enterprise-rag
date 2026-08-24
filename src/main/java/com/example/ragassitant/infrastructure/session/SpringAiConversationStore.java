package com.example.ragassitant.infrastructure.session;

import com.example.ragassitant.domain.spi.ConversationStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 Spring AI 2.0 官方 {@link ChatMemory} 抽象的会话历史存储。
 *
 * <p>【为什么重写】前实现 {@link InMemoryConversationStore} 是纯手搓的
 * ConcurrentHashMap + Deque，虽然修掉了配对错位等 bug，但仍是「非官方轮子」：
 * 官方早就有 {@code MessageWindowChatMemory}（滑动窗口裁剪）+
 * {@code ChatMemoryRepository}（存储抽象），窗口裁剪、消息配对这些事框架做得更可靠。
 * 现在内部全部委托给官方组件，对外仍实现 {@link ConversationStorePort}，
 * application 层零改动。
 *
 * <p><b>多副本部署</b>：当前用 {@link InMemoryChatMemoryRepository}（进程内），
 * 仍是单实例语义；要跨副本共享，只需把构造参数换成 {@code JdbcChatMemoryRepository}
 * （Spring AI 官方提供，H2/MySQL/PG 均有方言）—— 本类其余代码不用动，
 * 这正是官方 Repository 抽象的意义。TTL / 会话数上限的淘汰逻辑保留在本类
 * （官方 ChatMemory 不管生命周期），过期即调 {@code chatMemory.clear(id)}。
 */
@Component
public class SpringAiConversationStore implements ConversationStorePort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiConversationStore.class);

    /** 单会话保留的最大轮次，超出丢弃最旧的（一轮 = 一条 User + 一条 Assistant）。 */
    private final int maxTurnsPerSession;

    /** 会话空闲多久算过期。配 0 表示每次清理都不保留（主要供测试用）。 */
    private final long sessionTtlMillis;

    /** 会话总数上限，超出后按最后访问时间淘汰最老的。 */
    private final int maxSessions;

    /** 官方 ChatMemory：滑动窗口自动裁剪到 maxTurnsPerSession × 2 条消息。 */
    private final ChatMemory chatMemory;

    /** 仅用于 TTL / 容量淘汰的最后访问时间表（ChatMemory 不感知生命周期）。 */
    private final Map<String, AtomicLong> lastAccess = new ConcurrentHashMap<>();

    public SpringAiConversationStore(
            @Value("${agent.memory-size:20}") int maxTurnsPerSession,
            @Value("${agent.session-ttl-minutes:120}") long sessionTtlMinutes,
            @Value("${agent.max-sessions:500}") int maxSessions) {
        this.maxTurnsPerSession = Math.max(1, maxTurnsPerSession);
        this.sessionTtlMillis = Math.max(0, sessionTtlMinutes) * 60_000L;
        this.maxSessions = Math.max(1, maxSessions);
        // 窗口按「消息条数」算：一轮问答占两条，所以 ×2。
        // 若最后一条 user 还没等到 assistant 回复就被挤出窗口，getHistory 会跳过孤儿 user。
        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(this.maxTurnsPerSession * 2)
                .build();
        log.info("🔧 会话存储(Spring AI ChatMemory)配置: 单会话最多 {} 轮, 空闲 {} 分钟过期, 最多 {} 个会话",
                this.maxTurnsPerSession, sessionTtlMinutes, this.maxSessions);
    }

    private void touch(String sessionId) {
        lastAccess.computeIfAbsent(sessionId, k -> new AtomicLong(System.currentTimeMillis()))
                .set(System.currentTimeMillis());
    }

    @Override
    public void saveUser(String sessionId, String userMessage) {
        if (sessionId == null || sessionId.isBlank()) return;
        touch(sessionId);
        chatMemory.add(sessionId, new UserMessage(userMessage == null ? "" : userMessage));
    }

    /**
     * 补齐最近一轮的助手回复。
     *
     * <p>找不到待补齐的轮次时（异常路径下 saveUser 没被调用过）补一条空提问的 user 消息，
     * 保证「user → assistant」交替不变式，绝不让后续配对整体错位。
     */
    @Override
    public void saveAssistant(String sessionId, String assistantMessage) {
        if (sessionId == null || sessionId.isBlank()) return;
        touch(sessionId);
        List<Message> messages = chatMemory.get(sessionId);
        Message last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
        if (!(last instanceof UserMessage)) {
            chatMemory.add(sessionId, new UserMessage(""));
        }
        chatMemory.add(sessionId, new AssistantMessage(assistantMessage == null ? "" : assistantMessage));
    }

    /**
     * 返回<b>最近</b> maxPairs 轮完整问答，按时间正序（最旧在前、最新在后）。
     *
     * <p>只把「user 后面紧跟着 assistant」的消息视为完整一轮 —— 窗口裁剪可能留下
     * 尾部孤儿 user（还没来得及回复），直接跳过，与旧实现的 Turn.assistant != null 判定等价。
     */
    @Override
    public List<Map.Entry<String, String>> getHistory(String sessionId, int maxPairs) {
        List<Map.Entry<String, String>> result = new ArrayList<>();
        if (sessionId == null || sessionId.isBlank()) return result;
        if (!lastAccess.containsKey(sessionId)) return result;
        touch(sessionId);

        int limit = maxPairs <= 0 ? maxTurnsPerSession : maxPairs;
        List<Message> messages = chatMemory.get(sessionId);

        // 先收集所有完整配对，再取尾部 limit 对
        List<Map.Entry<String, String>> complete = new ArrayList<>();
        for (int i = 0; i < messages.size() - 1; i++) {
            if (messages.get(i) instanceof UserMessage u && messages.get(i + 1) instanceof AssistantMessage a) {
                complete.add(new AbstractMap.SimpleEntry<>(u.getText(), a.getText()));
                i++; // 跳过已配对的 assistant
            }
        }
        int from = Math.max(0, complete.size() - limit);
        result.addAll(complete.subList(from, complete.size()));
        return result;
    }

    /**
     * 清理过期会话。由 {@code @Scheduled} 每 10 分钟触发 —— 需要主类上的 {@code @EnableScheduling}。
     */
    @Override
    @Scheduled(fixedDelayString = "${agent.session-cleanup-interval-ms:600000}")
    public void clearExpired() {
        long now = System.currentTimeMillis();
        int expired = 0;
        for (Map.Entry<String, AtomicLong> e : lastAccess.entrySet()) {
            if (now - e.getValue().get() >= sessionTtlMillis) {
                removeSession(e.getKey());
                expired++;
            }
        }

        // 容量兜底：即使都没到 TTL，也不能无上限增长。按最后访问时间淘汰最老的。
        int evicted = 0;
        if (lastAccess.size() > maxSessions) {
            List<Map.Entry<String, AtomicLong>> oldestFirst = new ArrayList<>(lastAccess.entrySet());
            oldestFirst.sort(Comparator.comparingLong(x -> x.getValue().get()));
            int overflow = lastAccess.size() - maxSessions;
            for (int i = 0; i < overflow && i < oldestFirst.size(); i++) {
                removeSession(oldestFirst.get(i).getKey());
                evicted++;
            }
        }

        if (expired > 0 || evicted > 0) {
            log.info("🧹 会话清理完成: 过期 {} 个, 超量淘汰 {} 个, 剩余 {} 个", expired, evicted, lastAccess.size());
        }
    }

    private void removeSession(String sessionId) {
        try {
            chatMemory.clear(sessionId);
        } catch (Exception e) {
            log.warn("🧹 会话清理异常(忽略): sessionId={}, err={}", sessionId, e.toString());
        }
        lastAccess.remove(sessionId);
    }
}
