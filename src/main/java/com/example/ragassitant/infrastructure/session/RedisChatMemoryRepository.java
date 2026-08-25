package com.example.ragassitant.infrastructure.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 基于原生 Redis 数据结构（String/List/Set）的 {@link ChatMemoryRepository} 实现。
 *
 * <p>【为什么不用官方 RedisChatMemoryRepository】它依赖 RediSearch 模块
 * （启动时 FT.CREATE 建索引、查询走 FT.SEARCH），只有 Redis Stack 或 Redis 8.0+
 * 才内置；普通 redis 7.x 执行 FT.CREATE 直接报 unknown command。
 * 会话记忆只需要四个操作（findConversationIds / findByConversationId / saveAll /
 * deleteByConversationId），用原生结构就能覆盖，且<b>任何 Redis 版本通用</b>。
 *
 * <p><b>存储结构</b>：
 * <pre>
 *   {prefix}msg:{conversationId}   LIST  按序存放 JSON 序列化的消息
 *   {prefix}convos                 SET   会话 id 索引（findConversationIds 用）
 *   {prefix}summary:{conversationId}  STRING  滚动摘要（由 SpringAiConversationStore 读写）
 * </pre>
 * 所有键统一设置 TTL（与会话空闲过期对齐），Redis 侧自动淘汰，重启不丢、多副本共享。
 *
 * <p>序列化只保留 type + text：会话记忆里只存 User/Assistant/System 三类纯文本消息，
 * 不含 ToolResponseMessage 等复杂类型（ToolCallingAdvisor 默认在循环外管理记忆，
 * 工具消息本来就不会进 ChatMemory）。
 */
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryRepository.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StringRedisTemplate redis;
    private final String msgKeyPrefix;
    private final String indexKey;
    private final Duration ttl;

    public RedisChatMemoryRepository(StringRedisTemplate redis, String keyPrefix, Duration ttl) {
        this.redis = redis;
        this.msgKeyPrefix = keyPrefix + "msg:";
        this.indexKey = keyPrefix + "convos";
        this.ttl = ttl;
        log.info("🔧 Redis 会话仓库就绪: prefix={}, ttl={}", keyPrefix, ttl);
    }

    public String summaryKey(String conversationId) {
        return msgKeyPrefix.replace("msg:", "summary:") + conversationId;
    }

    /** 摘要写入（供 SpringAiConversationStore 复用，带同样的 TTL）。 */
    public void saveSummary(String conversationId, String summary) {
        redis.opsForValue().set(summaryKey(conversationId), summary);
        applyTtl(summaryKey(conversationId));
    }

    /** 摘要读取；不存在返回 null。 */
    public String findSummary(String conversationId) {
        return redis.opsForValue().get(summaryKey(conversationId));
    }

    /** 全部摘要（供审计页面），KEYS 模式扫描——会话规模小（≤maxSessions）时足够用。 */
    public java.util.Map<String, String> findAllSummaries() {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        Set<String> keys = redis.keys(msgKeyPrefix.replace("msg:", "summary:") + "*");
        if (keys == null) return result;
        for (String key : new TreeSet<>(keys)) {
            String value = redis.opsForValue().get(key);
            if (value != null && !value.isBlank()) {
                result.put(key.substring(key.lastIndexOf(':') + 1), value);
            }
        }
        return result;
    }

    @Override
    public List<String> findConversationIds() {
        Set<String> members = redis.opsForSet().members(indexKey);
        return members == null ? List.of() : new ArrayList<>(new TreeSet<>(members));
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        List<String> raw = redis.opsForList().range(msgKey(conversationId), 0, -1);
        List<Message> messages = new ArrayList<>();
        if (raw == null) return messages;
        for (String json : raw) {
            Message m = deserialize(json);
            if (m != null) messages.add(m);
        }
        return messages;
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        String key = msgKey(conversationId);
        redis.delete(key);
        if (messages != null && !messages.isEmpty()) {
            List<String> json = new ArrayList<>(messages.size());
            for (Message m : messages) {
                String s = serialize(m);
                if (s != null) json.add(s);
            }
            redis.opsForList().rightPushAll(key, json);
        }
        redis.opsForSet().add(indexKey, conversationId);
        applyTtl(key);
        redis.expire(indexKey, ttl == null ? Duration.ofDays(7) : ttl);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        redis.delete(msgKey(conversationId));
        redis.opsForSet().remove(indexKey, conversationId);
        redis.delete(summaryKey(conversationId));
    }

    private String msgKey(String conversationId) {
        return msgKeyPrefix + conversationId;
    }

    private void applyTtl(String key) {
        if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
            redis.expire(key, ttl);
        }
    }

    private String serialize(Message m) {
        try {
            java.util.Map<String, String> obj = new java.util.LinkedHashMap<>();
            obj.put("type", m.getMessageType().name());
            obj.put("text", m.getText());
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("消息序列化失败(跳过): {}", e.toString());
            return null;
        }
    }

    private Message deserialize(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            MessageType type = MessageType.valueOf(node.get("type").asText());
            String text = node.has("text") ? node.get("text").asText() : "";
            return switch (type) {
                case USER -> new UserMessage(text);
                case ASSISTANT -> new AssistantMessage(text);
                case SYSTEM -> new SystemMessage(text);
                default -> null; // TOOL 等类型不进会话记忆
            };
        } catch (Exception e) {
            log.warn("消息反序列化失败(跳过): {}", e.toString());
            return null;
        }
    }
}
