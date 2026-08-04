package com.example.ragassitant.infrastructure.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 会话历史存储的回归测试。
 *
 * <p>改造前这个类有三个问题，每一个都会静默污染多轮对话，且都不会报错：
 * <ol>
 *   <li>用两个独立 Deque 分别存问和答 → 缺一次 saveAssistant 就永久错位；</li>
 *   <li>getHistory(maxPairs) 返回的是<b>最旧</b>的 N 对（从下标 0 取），而调用方要的是最近 N 轮；</li>
 *   <li>clearExpired() 没有任何调用方，实现还是按 ConcurrentHashMap 的任意迭代顺序驱逐。</li>
 * </ol>
 */
class InMemoryConversationStoreTest {

    /** 默认参数：单会话 20 轮、TTL 120 分钟、最多 500 会话 */
    private static InMemoryConversationStore store() {
        return new InMemoryConversationStore(20, 120, 500);
    }

    private static void turn(InMemoryConversationStore store, String sessionId, String q, String a) {
        store.saveUser(sessionId, q);
        store.saveAssistant(sessionId, a);
    }

    // ==================== 顺序与配对 ====================

    @Test
    @DisplayName("getHistory 返回最近 N 轮，而不是最旧 N 轮")
    void returnsMostRecentTurns() {
        InMemoryConversationStore store = store();
        for (int i = 1; i <= 5; i++) {
            turn(store, "s1", "问题" + i, "回答" + i);
        }

        List<Map.Entry<String, String>> history = store.getHistory("s1", 2);

        assertEquals(2, history.size());
        assertEquals("问题4", history.get(0).getKey(), "返回的是最旧的几轮，多轮追问会拿着过时上下文做改写");
        assertEquals("问题5", history.get(1).getKey());
    }

    @Test
    @DisplayName("按时间正序返回：最后一个元素必须是最新一轮")
    void ordersChronologically() {
        InMemoryConversationStore store = store();
        turn(store, "s1", "第一轮", "答一");
        turn(store, "s1", "第二轮", "答二");
        turn(store, "s1", "第三轮", "答三");

        List<Map.Entry<String, String>> history = store.getHistory("s1", 10);

        // QueryRewriteService 取尾部若干轮、AgentAppService 顺序拼 messages，都假定最后一个是最近一轮
        assertEquals("第一轮", history.get(0).getKey());
        assertEquals("第三轮", history.get(history.size() - 1).getKey());
    }

    @Test
    @DisplayName("问答严格配对，不会串位")
    void pairsQuestionWithItsOwnAnswer() {
        InMemoryConversationStore store = store();
        turn(store, "s1", "列出所有设备", "共 8 台设备");
        turn(store, "s1", "你好", "您好，我是运维助手");

        List<Map.Entry<String, String>> history = store.getHistory("s1", 10);

        assertEquals("共 8 台设备", history.get(0).getValue());
        assertEquals("您好，我是运维助手", history.get(1).getValue());
    }

    @Test
    @DisplayName("只写入了问、还没写入答的轮次不返回（避免半截上下文进提示词）")
    void skipsIncompleteTurn() {
        InMemoryConversationStore store = store();
        turn(store, "s1", "已完成的问题", "已完成的回答");
        store.saveUser("s1", "正在处理中的问题");

        List<Map.Entry<String, String>> history = store.getHistory("s1", 10);

        assertEquals(1, history.size());
        assertEquals("已完成的问题", history.get(0).getKey());
    }

    @Test
    @DisplayName("缺失 saveUser 的异常路径不会让后续所有配对整体错位")
    void missingSaveUserDoesNotShiftLaterPairs() {
        InMemoryConversationStore store = store();
        // 模拟异常路径：只落了助手回复，没有对应的用户提问
        store.saveAssistant("s1", "孤立的回答");
        turn(store, "s1", "正常的问题", "正常的回答");

        List<Map.Entry<String, String>> history = store.getHistory("s1", 10);

        // 旧实现（两个独立队列）在这里会把「正常的问题」配到「孤立的回答」上，并且往后每一轮都错位
        Map.Entry<String, String> last = history.get(history.size() - 1);
        assertEquals("正常的问题", last.getKey());
        assertEquals("正常的回答", last.getValue());
    }

