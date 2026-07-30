package com.example.ragassitant.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class LlmBackendConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmBackendConfig.class);

    @Value("${spring.ai.chat-backend:ollama}")
    private String chatBackend;

    @Value("${spring.ai.embedding-backend:ollama}")
    private String embeddingBackend;

    /**
     * 根据 spring.ai.chat-backend 配置，决定 Agent + ChatClient 默认用哪个聊天模型。
     * 通过 @Qualifier 按 Bean 名称注入，不依赖具体实现类包名，适配 Spring AI 2.0 GA。
     * 取值：ollama（本地 Ollama）/ dashscope（阿里云百炼 OpenAI 兼容）/ openai（官方 OpenAI）
     */
    @Bean
    @Primary
    public ChatModel primaryChatModel(
            @Autowired(required = false) @Qualifier("ollamaChatModel") ChatModel ollamaChatModel,
            @Autowired(required = false) @Qualifier("openAiChatModel") ChatModel openAiChatModel) {

        ChatModel chosen;
        String backend = (chatBackend == null ? "" : chatBackend.trim().toLowerCase());

        if (("dashscope".equals(backend) || "openai".equals(backend)) && openAiChatModel != null) {
            chosen = openAiChatModel;
            log.info("✅ LLM 聊天后端已切换：chat-backend={}，使用 OpenAiChatModel（百炼 DashScope 兼容模式）", chatBackend);
        } else {
            if (ollamaChatModel == null) {
                throw new IllegalStateException("配置 chat-backend=ollama 但 Ollama ChatModel 未初始化，请检查 spring.ai.ollama.* 配置");
            }
            chosen = ollamaChatModel;
            log.info("✅ LLM 聊天后端已切换：chat-backend={}，使用 Ollama ChatModel（本地部署）", chatBackend);
        }

        return chosen;
    }

    /**
     * Spring AI 2.0 ChatClient.Builder 默认会注入 @Primary 的 ChatModel，
     * 这里显式再包一层，保证业务适配器拿到的一定是切完后端的 Builder。
     */
    @Bean
    @Primary
    public ChatClient.Builder primaryChatClientBuilder(ChatModel primaryChatModel) {
        return ChatClient.builder(primaryChatModel);
    }

    /**
     * 根据 spring.ai.embedding-backend 配置决定向量编码模型。
     * 建议保持 ollama（本地 nomic-embed-text 免费、无外网延迟）。
     */
    @Bean
    @Primary
    public EmbeddingModel primaryEmbeddingModel(
            @Autowired(required = false) @Qualifier("ollamaEmbeddingModel") EmbeddingModel ollamaEmbeddingModel,
            @Autowired(required = false) @Qualifier("openAiEmbeddingModel") EmbeddingModel openAiEmbeddingModel) {

        EmbeddingModel chosen;
        String backend = (embeddingBackend == null ? "" : embeddingBackend.trim().toLowerCase());

        if (("dashscope".equals(backend) || "openai".equals(backend)) && openAiEmbeddingModel != null) {
            chosen = openAiEmbeddingModel;
            log.info("✅ Embedding 后端已切换：embedding-backend={}，使用 OpenAiEmbeddingModel", embeddingBackend);
        } else {
            if (ollamaEmbeddingModel == null) {
                throw new IllegalStateException("配置 embedding-backend=ollama 但 Ollama EmbeddingModel 未初始化，请检查 spring.ai.ollama.embedding.* 配置");
            }
            chosen = ollamaEmbeddingModel;
            log.info("✅ Embedding 后端已切换：embedding-backend={}，使用 Ollama EmbeddingModel（本地 nomic-embed-text）", embeddingBackend);
        }
        return chosen;
    }
}