package com.example.ragassitant.infrastructure.llm;

import com.example.ragassitant.domain.spi.LlmPort;
import com.example.ragassitant.domain.spi.LlmPort.MessageDTO.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SpringAiOllamaAdapter implements LlmPort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiOllamaAdapter.class);
    private final ChatClient chatClient;
    private final ChatClient.Builder chatClientBuilder;

    public SpringAiOllamaAdapter(ChatClient.Builder chatClientBuilder) {
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
            log.error("❌ LLM RawMessages 调用失败", e);
            return null;
        }
    }

    @Override
    public String chatWithAutoToolCalling(List<MessageDTO> messages, int maxToolCalls, List<Object> toolBeans) {
        try {
            Object[] tools = toolBeans == null ? new Object[0] : toolBeans.toArray();
            int maxCalls = maxToolCalls <= 0 ? 5 : maxToolCalls;
            log.info("🤖[Spring AI 2.0 官方 Tool Calling] 开始：消息数={}, 最大工具轮次={}, 工具Bean数={}",
                    messages == null ? 0 : messages.size(), maxCalls, tools.length);

            // Spring AI 2.0 ChatClient 内部自动完成 Agent 循环，不需要手写 for：
            //   LLM → 检测 tool_call 响应 → 反射调用 @Tool 注解方法 → 自动塞回 ToolMessage → 循环 → 出最终纯文本
            String finalContent = chatClientBuilder.build()
                    .prompt()
                    .messages(toSpringMessages(messages))
                    .tools(tools)
                    .call()
                    .content();

            log.info("✅[Spring AI 2.0 官方 Tool Calling] 结束：最终回答长度={}", finalContent == null ? 0 : finalContent.length());
            return finalContent;
        } catch (Exception e) {
            log.error("❌ Spring AI 2.0 官方 Tool Calling 执行失败", e);
            return null;
        }
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