package com.example.ragassitant.infrastructure.session;

import com.example.ragassitant.domain.spi.ConversationStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内会话历史存储。
 *
 * <p><b>单实例限制</b>：多副本部署时会话不共享，用户的下一次请求落到另一个副本就丢上下文。
 * 生产环境应换成 Spring AI 的 {@code ChatMemory} + JDBC/Redis 仓库实现；
 * 端口 {@link ConversationStorePort} 已经把这层隔开，替换不影响 application 层。
 *
 * <p>改造前有三个问题：
 * <ol>
 *   <li><b>用两个独立 Deque 拼问答对</b>（userMsgs / asstMsgs 各自裁剪）。一旦某轮只写入 user
 *       没写入 assistant，之后所有配对<b>永久错位</b>，第 N 轮的问会配上第 N+1 轮的答。
 *       现在改成一个 {@code Deque<Turn>}，一轮就是一个对象，不存在错位的可能。</li>
 *   <li><b>{@code getHistory(maxPairs)} 返回的是最旧的 N 对</b>（从下标 0 开始取），
 *       而调用方要的是「最近 N 轮」。当时被「队列上限恰好也是 10、调用方都要 ≥10 轮」掩盖着，
 *       属于一改上限就会暴露的隐藏 bug。</li>
 *   <li><b>{@code clearExpired()} 全项目没有任何调用方</b>，而且实现是
 *       {@code keySet().stream().skip(keep)} —— ConcurrentHashMap 的迭代顺序是任意的，
 *       等于随机驱逐而不是淘汰最老会话。现在按最后访问时间淘汰，并由 {@code @Scheduled} 真正触发。</li>
 * </ol>
 */
@Component
public class InMemoryConversationStore implements ConversationStorePort {

    private static final Logger log = LoggerFactory.getLogger(InMemoryConversationStore.class);

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** 单会话保留的最大轮次，超出丢弃最旧的。 */
    private final int maxTurnsPerSession;

    /** 会话空闲多久算过期。配 0 表示每次清理都不保留（主要供测试用）。 */
    private final long sessionTtlMillis;

    /** 会话总数上限，超出后按最后访问时间淘汰最老的。 */
    private final int maxSessions;

    public InMemoryConversationStore(
            @Value("${agent.memory-size:20}") int maxTurnsPerSession,
            @Value("${agent.session-ttl-minutes:120}") long sessionTtlMinutes,
            @Value("${agent.max-sessions:500}") int maxSessions) {
        this.maxTurnsPerSession = Math.max(1, maxTurnsPerSession);
        this.sessionTtlMillis = Math.max(0, sessionTtlMinutes) * 60_000L;
        this.maxSessions = Math.max(1, maxSessions);
        log.info("🔧 会话存储配置: 单会话最多 {} 轮, 空闲 {} 分钟过期, 最多 {} 个会话",
                this.maxTurnsPerSession, sessionTtlMinutes, this.maxSessions);
    }

    /** 一轮问答。assistant 为 null 表示这轮还没答完（不会被 getHistory 返回）。 */
    private static final class Turn {
        final String user;
        volatile String assistant;

        Turn(String user) {
            this.user = user == null ? "" : user;
        }
    }

    private static final class Session {
        final Deque<Turn> turns = new ConcurrentLinkedDeque<>();
        final AtomicLong lastAccess = new AtomicLong(System.currentTimeMillis());

        void touch() {
            lastAccess.set(System.currentTimeMillis());
        }
    }

    @Override
    public void saveUser(String sessionId, String userMessage) {
        if (sessionId == null || sessionId.isBlank()) return;
        Session session = sessions.computeIfAbsent(sessionId, k -> new Session());
        session.touch();
        synchronized (session) {
            session.turns.addLast(new Turn(userMessage));
            while (session.turns.size() > maxTurnsPerSession) session.turns.pollFirst();
        }
    }

    /**
     * 补齐最近一轮的助手回复。
     *
     * <p>找不到待补齐的轮次时（异常路径下 saveUser 没被调用过）补一条空提问的轮次，
     * 保证「一次 save 对应一轮」的不变式，绝不让后续配对整体错位。
     */
    @Override
    public void saveAssistant(String sessionId, String assistantMessage) {
        if (sessionId == null || sessionId.isBlank()) return;
        Session session = sessions.computeIfAbsent(sessionId, k -> new Session());
        session.touch();
        synchronized (session) {
            Turn last = session.turns.peekLast();
            if (last == null || last.assistant != null) {
                last = new Turn("");
                session.turns.addLast(last);
                while (session.turns.size() > maxTurnsPerSession) session.turns.pollFirst();
            }
            last.assistant = assistantMessage == null ? "" : assistantMessage;
        }
    }

    /**
     * 返回<b>最近</b> maxPairs 轮完整问答，按时间正序（最旧在前、最新在后）。
     *
     * <p>顺序很重要：调用方要么按序拼进 messages（AgentAppService），要么取尾部若干轮喂改写
     * （QueryRewriteService），两者都假定「最后一个元素是最近一轮」。
     */
    @Override
    public List<Map.Entry<String, String>> getHistory(String sessionId, int maxPairs) {
        List<Map.Entry<String, String>> result = new ArrayList<>();
        if (sessionId == null || sessionId.isBlank()) return result;
        Session session = sessions.get(sessionId);
        if (session == null) return result;
        session.touch();

        int limit = maxPairs <= 0 ? maxTurnsPerSession : maxPairs;
        List<Turn> complete = new ArrayList<>();
        for (Turn t : session.turns) {
            if (t.assistant != null) complete.add(t);
        }
        int from = Math.max(0, complete.size() - limit);
        for (Turn t : complete.subList(from, complete.size())) {
            result.add(new AbstractMap.SimpleEntry<>(t.user, t.assistant));
        }
        return result;
    }

    /**
     * 清理过期会话。由 {@code @Scheduled} 每 10 分钟触发 —— 需要主类上的 {@code @EnableScheduling}，
     * 少了它这个方法就又回到「有实现但永远不执行」的状态。
     */
    @Override
    @Scheduled(fixedDelayString = "${agent.session-cleanup-interval-ms:600000}")
    public void clearExpired() {
        long now = System.currentTimeMillis();
        int expired = 0;
        for (Map.Entry<String, Session> e : sessions.entrySet()) {
            if (now - e.getValue().lastAccess.get() >= sessionTtlMillis) {
                sessions.remove(e.getKey());
                expired++;
            }
        }

        // 容量兜底：即使都没到 TTL，也不能无上限增长。按最后访问时间淘汰最老的。
        int evicted = 0;
        if (sessions.size() > maxSessions) {
            List<Map.Entry<String, Session>> oldestFirst = new ArrayList<>(sessions.entrySet());
            oldestFirst.sort(Comparator.comparingLong(x -> x.getValue().lastAccess.get()));
            int overflow = sessions.size() - maxSessions;
            for (int i = 0; i < overflow && i < oldestFirst.size(); i++) {
                sessions.remove(oldestFirst.get(i).getKey());
                evicted++;
            }
        }

        if (expired > 0 || evicted > 0) {
            log.info("🧹 会话清理完成: 过期 {} 个, 超量淘汰 {} 个, 剩余 {} 个", expired, evicted, sessions.size());
        }
    }
}
