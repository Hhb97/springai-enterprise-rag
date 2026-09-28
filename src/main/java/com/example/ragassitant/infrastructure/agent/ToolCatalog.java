package com.example.ragassitant.infrastructure.agent;

import com.example.ragassitant.domain.spi.AgentTraceStorePort;
import com.example.ragassitant.infrastructure.governance.ToolGovernance;
import com.example.ragassitant.infrastructure.observability.AgentMetrics;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 工具目录：从 {@code @Tool} 注解的 Bean 中提取工具定义（name / description / inputSchema）。
 *
 * <p><b>唯一真相源</b>。之前工具信息散落在三处且各自维护：{@code @Tool} 注解、
 * {@code PromptBuilder} 里手写的「## 工具清单」、{@code HeuristicRouter} 里的正则表。
 * 三份不一致时没有任何机制能发现 —— 提示词里写的参数名和注解里的对不上，模型就会填错参数。
 * 这里改成从 Spring AI 的 {@link MethodToolCallbackProvider} 反查，注解改了目录自动跟着变。
 *
 * <p>它同时是后续两件事的基础设施：
 * <ul>
 *   <li><b>影子路由</b>（当前用途）：把工具定义喂给 LLM，观察它会选哪个工具，用于评估
 *       「能不能把启发式正则路由换成 LLM 自主选择」；</li>
 *   <li><b>工具检索</b>（下一步）：工具数量上到几十、上百个时，不可能把全部定义塞进上下文
 *       （单个工具 schema 约 150~300 token，1000 个就是 15~30 万 token，且每轮都要重发，
 *       候选越多模型选得越不准）。届时把这里的 description + inputSchema 向量化，
 *       按 query 检索 top-K 再用 {@code ChatClient.toolCallbacks(...)} 按请求动态注册。</li>
 * </ul>
 */
@Component
public class ToolCatalog {

    private static final Logger log = LoggerFactory.getLogger(ToolCatalog.class);

    private final List<ToolCallback> toolCallbacks;
    private final List<ToolCallback> tracingToolCallbacks;

    public ToolCatalog(DeviceTools deviceTools, AgentTraceStorePort traceStore) {
        this(deviceTools, traceStore, ToolGovernance.permissive(), AgentMetrics.noop());
    }

    @Autowired
    public ToolCatalog(DeviceTools deviceTools, AgentTraceStorePort traceStore,
                       ToolGovernance governance, AgentMetrics metrics) {
        this.toolCallbacks = Arrays.asList(
                MethodToolCallbackProvider.builder()
                        .toolObjects(deviceTools)
                        .build()
                        .getToolCallbacks());
        this.tracingToolCallbacks = toolCallbacks.stream()
                .map(cb -> (ToolCallback) new TracingToolCallback(cb, traceStore, governance, metrics))
                .toList();
    }

    @PostConstruct
    public void logCatalog() {
        log.info("🧰 工具目录构建完成，共 {} 个工具: {}", toolCallbacks.size(),
                toolCallbacks.stream().map(t -> t.getToolDefinition().name()).toList());
    }

    public List<ToolCallback> toolCallbacks() {
        return toolCallbacks;
    }

    /**
     * 带日志与 trace 的工具回调，交给 {@code ChatClient.toolCallbacks(...)} 注册。
     *
     * <p>这是「LLM 有没有自己调工具」的确定性判据来源，见 {@link TracingToolCallback}。
     */
    public List<ToolCallback> tracingToolCallbacks() {
        return tracingToolCallbacks;
    }

    public List<ToolDefinition> definitions() {
        return toolCallbacks.stream().map(ToolCallback::getToolDefinition).toList();
    }

    public Optional<ToolDefinition> find(String name) {
        return definitions().stream().filter(d -> d.name().equals(name)).findFirst();
    }

    public boolean contains(String name) {
        return name != null && definitions().stream().anyMatch(d -> d.name().equals(name));
    }

    /**
     * 渲染成提示词可用的工具清单。
     *
     * <p>带上 {@code inputSchema} 是刻意的：只给工具名和描述，模型填参数时只能猜键名
     * （{@code date} vs {@code startDate}），而 FORCE_EXEC 那条路径不做模糊匹配，猜错就是缺参数。
     */
    public String renderForPrompt() {
        StringBuilder sb = new StringBuilder();
        for (ToolDefinition d : definitions()) {
            sb.append("- ").append(d.name()).append(": ").append(d.description()).append('\n');
            sb.append("  参数 schema: ").append(compact(d.inputSchema())).append('\n');
        }
        return sb.toString();
    }

    private static String compact(String schema) {
        return schema == null ? "{}" : schema.replaceAll("\\s+", " ").trim();
    }
}
