package com.example.ragassitant.domain.spi;

import com.example.ragassitant.domain.service.AgentTextUtils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 大模型调用端口。
 *
 * <p><b>失败语义约定</b>：同步方法在最终失败（含熔断拒绝、降级也失败）时返回 {@code null}，
 * 不向业务层抛异常；流式方法以 {@code Flux.error} 结束。熔断、降级、重试由基础设施层的
 * 装饰器统一处理，业务代码不需要也不应该自己 try/catch 重试。
 */
public interface LlmPort {

    String chat(List<String> systemPrompts, List<String> historyUser, List<String> historyAssistant, String currentUser);

    String chatWithRawMessages(List<MessageDTO> messages);

    /**
     * 流式版本 chatWithRawMessages。
     * 返回 Flux<String>，每个元素是 LLM 增量吐出的 token（或 token 片段）。
     * 结束时 Flux complete，异常时 Flux error。
     */
    Flux<String> chatStreamWithRawMessages(List<MessageDTO> messages);

    /**
     * Spring AI 2.0 官方自动 Tool Calling Agent 循环。
     *
     * @param messages        历史 / 系统 / 用户消息列表
     * @param maxToolCalls    最大工具调用次数
     * @param toolBeans       带 @Tool 注解的 Bean 或 ToolCallback 列表
     * @return                LLM 最终回答（纯文本），失败返回 null
     */
    String chatWithAutoToolCalling(List<MessageDTO> messages, int maxToolCalls, List<Object> toolBeans);

    /**
     * 结构化输出：让模型按 {@code type} 的 JSON Schema 作答并反序列化。
     *
     * <p>【为什么加】改造前 Planner / ReAct / 影子路由共 6 处在用正则 + {@code indexOf('{')} 手抠 JSON。
     * 基础设施实现会把目标类型的 JSON Schema 作为格式约束追加进提示词，再用 Schema 对应的转换器解析；
     * 解析失败时 {@link Structured#value()} 为 null 但保留 {@link Structured#raw()}，
     * 调用方可以退回旧的宽松解析 —— <b>只调用一次模型</b>，不会因为结构化失败而重复付费。
     *
     * <p>默认实现（无 Schema 约束，只做宽松 JSON 解析）供测试桩和不支持的实现使用。
     */
    default <T> Structured<T> chatStructured(List<MessageDTO> messages, Class<T> type) {
        String raw = chatWithRawMessages(messages);
        return Structured.lenientParse(raw, type);
    }

    default String chat(String systemPrompt, String userQuestion) {
        return chat(List.of(systemPrompt), List.of(), List.of(), userQuestion);
    }

    /**
     * 结构化调用结果：{@code value} 为解析成功的对象（失败为 null），{@code raw} 为模型原始输出（调用失败为 null）。
     */
    record Structured<T>(T value, String raw) {

        private static final ObjectMapper LENIENT = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        public boolean parsed() {
            return value != null;
        }

        public static <T> Structured<T> failed() {
            return new Structured<>(null, null);
        }

        /** 宽松解析：剥代码块围栏、抠第一个 JSON 对象、忽略未知字段。 */
        public static <T> Structured<T> lenientParse(String raw, Class<T> type) {
            if (raw == null || raw.isBlank()) return new Structured<>(null, raw);
            String json = AgentTextUtils.extractJson(raw);
            if (json == null) return new Structured<>(null, raw);
            try {
                return new Structured<>(LENIENT.readValue(json, type), raw);
            } catch (Exception e) {
                return new Structured<>(null, raw);
            }
        }
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
