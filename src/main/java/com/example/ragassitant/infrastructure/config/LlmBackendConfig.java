package com.example.ragassitant.infrastructure.config;

import com.example.ragassitant.domain.spi.LlmPort;
import com.example.ragassitant.infrastructure.llm.SpringAiChatAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;

@Configuration
public class LlmBackendConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmBackendConfig.class);

    @Value("${spring.ai.chat-backend:ollama}")
    private String chatBackend;

    @Value("${spring.ai.embedding-backend:ollama}")
    private String embeddingBackend;

    // === DashScope (OpenAI 兼容模式) ===
    @Value("${spring.ai.openai.api-key:}")
    private String dashscopeApiKey;

    @Value("${spring.ai.openai.base-url:}")
    private String dashscopeBaseUrl;

    @Value("${spring.ai.openai.default-chat-options.model:}")
    private String dashscopeChatModel;

    // === Ollama ===
    @Value("${spring.ai.ollama.base-url:}")
    private String ollamaBaseUrl;

    @Value("${spring.ai.ollama.default-chat-options.model:}")
    private String ollamaChatModel;

    @Value("${spring.ai.ollama.embedding.options.model:}")
    private String ollamaEmbedModel;

    // === Query 改写 ===
    @Value("${rag.query-rewrite.backend:dashscope}")
    private String rewriteBackend;

    @Value("${rag.query-rewrite.model:}")
    private String rewriteModel;

    @Value("${rag.query-rewrite.timeout-ms:3000}")
    private int rewriteTimeoutMs;

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
     * Query 改写专用的 LlmPort，后端由 {@code rag.query-rewrite.backend} 独立指定。
     *
     * <p>为什么要独立配置：指代消解对 1.5b 级别的小模型偏吃力，改写跑偏反而会让检索更差；
     * 而改写又在问答主链路上，用本地小模型还要额外付 1~3s 推理延迟。默认指向 DashScope 的
     * 轻量模型，既快又便宜；想完全离线就把它改回 ollama。
     *
     * <p>目标后端不可用时降级到主聊天模型，绝不因为改写这个可选环节导致启动失败。
     */
    @Bean("queryRewriteLlmPort")
    public LlmPort queryRewriteLlmPort(
            @Value("${rag.query-rewrite.backend:dashscope}") String rewriteBackend,
            @Value("${rag.query-rewrite.model:}") String rewriteModel,
            @Autowired(required = false) @Qualifier("ollamaChatModel") ChatModel ollamaChatModel,
            @Autowired(required = false) @Qualifier("openAiChatModel") ChatModel openAiChatModel,
            ChatModel primaryChatModel) {

        String backend = (rewriteBackend == null ? "" : rewriteBackend.trim().toLowerCase());
        ChatModel chosen;
        boolean degraded = false;

        if ("ollama".equals(backend) && ollamaChatModel != null) {
            chosen = ollamaChatModel;
            log.info("✅ Query 改写后端：ollama（本地）");
        } else if (("dashscope".equals(backend) || "openai".equals(backend)) && openAiChatModel != null) {
            chosen = openAiChatModel;
            log.info("✅ Query 改写后端：{}（OpenAI 兼容模式）", backend);
        } else {
            chosen = primaryChatModel;
            degraded = true;
            log.warn("⚠️ Query 改写后端 '{}' 不可用，降级使用主聊天模型（改写质量可能下降，但不影响主流程）",
                    rewriteBackend);
        }

        ChatClient.Builder builder = ChatClient.builder(chosen);
        // 降级到主模型时不套用改写模型名 —— 模型名是跟后端绑定的，跨后端用必然 404
        if (!degraded && rewriteModel != null && !rewriteModel.isBlank()) {
            // 改写是机械任务，温度压到最低求稳定
            // ChatOptions 是接口没有静态 builder，必须按后端用具体实现类：
            //   ollama 后端 → OllamaChatOptions.builder()
            //   dashscope/openai 兼容模式 → OpenAiChatOptions.builder()
            // 注意：Spring AI 2.0 defaultOptions() 接受 Builder，不要提前 .build()
            if ("ollama".equals(backend)) {
                builder = builder.defaultOptions(OllamaChatOptions.builder()
                        .model(rewriteModel)
                        .temperature(0.1));
            } else {
                builder = builder.defaultOptions(OpenAiChatOptions.builder()
                        .model(rewriteModel)
                        .temperature(0.1));
            }
            log.info("✅ Query 改写模型：{}", rewriteModel);
        } else {
            log.info("ℹ️ Query 改写沿用后端默认模型（未指定 rag.query-rewrite.model）");
        }
        return new SpringAiChatAdapter(builder);
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

    /**
     * 应用启动完成后集中打印 LLM 相关配置（API Key 脱敏），方便确认 compose 里的环境变量有没有生效。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void printLlmConfigSummary() {
        String maskedKey = maskApiKey(dashscopeApiKey);
        String chatModelDisplay = resolveChatModelDisplay();
        String embedModelDisplay = resolveEmbedModelDisplay();
        String rewriteModelDisplay = (rewriteModel == null || rewriteModel.isBlank())
                ? "沿用后端默认" : rewriteModel;

        // 用分段 box 打印，日志里一眼看清
        log.info("");
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                        🤖 LLM 配置总览                             ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║  聊天后端 (CHAT_BACKEND)         : {}  {}",
                padRight(chatBackend, 12), markActive(chatBackend));
        log.info("║  聊天模型 (DASHSCOPE_CHAT_MODEL) : {}", chatModelDisplay);
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║  向量后端 (EMBED_BACKEND)        : {}  {}",
                padRight(embeddingBackend, 12), markActive(embeddingBackend));
        log.info("║  向量模型                        : {}", embedModelDisplay);
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║  ── DashScope / OpenAI 兼容模式 ──                                ║");
        log.info("║  API Key (DASHSCOPE_API_KEY)     : {}", maskedKey);
        log.info("║  Base URL                        : {}", dashscopeBaseUrl.isBlank() ? "(未设置)" : dashscopeBaseUrl);
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║  ── Ollama 本地模式 ──                                            ║");
        log.info("║  Base URL (OLLAMA_BASE_URL)      : {}", ollamaBaseUrl.isBlank() ? "(未设置)" : ollamaBaseUrl);
        log.info("║  Ollama 聊天模型                 : {}", ollamaChatModel.isBlank() ? "(未设置)" : ollamaChatModel);
        log.info("║  Ollama 向量模型                 : {}", ollamaEmbedModel.isBlank() ? "(未设置)" : ollamaEmbedModel);
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║  ── Query 改写 (REWRITE) ──                                       ║");
        log.info("║  改写后端 (REWRITE_BACKEND)      : {}  {}",
                padRight(rewriteBackend, 12), markActive(rewriteBackend));
        log.info("║  改写模型 (REWRITE_MODEL)        : {}", rewriteModelDisplay);
        log.info("║  改写超时                        : {} ms", rewriteTimeoutMs);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
        log.info("");
    }

    // === 打印辅助方法 ===

    private String resolveChatModelDisplay() {
        String b = (chatBackend == null ? "" : chatBackend.trim().toLowerCase());
        if ("dashscope".equals(b) || "openai".equals(b)) {
            return (dashscopeChatModel.isBlank() ? "(未设置，将用默认)" : dashscopeChatModel) + "  ← 生效";
        }
        // ollama 或其他
        return (ollamaChatModel.isBlank() ? "(未设置)" : ollamaChatModel) + "  ← 生效";
    }

    private String resolveEmbedModelDisplay() {
        String b = (embeddingBackend == null ? "" : embeddingBackend.trim().toLowerCase());
        if ("dashscope".equals(b) || "openai".equals(b)) {
            return "text-embedding-v3 (DashScope)  ← 生效";
        }
        return (ollamaEmbedModel.isBlank() ? "(未设置)" : ollamaEmbedModel) + "  ← 生效";
    }

    private static String markActive(String backend) {
        if (backend == null) return "";
        String b = backend.trim().toLowerCase();
        return switch (b) {
            case "dashscope", "openai" -> "☁️  云端";
            case "ollama" -> "🖥️  本地";
            default -> "❓ 未知";
        };
    }

    private static String padRight(String s, int len) {
        if (s == null) s = "";
        if (s.length() >= len) return s;
        return s + " ".repeat(len - s.length());
    }

    /**
     * API Key 脱敏：空/短的直接打码，否则 前6 + **** + 后4
     */
    private static String maskApiKey(String key) {
        if (key == null || key.isBlank()) {
            return "⚠️  未设置（会导致 DashScope 调用失败）";
        }
        if (key.length() <= 10) {
            return "**** (长度异常)";
        }
        return key.substring(0, 6) + "****" + key.substring(key.length() - 4);
    }
}