package com.example.ragassitant.infrastructure.llm;

import com.example.ragassitant.domain.spi.LlmPort;
import com.example.ragassitant.domain.spi.LlmPort.MessageDTO.Role;
import com.example.ragassitant.infrastructure.agent.ToolCallBudget;
import com.example.ragassitant.infrastructure.observability.AgentMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * LlmPort 的 Spring AI 实现，与具体后端无关（Ollama / DashScope 等 OpenAI 兼容协议）。
 *
 * <p><b>本类失败时直接抛异常</b>，不再自己吞掉返回 null。改造前每个方法都 {@code catch (Exception) → return null}，
 * 结果上层看到的只有「返回了 null」，分不清是超时、401、429 还是模型真的没输出 ——
 * 熔断器没法判断该不该计入失败率。现在异常原样上抛，由 {@code ResilientLlmPort} 统一分类、熔断、降级，
 * 并由它保持「对业务层最终返回 null」的原有约定。
 *
 * <p>不再注册为 Spring Bean：它只作为 {@code ResilientLlmPort} 的被装饰对象，由 {@code LlmBackendConfig} 显式构造。
 * 直接注入它就绕过了熔断和降级。
 */
public class SpringAiChatAdapter implements LlmPort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiChatAdapter.class);

    private final ChatClient chatClient;
    private final ChatClient.Builder chatClientBuilder;
    private final AgentMetrics metrics;

    public SpringAiChatAdapter(ChatClient.Builder chatClientBuilder) {
        this(chatClientBuilder, AgentMetrics.noop());
    }

    public SpringAiChatAdapter(ChatClient.Builder chatClientBuilder, AgentMetrics metrics) {
        this.chatClientBuilder = chatClientBuilder;
        this.chatClient = chatClientBuilder.build();
        this.metrics = metrics;
    }

    @Override
    public String chat(List<String> systemPrompts, List<String> historyUser, List<String> historyAssistant, String currentUser) {
        List<Message> messages = new ArrayList<>();
        if (systemPrompts != null) {
            for (String sp : systemPrompts) {
                if (sp != null && !sp.isBlank()) messages.add(new SystemMessage(sp));
            }
        }
        int pairs = Math.min(historyUser == null ? 0 : historyUser.size(),
                historyAssistant == null ? 0 : historyAssistant.size());
        for (int i = 0; i < pairs; i++) {
            messages.add(new UserMessage(historyUser.get(i)));
            messages.add(new AssistantMessage(historyAssistant.get(i)));
        }
        if (currentUser != null) messages.add(new UserMessage(currentUser));

        return textOf(recordUsage(chatClient.prompt().messages(messages).call().chatResponse()));
    }

    @Override
    public String chatWithRawMessages(List<MessageDTO> messages) {
        return textOf(recordUsage(chatClient.prompt().messages(toSpringMessages(messages)).call().chatResponse()));
    }

    /**
     * 结构化输出：把目标类型的 JSON Schema 作为格式约束追加到最后一条用户消息，一次调用，Schema 转换器解析。
     *
     * <p>没有直接用 {@code .call().entity(type)}：那样解析失败时只剩一个异常，原始文本拿不到，
     * 调用方想退回宽松解析就得<b>再调一次模型</b>。这里自己持有原始输出，解析失败时退回
     * {@link Structured#lenientParse}（剥围栏、忽略未知字段），仍然只付一次调用的钱。
     */
    @Override
    public <T> Structured<T> chatStructured(List<MessageDTO> messages, Class<T> type) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
        List<Message> springMessages = toSpringMessages(messages);
        springMessages.add(new UserMessage(converter.getFormat()));

        String raw = textOf(recordUsage(chatClient.prompt().messages(springMessages).call().chatResponse()));
        if (raw == null || raw.isBlank()) return new Structured<>(null, raw);
        try {
            return new Structured<>(converter.convert(raw), raw);
        } catch (Exception strictFailure) {
            Structured<T> lenient = Structured.lenientParse(raw, type);
            if (lenient.parsed()) {
                log.debug("结构化输出严格解析失败、宽松解析成功: type={}", type.getSimpleName());
            } else {
                log.warn("⚠️ 结构化输出解析失败: type={}, 原始输出前 200 字: {}", type.getSimpleName(),
                        raw.substring(0, Math.min(200, raw.length())));
            }
            return lenient;
        }
    }

    @Override
    public Flux<String> chatStreamWithRawMessages(List<MessageDTO> messages) {
        return chatClient.prompt()
                .messages(toSpringMessages(messages))
                .stream()
                .chatResponse()
                .map(chatResponse -> {
                    if (chatResponse == null || chatResponse.getResult() == null
                            || chatResponse.getResult().getOutput() == null) return "";
                    String text = chatResponse.getResult().getOutput().getText();
                    return text == null ? "" : text;
                })
                .filter(s -> s != null && !s.isEmpty());
    }

    @Override
    public String chatWithAutoToolCalling(List<MessageDTO> messages, int maxToolCalls, List<Object> toolBeans) {
        // 2.0.0 GA 的 DefaultToolCallingManager 还没有官方 Tool Call Limits，
        // 预算在 TracingToolCallback 装饰器层经 ToolCallBudget 真正强制执行。
        ToolCallBudget.begin(maxToolCalls);
        try {
            List<Object> beans = toolBeans == null ? List.of() : toolBeans;
            log.info("🤖[Spring AI 2.0 官方 Tool Calling] 开始：消息数={}, 最大工具次数={}, 工具数={}",
                    messages == null ? 0 : messages.size(), maxToolCalls, beans.size());

            ChatClient.ChatClientRequestSpec spec = chatClientBuilder.build()
                    .prompt()
                    .messages(toSpringMessages(messages));

            // ToolCallback → toolCallbacks(...)（可套装饰器、可按请求动态挑选）；普通 @Tool Bean → tools(...)
            List<ToolCallback> callbacks = asToolCallbacks(beans);
            spec = callbacks != null ? spec.toolCallbacks(callbacks) : spec.tools(beans.toArray());

            ChatResponse response = recordUsage(spec.call().chatResponse());
            String finalContent = textOf(response);
            log.info("✅[Spring AI 2.0 官方 Tool Calling] 结束：模型={}, 本轮tool_calls={}, finishReason={}, 最终回答长度={}",
                    modelOf(response), toolCallCount(response), finishReasonOf(response),
                    finalContent == null ? 0 : finalContent.length());
            return finalContent;
        } finally {
            ToolCallBudget.clear();
        }
    }

    // ==================== 内部 ====================

    /** 记录 token 用量：影子路由、改写、摘要都是额外调用，成本必须可见。 */
    private ChatResponse recordUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) return response;
        Usage usage = response.getMetadata().getUsage();
        if (usage != null) {
            String model = modelOf(response);
            if (usage.getPromptTokens() != null) metrics.tokens(model, "prompt", usage.getPromptTokens());
            if (usage.getCompletionTokens() != null) metrics.tokens(model, "completion", usage.getCompletionTokens());
        }
        return response;
    }

    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) return null;
        return response.getResult().getOutput().getText();
    }

    /** 全部是 ToolCallback 时返回强类型列表；否则返回 null，表示按普通 {@code @Tool} Bean 处理。 */
    private static List<ToolCallback> asToolCallbacks(List<Object> beans) {
        if (beans.isEmpty() || !beans.stream().allMatch(b -> b instanceof ToolCallback)) return null;
        return beans.stream().map(b -> (ToolCallback) b).toList();
    }

    private static String modelOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null) return "unknown";
        String model = response.getMetadata().getModel();
        return model == null || model.isBlank() ? "unknown" : model;
    }

    /**
     * 最后一轮响应里 tool_calls 的数量。健康流程里最后一轮是纯文本总结，通常为 0；
     * 判断「LLM 有没有自己调工具」请看 {@code NATIVE_TOOL_CALL} 日志与 trace。
     */
    private static int toolCallCount(ChatResponse response) {
        if (response == null || response.getResult() == null) return 0;
        return response.getResult().getOutput().hasToolCalls()
                ? response.getResult().getOutput().getToolCalls().size() : 0;
    }

    private static String finishReasonOf(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getMetadata() == null) return "unknown";
        String reason = response.getResult().getMetadata().getFinishReason();
        return reason == null || reason.isBlank() ? "unknown" : reason;
    }

    private static List<Message> toSpringMessages(List<MessageDTO> messages) {
        List<Message> springMessages = new ArrayList<>();
        if (messages != null) {
            for (MessageDTO dto : messages) {
                if (dto == null || dto.content == null) continue;
                switch (dto.role == null ? Role.USER : dto.role) {
                    case SYSTEM -> springMessages.add(new SystemMessage(dto.content));
                    case ASSISTANT -> springMessages.add(new AssistantMessage(dto.content));
                    case USER -> springMessages.add(new UserMessage(dto.content));
                }
            }
        }
        return springMessages;
    }
}
