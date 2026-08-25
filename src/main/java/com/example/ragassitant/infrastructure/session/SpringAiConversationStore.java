package com.example.ragassitant.infrastructure.session;

import com.example.ragassitant.domain.spi.ConversationStorePort;
import com.example.ragassitant.domain.spi.LlmPort;
import com.example.ragassitant.domain.spi.LlmPort.MessageDTO;
import com.example.ragassitant.domain.spi.LlmPort.MessageDTO.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 Spring AI 2.0 官方 {@link ChatMemory} 抽象的会话历史存储，带<b>滚动摘要</b>。
 *
 * <p>【为什么重写】前实现 {@link InMemoryConversationStore} 是纯手搓的
 * ConcurrentHashMap + Deque，虽然修掉了配对错位等 bug，但仍是「非官方轮子」：
 * 官方早就有 {@code MessageWindowChatMemory}（滑动窗口裁剪）+
 * {@code ChatMemoryRepository}（存储抽象），窗口裁剪、消息配对这些事框架做得更可靠。
 * 现在内部全部委托给官方组件，对外仍实现 {@link ConversationStorePort}，
 * application 层零改动。
 *
 * <p><b>滚动摘要（Rolling Summarization）</b>：官方窗口是硬裁剪——第 21 轮进来时
 * 最旧的一轮被永久丢弃，用户再问「总结整个会话」必然遗漏早期内容。现在在写入前
 * 检查即将溢出的部分：把将被挤出的完整轮次喂给<b>轻量 LLM 后端</b>
 * （复用 Query 改写的 {@code queryRewriteLlmPort}，改写和摘要是同类机械任务，
 * 只换提示词），压成几百字要点存入会话级摘要槽；{@link #getHistory} 把摘要作为
 * 第一对伪消息返回，各 AppService 无需感知。摘要失败（超时/异常/空返回）时静默
 * 保留旧摘要并照常裁剪——宁可丢摘要也不阻断主流程。
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

    /** 每个会话的滚动摘要（被窗口裁掉的旧轮次的要点），随会话清理一起删除。 */
    private final Map<String, String> sessionSummaries = new ConcurrentHashMap<>();

    /** 摘要专用轻量 LLM：复用 Query 改写的独立后端（改写与摘要是同类机械任务）。 */
    private final LlmPort summaryLlm;

    /** 滚动摘要开关。 */
    private final boolean summaryEnabled;

    /** 单次摘要喂给 LLM 的最大字符数，防止极端长轮次撑爆辅助模型上下文。 */
    private final int summaryMaxPromptChars;

    /** redis 持久化模式下的仓库（memory 模式为 null）；滚动摘要的读写也走它，重启不丢。 */
    private final RedisChatMemoryRepository redisRepository;

    public SpringAiConversationStore(
            @Value("${agent.memory-size:20}") int maxTurnsPerSession,
            @Value("${agent.session-ttl-minutes:120}") long sessionTtlMinutes,
            @Value("${agent.max-sessions:500}") int maxSessions,
            @Qualifier("queryRewriteLlmPort") LlmPort summaryLlm,
            @Value("${agent.memory-summary.enabled:true}") boolean summaryEnabled,
            @Value("${agent.memory-summary.max-prompt-chars:6000}") int summaryMaxPromptChars,
            @Value("${agent.memory.persistence:memory}") String persistence,
            org.springframework.data.redis.core.StringRedisTemplate redisTemplate) {
        this.maxTurnsPerSession = Math.max(1, maxTurnsPerSession);
        this.sessionTtlMillis = Math.max(0, sessionTtlMinutes) * 60_000L;
        this.maxSessions = Math.max(1, maxSessions);
        this.summaryLlm = summaryLlm;
        this.summaryEnabled = summaryEnabled;
        this.summaryMaxPromptChars = Math.max(1000, summaryMaxPromptChars);

        // 窗口按「消息条数」算：一轮问答占两条，所以 ×2。
        // 若最后一条 user 还没等到 assistant 回复就被挤出窗口，getHistory 会跳过孤儿 user。
        // 持久化模式：memory=进程内（重启丢失）；redis=存 Redis（重启恢复、多副本共享，
        //   键带原生 TTL 与 session-ttl-minutes 对齐，Redis 侧自动淘汰过期会话）。
        ChatMemoryRepository repository;
        if ("redis".equalsIgnoreCase(persistence)) {
            Duration ttl = this.sessionTtlMillis > 0 ? Duration.ofMillis(this.sessionTtlMillis) : null;
            this.redisRepository = new RedisChatMemoryRepository(redisTemplate, "rag:chat:", ttl);
            repository = this.redisRepository;
        } else {
            this.redisRepository = null;
            repository = new InMemoryChatMemoryRepository();
        }
        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(this.maxTurnsPerSession * 2)
                .build();
        log.info("🔧 会话存储(Spring AI ChatMemory)配置: 持久化={}, 单会话最多 {} 轮, 空闲 {} 分钟过期, 最多 {} 个会话, 滚动摘要={}",
                persistence.toLowerCase(), this.maxTurnsPerSession, sessionTtlMinutes, this.maxSessions,
                summaryEnabled ? "开启" : "关闭");
    }

    private void touch(String sessionId) {
        lastAccess.computeIfAbsent(sessionId, k -> new AtomicLong(System.currentTimeMillis()))
                .set(System.currentTimeMillis());
    }

    @Override
    public void saveUser(String sessionId, String userMessage) {
        if (sessionId == null || sessionId.isBlank()) return;
        touch(sessionId);
        compactBeforeAdd(sessionId, 1);
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
        boolean needPadding = !(last instanceof UserMessage);
        compactBeforeAdd(sessionId, needPadding ? 2 : 1);
        if (needPadding) {
            chatMemory.add(sessionId, new UserMessage(""));
        }
        chatMemory.add(sessionId, new AssistantMessage(assistantMessage == null ? "" : assistantMessage));
    }

    /**
     * 写入前的溢出检查与滚动摘要。
     *
     * <p>{@link MessageWindowChatMemory} 的裁剪发生在 add() 内部且拿不到被裁的消息，
     * 所以必须在写入前自己算：本次要加 incomingCount 条，窗口将溢出几条？把溢出覆盖的
     * <b>完整轮次</b>（从头数的 user→assistant 对）先摘要、再从窗口移除——移除方式是
     * clear 后重放剩余消息（ChatMemory 接口没有定点删除）。摘要内容合并进会话级
     * 摘要槽；LLM 失败时保留旧摘要照常裁剪，绝不抛异常阻断保存。
     */
    private void compactBeforeAdd(String sessionId, int incomingCount) {
        if (!summaryEnabled || summaryLlm == null) return;
        try {
            List<Message> msgs = chatMemory.get(sessionId);
            int overflow = msgs.size() + incomingCount - maxTurnsPerSession * 2;
            if (overflow <= 0) return;

            // 从头部收集「完整对」，直到覆盖 overflow 条为止（孤儿 user 不动）
            StringBuilder transcript = new StringBuilder();
            int covered = 0;
            int i = 0;
            while (i + 1 < msgs.size() && covered < overflow
                    && msgs.get(i) instanceof UserMessage u && msgs.get(i + 1) instanceof AssistantMessage a) {
                transcript.append("用户：").append(u.getText()).append('\n')
                        .append("助手：").append(a.getText()).append("\n\n");
                covered += 2;
                i += 2;
            }
            if (covered == 0) return;

            // 先摘要（用旧摘要 + 新被裁轮次合成新摘要）
            mergeSummary(sessionId, transcript.toString());

            // 再定点移除头部 covered 条：clear 后重放剩余消息
            List<Message> remaining = new ArrayList<>(msgs.subList(covered, msgs.size()));
            chatMemory.clear(sessionId);
            for (Message m : remaining) {
                chatMemory.add(sessionId, m);
            }
            String current = readSummary(sessionId);
            log.info("📝 会话滚动摘要: sessionId={}, 压缩 {} 条旧消息, 窗口剩余 {}, 累计摘要长度 {} 字",
                    sessionId, covered, remaining.size(), current == null ? 0 : current.length());
        } catch (Exception e) {
            // 摘要属于锦上添花，任何异常都不影响正常保存
            log.warn("📝 会话滚动摘要失败(忽略): sessionId={}, err={}", sessionId, e.toString());
        }
    }

    /** 摘要读取：redis 模式从 Redis 取（重启不丢），memory 模式从进程内 Map 取。 */
    private String readSummary(String sessionId) {
        return redisRepository != null ? redisRepository.findSummary(sessionId) : sessionSummaries.get(sessionId);
    }

    /** 摘要写入：按持久化模式分流。 */
    private void writeSummary(String sessionId, String summary) {
        if (redisRepository != null) {
            redisRepository.saveSummary(sessionId, summary);
        } else {
            sessionSummaries.put(sessionId, summary);
        }
    }

    /** 用轻量后端把「旧摘要 + 新被裁轮次」合成为新摘要；失败保留旧摘要。 */
    private void mergeSummary(String sessionId, String evictedTranscript) {
        String prev = readSummary(sessionId);
        String clipped = evictedTranscript.length() > summaryMaxPromptChars
                ? evictedTranscript.substring(evictedTranscript.length() - summaryMaxPromptChars)
                : evictedTranscript;
        String instruction = "你是对话摘要助手。请把下面的对话历史压缩成不超过300字的中文要点摘要，"
                + "保留用户问过的关键实体（如设备编号、日期、参数）和已给出的结论，按要点分条输出。\n\n"
                + (prev.isBlank() ? "" : "【此前累积的摘要】\n" + prev + "\n\n")
                + "【新近对话】\n" + clipped;
        long start = System.currentTimeMillis();
        String summary = summaryLlm.chatWithRawMessages(List.of(
                new MessageDTO(Role.SYSTEM, "你是简洁可靠的对话摘要助手，只输出摘要本身。"),
                new MessageDTO(Role.USER, instruction)));
        long ms = System.currentTimeMillis() - start;
        if (summary != null && !summary.isBlank()) {
            writeSummary(sessionId, summary.trim());
            log.info("📝 摘要更新完成: sessionId={}, 耗时 {}ms, 长度 {} 字", sessionId, ms, summary.trim().length());
        } else {
            log.warn("📝 摘要返回为空，保留旧摘要: sessionId={}, 耗时 {}ms", sessionId, ms);
        }
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
        // 【redis 持久化】应用重启后 lastAccess 表为空，但 Redis 里历史还在——
        // 只要窗口里有消息就视为已知会话并恢复追踪（摘要同样从 Redis 恢复）。
        List<Message> messages = chatMemory.get(sessionId);
        if (!lastAccess.containsKey(sessionId) && messages.isEmpty()) return result;
        touch(sessionId);

        int limit = maxPairs <= 0 ? maxTurnsPerSession : maxPairs;

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

        // 滚动摘要作为第一对伪消息返回：各 AppService 按普通 user→assistant 对拼进
        // messages，模型因此能"看到"已被窗口裁掉的早期内容要点。无摘要时行为与从前完全一致。
        String summary = readSummary(sessionId);
        if (summary != null && !summary.isBlank()) {
            result.add(0, new AbstractMap.SimpleEntry<>(
                    "[以下是本会话更早轮次的自动摘要，原文已超出上下文窗口，请结合它理解后续问题]",
                    summary));
        }
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

    @Override
    public Map<String, String> getSummaries() {
        return redisRepository != null ? redisRepository.findAllSummaries() : Map.copyOf(sessionSummaries);
    }

    @Override
    public Map<String, List<Map.Entry<String, String>>> getAllSessionHistories() {
        // redis 持久化模式：会话清单以 Redis 为准（应用重启后 lastAccess 表是空的）；
        // memory 模式：以 lastAccess 表为准。按最后访问时间倒序（最近活跃的排前面）。
        java.util.Set<String> ids = new java.util.LinkedHashSet<>(lastAccess.keySet());
        if (redisRepository != null) {
            ids.addAll(redisRepository.findConversationIds());
        }
        List<String> ordered = new ArrayList<>(ids);
        ordered.sort(Comparator.comparingLong((String id) ->
                lastAccess.containsKey(id) ? lastAccess.get(id).get() : 0L).reversed());

        Map<String, List<Map.Entry<String, String>>> result = new LinkedHashMap<>();
        for (String sid : ordered) {
            List<Message> messages = chatMemory.get(sid);
            List<Map.Entry<String, String>> pairs = new ArrayList<>();
            for (int i = 0; i < messages.size() - 1; i++) {
                if (messages.get(i) instanceof UserMessage u && messages.get(i + 1) instanceof AssistantMessage a) {
                    pairs.add(new AbstractMap.SimpleEntry<>(u.getText(), a.getText()));
                    i++;
                }
            }
            result.put(sid, pairs);
        }
        return result;
    }

    private void removeSession(String sessionId) {
        try {
            chatMemory.clear(sessionId);
        } catch (Exception e) {
            log.warn("🧹 会话清理异常(忽略): sessionId={}, err={}", sessionId, e.toString());
        }
        lastAccess.remove(sessionId);
        sessionSummaries.remove(sessionId);
    }
}
