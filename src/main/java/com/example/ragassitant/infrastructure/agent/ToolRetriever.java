package com.example.ragassitant.infrastructure.agent;

import com.example.ragassitant.infrastructure.governance.GovernanceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具检索：按问题语义挑 top-K 个工具注册给模型，而不是每轮都把全部工具塞进上下文。
 *
 * <p>工具少的时候<b>不该</b>开：8 个工具全量注册只占一千多 token，模型选得也准；检索反而可能漏掉正确工具。
 * 只有工具数达到 {@code governance.tool-retrieval.min-tools}（默认 20）且显式启用时才生效，
 * 否则 {@link #select} 原样返回全部工具。
 *
 * <p>索引用<b>独立的</b>内存 SimpleVectorStore（不和知识库混在一起，按用户要求不做向量库迁移），
 * 启动完成后构建一次；工具定义来自 {@link ToolCatalog}，改注解后重启即生效。
 * 被检索到的工具之外，{@code always-include} 列出的兜底工具始终注册。
 */
@Component
public class ToolRetriever {

    private static final Logger log = LoggerFactory.getLogger(ToolRetriever.class);
    private static final String TOOL_NAME = "toolName";

    private final ToolCatalog toolCatalog;
    private final GovernanceProperties.ToolRetrieval cfg;
    private final ObjectProvider<EmbeddingModel> embeddingModel;

    private volatile SimpleVectorStore index;

    public ToolRetriever(ToolCatalog toolCatalog, GovernanceProperties props,
                         ObjectProvider<EmbeddingModel> embeddingModel) {
        this.toolCatalog = toolCatalog;
        this.cfg = props.getToolRetrieval();
        this.embeddingModel = embeddingModel;
    }

    public boolean active() {
        return cfg.isEnabled() && toolCatalog.tracingToolCallbacks().size() >= cfg.getMinTools() && index != null;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void buildIndex() {
        int total = toolCatalog.tracingToolCallbacks().size();
        if (!cfg.isEnabled()) return;
        if (total < cfg.getMinTools()) {
            log.info("🧰 工具检索已启用但工具数 {} < min-tools {}，保持全量注册", total, cfg.getMinTools());
            return;
        }
        EmbeddingModel model = embeddingModel.getIfAvailable();
        if (model == null) {
            log.warn("🧰 工具检索需要 EmbeddingModel，未找到，保持全量注册");
            return;
        }
        try {
            SimpleVectorStore store = SimpleVectorStore.builder(model).build();
            List<Document> docs = new ArrayList<>();
            for (ToolCallback cb : toolCatalog.tracingToolCallbacks()) {
                var d = cb.getToolDefinition();
                docs.add(new Document(d.name() + "\n" + d.description(), Map.of(TOOL_NAME, d.name())));
            }
            store.add(docs);
            this.index = store;
            log.info("🧰 工具检索索引构建完成：{} 个工具，每次注册 top-{} + 兜底 {}", total, cfg.getTopK(),
                    cfg.getAlwaysInclude());
        } catch (Exception e) {
            log.warn("🧰 工具检索索引构建失败，保持全量注册: {}", e.getMessage());
        }
    }

    /** 返回本次问题应注册给模型的工具；未启用 / 检索失败时返回全部工具。 */
    public List<ToolCallback> select(String question) {
        List<ToolCallback> all = toolCatalog.tracingToolCallbacks();
        if (!active() || question == null || question.isBlank()) return all;
        try {
            Set<String> names = new LinkedHashSet<>(cfg.getAlwaysInclude());
            index.similaritySearch(SearchRequest.builder().query(question).topK(cfg.getTopK()).build())
                    .forEach(d -> names.add(String.valueOf(d.getMetadata().get(TOOL_NAME))));
            List<ToolCallback> picked = all.stream()
                    .filter(cb -> names.contains(cb.getToolDefinition().name()))
                    .toList();
            log.info("🧰 工具检索：{} 个工具中选中 {} 个: {}", all.size(), picked.size(), names);
            return picked.isEmpty() ? all : picked;
        } catch (Exception e) {
            log.warn("🧰 工具检索失败，回退全量注册: {}", e.getMessage());
            return all;
        }
    }
}