    @Test
    @DisplayName("超过单会话轮次上限时丢弃最旧的轮次")
    void dropsOldestBeyondLimit() {
        InMemoryConversationStore store = new InMemoryConversationStore(3, 120, 500);
        for (int i = 1; i <= 5; i++) {
            turn(store, "s1", "问题" + i, "回答" + i);
        }

        List<Map.Entry<String, String>> history = store.getHistory("s1", 10);

        assertEquals(3, history.size());
        assertEquals("问题3", history.get(0).getKey());
        assertEquals("问题5", history.get(2).getKey());
    }

    @Test
    @DisplayName("会话之间互不干扰")
    void sessionsAreIsolated() {
        InMemoryConversationStore store = store();
        turn(store, "s1", "会话一的问题", "会话一的回答");
        turn(store, "s2", "会话二的问题", "会话二的回答");

        assertEquals("会话一的问题", store.getHistory("s1", 10).get(0).getKey());
        assertEquals("会话二的问题", store.getHistory("s2", 10).get(0).getKey());
    }

    @Test
    @DisplayName("空 sessionId 或不存在的会话返回空列表，不抛异常")
    void blankOrUnknownSessionReturnsEmpty() {
        InMemoryConversationStore store = store();
        turn(store, "s1", "问题", "回答");

        assertTrue(store.getHistory(null, 10).isEmpty());
        assertTrue(store.getHistory("  ", 10).isEmpty());
        assertTrue(store.getHistory("不存在的会话", 10).isEmpty());
        assertDoesNotThrow(() -> store.saveUser(null, "x"));
        assertDoesNotThrow(() -> store.saveAssistant("", "x"));
    }

    // ==================== 清理 ====================

    @Test
    @DisplayName("TTL 到期的会话会被清掉")
    void clearsExpiredSessions() {
        // ttl=0 → 每次清理都视为过期，避免测试里睡等
        InMemoryConversationStore store = new InMemoryConversationStore(20, 0, 500);
        turn(store, "s1", "问题", "回答");

        store.clearExpired();

        assertTrue(store.getHistory("s1", 10).isEmpty(), "过期会话没被清理，长期运行会一直涨");
    }

    @Test
    @DisplayName("未过期的会话不会被清掉")
    void keepsFreshSessions() {
        InMemoryConversationStore store = store();
        turn(store, "s1", "问题", "回答");

        store.clearExpired();

        assertEquals(1, store.getHistory("s1", 10).size());
    }

    @Test
    @DisplayName("会话数超量时淘汰最久未访问的，而不是随机淘汰")
    void evictsLeastRecentlyUsedOnOverflow() throws InterruptedException {
        // maxSessions=2，TTL 很长（不让 TTL 规则参与），构造 3 个会话触发容量兜底
        InMemoryConversationStore store = new InMemoryConversationStore(20, 600, 2);
        turn(store, "old", "最老的问题", "答");
        Thread.sleep(5);
        turn(store, "mid", "中间的问题", "答");
        Thread.sleep(5);
        turn(store, "new", "最新的问题", "答");

        store.clearExpired();

        // 旧实现用 keySet().stream().skip(keep)，ConcurrentHashMap 迭代顺序任意 → 淘汰的是随机会话
        assertTrue(store.getHistory("old", 10).isEmpty(), "最久未访问的会话应被淘汰");
        assertFalse(store.getHistory("new", 10).isEmpty(), "最近访问过的会话不该被淘汰");
    }

    @Test
    @DisplayName("读一次会话就刷新它的活跃时间，避免正在用的会话被淘汰")
    void readingRefreshesLastAccess() throws InterruptedException {
        InMemoryConversationStore store = new InMemoryConversationStore(20, 600, 2);
        turn(store, "a", "问题A", "答A");
        Thread.sleep(5);
        turn(store, "b", "问题B", "答B");
        Thread.sleep(5);
        store.getHistory("a", 10);          // a 重新变成最近活跃
        Thread.sleep(5);
        turn(store, "c", "问题C", "答C");

        store.clearExpired();

        assertFalse(store.getHistory("a", 10).isEmpty(), "刚读过的会话被淘汰了");
        assertTrue(store.getHistory("b", 10).isEmpty(), "此时最久未访问的是 b");
    }
}
