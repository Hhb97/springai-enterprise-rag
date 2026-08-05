package com.example.ragassitant.domain.spi;

import reactor.core.publisher.Flux;

import java.util.List;

public interface LlmPort {

    String chat(List<String> systemPrompts, List<String> historyUser, List<String> historyAssistant, String currentUser);

    String chatWithRawMessages(List<MessageDTO> messages);

    /**
     * 流式版本 chatWithRawMessages。
     * 返回 Flux<String>，每个元素是 LLM 增量吐出的 token（或 token 片段）。
     * 结束时 Flux complete，异常时 Flux error。
     *
     * <p>典型用法：
     * <pre>
     *   StringBuilder buf = new StringBuilder();
     *   llmPort.chatStreamWithRawMessages(messages)
     *     .doOnNext(buf::append)
     *     .doOnComplete(() -> System.out.println(buf))
     *     .subscribe();
     * </pre>
     */
    Flux<String> chatStreamWithRawMessages(List<MessageDTO> messages);

    /**
     * Spring AI 2.0 官方自动 Tool Calling Agent 循环。
     * 不需要手写 for / 解析 tool_call JSON / 手动拼消息，ChatClient 内部自动：
     *   1. 调 LLM → 2. 检测 tool_call → 3. 反射调用 @Tool 注解的 Bean 方法 → 4. 自动把结果拼回 messages → 5. 回到 1 直到最终回答
     *
     * @param messages        历史 / 系统 / 用户消息列表
     * @param maxToolCalls    最大工具调用轮次（LLM 最多调用多少次工具就强制返回）
     * @param toolBeans       带 @Tool 注解的 Spring Bean（DeviceTools 等）
     * @return                LLM 最终总结回答（纯文本）
     */
    String chatWithAutoToolCalling(List<MessageDTO> messages, int maxToolCalls, List<Object> toolBeans);

    default String chat(String systemPrompt, String userQuestion) {
        return chat(List.of(systemPrompt), List.of(), List.of(), userQuestion);
    }

    class MessageDTO {
        public final Role role;
        public final String content;

        public MessageDTO(Role role, String content) {
            this.role = role;
            this.content = content;
        }

        public enum Role { SYSTEM, USER, ASSISTANT }
    }
}