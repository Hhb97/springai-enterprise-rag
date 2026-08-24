package com.example.ragassitant.infrastructure.llm;

import com.example.ragassitant.domain.spi.LlmPort;
import com.example.ragassitant.domain.spi.LlmPort.MessageDTO.Role;
import com.example.ragassitant.infrastructure.agent.ToolCallBudget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * LlmPort 的 Spring AI 实现，与具体后端无关。
 *
 * <p>后端由 {@code spring.ai.chat-backend} 决定（Ollama 本地 / DashScope 等 OpenAI 兼容协议），
 * 见 {@link com.example.ragassitant.infrastructure.config.LlmBackendConfig}。
 * 原名 {@code SpringAiOllamaAdapter} 是最初只接 Ollama 时留下的，类名和职责已经不符 ——
 * 排查「为什么请求打到了云上」时，一个叫 Ollama 的类会把人带偏，所以改名。
 *
 * <p>标 {@code @Primary}：Query 改写会注册第二个 LlmPort bean（可指向不同后端），
 * 需要明确谁是主实现，否则按类型注入会歧义。
 */
@Component
@Primary
public class SpringAiChatAdapter implements LlmPort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiChatAdapter.class);
    private final ChatClient chatClient;
    private final ChatClient.Builder chatClientBuilder;

    public SpringAiChatAdapter(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String chat(List<String> systemPrompts, List<String> historyUser, List<String> historyAssistant, String currentUser) {
        try {
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

            return chatClient.prompt().messages(messages).call().content();
        } catch (Exception e) {
            log.error("❌ LLM 调用失败", e);
            return null;
        }
    }

    @Override
    public String chatWithRawMessages(List<MessageDTO> messages) {
        try {
            return chatClient.prompt().messages(toSpringMessages(messages)).call().content();
        } catch (Exception e) {
            if (isInterrupt(e)) {
                Thread.currentThread().interrupt();
                log.debug("⏹️ LLM 调用被调用方取消（通常是上游超时）");
            } else {
                log.error("❌ LLM RawMessages 调用失败", e);
            }
            return null;
        }
    }

    @Override
    public Flux<String> chatStreamWithRawMessages(List<MessageDTO> messages) {
        try {
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
                    .filter(s -> s != null && !s.isEmpty())
                    .doOnError(e -> {
                        if (isInterrupt(e)) {
                            Thread.currentThread().interrupt();
                            log.debug("⏹️ LLM Stream 调用被取消");
                        } else {
                            log.error("❌ LLM Stream RawMessages 调用失败", e);
                        }
                    });
        } catch (Exception e) {
            log.error("❌ LLM Stream RawMessages 创建失败", e);
            return Flux.error(e);
        }
    }

    private static boolean isInterrupt(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException) return true;
            if (t.getCause() == t) break;
        }
        return Thread.currentThread().isInterrupted();
    }

    @Override
    public String chatWithAutoToolCalling(List<MessageDTO> messages, int maxToolCalls, List<Object> toolBeans) {
        // 2.0.0 GA 的 DefaultToolCallingManager 还没有官方 Tool Call Limits，
        // 预算在 TracingToolCallback 装饰器层经 ToolCallBudget 真正强制执行：
        // 每次原生工具执行扣一次额度，耗尽抛 IllegalStateException 中断循环。
        ToolCallBudget.begin(maxToolCalls);
        try {
            List<Object> beans = toolBeans == null ? List.of() : toolBeans;
            log.info("🤖[Spring AI 2.0 官方 Tool Calling] 开始：消息数={}, 最大工具次数={}, 工具数={}",
                    messages == null ? 0 : messages.size(), maxToolCalls, beans.size());

            // Spring AI 2.0 ChatClient 内部自动完成 Agent 循环，不需要手写 for：
            //   LLM → 检测 tool_call 响应 → 反射调用 @Tool 注解方法 → 自动塞回 ToolMessage → 循环 → 出最终纯文本
            //
            // 取 chatResponse() 而不是 content()：多拿到「实际服务的模型名」和「本轮有没有 tool_calls」。
            // 【为什么必须打这两个值】排查「模型没调工具」时，第一个要排除的就是
            // 「请求根本没打到你以为的那个模型」—— spring.ai.chat-backend 默认 ollama，
            // 而 spring.ai.openai.* 下面可能配着另一个模型，只看配置文件是分辨不出来的。
            ChatClient.ChatClientRequestSpec spec = chatClientBuilder.build()
                    .prompt()
                    .messages(toSpringMessages(messages));

            // 两种注册方式：
            //   ToolCallback  → toolCallbacks(...)，可以套装饰器（日志/trace/动态挑选），
            //                   也是后续「按 query 检索 top-K 个工具再注册」的必经接口
            //   普通 @Tool Bean → tools(...)，由框架自己反射扫描注解
            // tools(Object...) 不接受 ToolCallback 实例，所以必须分流。
            List<ToolCallback> callbacks = asToolCallbacks(beans);
            spec = callbacks != null ? spec.toolCallbacks(callbacks) : spec.tools(beans.toArray());

            ChatResponse response = spec.call().chatResponse();

            String finalContent = response == null || response.getResult() == null
                    ? null : response.getResult().getOutput().getText();
            log.info("✅[Spring AI 2.0 官方 Tool Calling] 结束：模型={}, 本轮tool_calls={}, finishReason={}, 最终回答长度={}",
                    modelOf(response), toolCallCount(response), finishReasonOf(response),
                    finalContent == null ? 0 : finalContent.length());
            return finalContent;
        } catch (Exception e) {
            log.error("❌ Spring AI 2.0 官方 Tool Calling 执行失败", e);
            return null;
        } finally {
            ToolCallBudget.clear();
        }
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
     * 最后一轮响应里 tool_calls 的数量。
     *
     * <p>正常结束时通常是 0（工具已经在前面的轮次执行完，最后一轮是纯文本总结）。
     * 它的诊断价值在于：如果全程都是 0、而模型的文本里却写着 {@code [{"name":...}]}，
     * 就说明这个模型没有走原生 tool_calls 通道 —— 是模型能力问题，不是编排问题。
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