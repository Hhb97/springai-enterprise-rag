package com.example.ragassitant.infrastructure.session;

import com.example.ragassitant.domain.spi.LlmPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class SpringAiConversationStoreTest {

    /** 摘要用的 LLM 桩：按给定行为返回或抛异常，并记录调用次数。 */
    static class StubLlm implements LlmPort {
        final Supplier<String> behavior;
        final AtomicInteger calls = new AtomicInteger();

        StubLlm(Supplier<String> behavior) {
            this.behavior = behavior;
        }

        @Override
        public String chat(String systemPrompt, String userMessage) {
            return chatWithRawMessages(List.of());
        }

        @Override
        public String chat(List<String> systemPrompts, List<String> historyUser,
                           List<String> historyAssistant, String currentUser) {
            return chatWithRawMessages(List.of());
        }

        @Override
        public String chatWithAutoToolCalling(List<MessageDTO> messages, int maxToolCalls, List<Object> toolBeans) {
            return chatWithRawMessages(messages);
        }

        @Override
        public String chatWithRawMessages(List<MessageDTO> messages) {
            calls.incrementAndGet();
            return behavior.get();
        }

        @Override
        public Flux<String> chatStreamWithRawMessages(List<MessageDTO> messages) {
            return Flux.just(behavior.get());
        }
    }

    private static SpringAiConversationStore store(int maxTurns, LlmPort llm) {
        return new SpringAiConversationStore(maxTurns, 120, 100, llm, true, 6000, "memory", null);
    }

    private static void turn(SpringAiConversationStore s, String sid, int n) {
        s.saveUser(sid, "问题" + n);
        s.saveAssistant(sid, "回答" + n);
    }

    @Test
    @DisplayName("窗口溢出时，被挤出的轮次压缩成摘要（首次摘要时尚无旧摘要）")
    void overflowProducesSummaryOnFirstCompaction() {
        StubLlm llm = new StubLlm(() -> "摘要：用户问过问题1");
        SpringAiConversationStore s = store(2, llm);
        turn(s, "s1", 1);
        turn(s, "s1", 2);
        turn(s, "s1", 3);

        assertTrue(llm.calls.get() > 0, "溢出后应调用摘要模型");
        assertEquals("摘要：用户问过问题1", s.getSummaries().get("s1"),
                "首次摘要必须能写入 —— 旧实现在「还没有旧摘要」时对 null 调 isBlank() 直接 NPE，摘要永远写不进去");
        // 摘要作为第一对伪消息返回，其后是窗口内的 2 轮原文
        List<Map.Entry<String, String>> history = s.getHistory("s1", 10);
        assertEquals(3, history.size());
        assertEquals("摘要：用户问过问题1", history.get(0).getValue());
        assertEquals("问题2", history.get(1).getKey());
        assertEquals("问题3", history.get(2).getKey());
    }

    @Test
    @DisplayName("摘要模型抛异常：不影响保存，窗口照常裁剪，不留下摘要")
    void summaryFailureDoesNotBreakSave() {
        StubLlm llm = new StubLlm(() -> {
            throw new IllegalStateException("backend down");
        });
        SpringAiConversationStore s = store(2, llm);
        assertDoesNotThrow(() -> {
            turn(s, "s1", 1);
            turn(s, "s1", 2);
            turn(s, "s1", 3);
        });
        List<Map.Entry<String, String>> history = s.getHistory("s1", 10);
        assertEquals("问题3", history.get(history.size() - 1).getKey(), "最新一轮必须保存成功");
        assertTrue(history.size() <= 2);
        assertNull(s.getSummaries().get("s1"));
    }

    @Test
    @DisplayName("摘要返回空串：保留旧摘要")
    void blankSummaryKeepsPrevious() {
        AtomicInteger n = new AtomicInteger();
        StubLlm llm = new StubLlm(() -> n.incrementAndGet() == 1 ? "第一版摘要" : "  ");
        SpringAiConversationStore s = store(1, llm);
        turn(s, "s1", 1);
        turn(s, "s1", 2); // 第一次压缩 → 第一版摘要
        turn(s, "s1", 3); // 第二次压缩 → 空串 → 保留
        assertEquals("第一版摘要", s.getSummaries().get("s1"));
    }
}
