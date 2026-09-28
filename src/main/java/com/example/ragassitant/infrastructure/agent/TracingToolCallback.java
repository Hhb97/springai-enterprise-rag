package com.example.ragassitant.infrastructure.agent;

import com.example.ragassitant.common.trace.TraceIdFilter;
import com.example.ragassitant.domain.spi.AgentTraceStorePort;
import com.example.ragassitant.infrastructure.governance.ToolGovernance;
import com.example.ragassitant.infrastructure.observability.AgentMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.DefaultToolMetadata;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Map;

/**
 * 给 {@link ToolCallback} 套一层日志与 trace 记录，用来回答一个基本问题：
 * <b>这次工具执行到底是 LLM 通过原生 tool_calls 通道发起的，还是我们自己的反射路径发起的？</b>
 *
 * <p>改造前无法区分：
 * <ul>
 *   <li>{@code DeviceTools} 里那句「🔧 调用工具 xxx」<b>两条路径都会打</b>
 *       （原生执行、以及 FORCE_EXEC / 文本兜底走的 {@code SpringAiToolRegistry} 反射）；</li>
 *   <li>适配器打的 {@code 本轮tool_calls} 只反映<b>最后一轮</b>响应，而健康流程里最后一轮
 *       正是纯文本总结，该值恒为 0 —— 看着像「没调工具」，极易误判；</li>
 *   <li>原生路径<b>完全不写 AgentTrace</b>：只有 FORCE_EXEC 会写 {@code HEURISTIC_TOOL}，
 *       所以 trace 回放里看不到 LLM 自主调用过什么工具、传了什么参数。</li>
 * </ul>
 *
 * <p>本类只在原生通道被真正走通时才会执行到，因此它的日志（{@code NATIVE_TOOL_CALL}）
 * 是「LLM 自己调了工具」的<b>确定性判据</b>，不需要开 DEBUG。
 *
 * <p>顺带说明：改用 {@code ChatClient.toolCallbacks(...)} 注册工具（而不是 {@code tools(Object...)}）
 * 也是工具检索（按 query 动态挑 top-K 个工具注册）的前置条件。
 *
 * <p>生产化后本类还承担两件事：
 * <ul>
 *   <li><b>治理</b>：执行前过 {@link ToolGovernance}（禁用 / 权限 / 高风险待确认 / 写操作幂等 / 审计）；</li>
 *   <li><b>直出</b>：governance 配置为 returnDirect 的只读工具，通过 {@link #getToolMetadata()} 告诉
 *       Spring AI 跳过模型总结轮，工具输出原样作为最终回答 —— 省一次 LLM 往返，也杜绝模型「总结」时改数字。</li>
 * </ul>
 */
public class TracingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(TracingToolCallback.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolCallback delegate;
    private final AgentTraceStorePort traceStore;
    private final ToolGovernance governance;
    private final AgentMetrics metrics;
    private final ToolMetadata metadata;

    public TracingToolCallback(ToolCallback delegate, AgentTraceStorePort traceStore) {
        this(delegate, traceStore, ToolGovernance.permissive(), AgentMetrics.noop());
    }

    public TracingToolCallback(ToolCallback delegate, AgentTraceStorePort traceStore,
                               ToolGovernance governance, AgentMetrics metrics) {
        this.delegate = delegate;
        this.traceStore = traceStore;
        this.governance = governance;
        this.metrics = metrics;
        boolean direct = delegate.getToolMetadata().returnDirect()
                || governance.returnDirect(delegate.getToolDefinition().name());
        this.metadata = new DefaultToolMetadata(direct);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return metadata;
    }

    @Override
    public String call(String toolInput) {
        return record(toolInput, () -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return record(toolInput, () -> delegate.call(toolInput, toolContext));
    }

    private String record(String toolInput, ToolInvocation invocation) {
        String name = getToolDefinition().name();
        // 预算控制：耗尽即中断 ChatClient 内部工具循环（见 ToolCallBudget 类注释）。
        // 必须抛普通 RuntimeException 而非 ToolExecutionException —— 后者会被官方
        // ToolExecutionExceptionProcessor 转成错误文本回灌给模型，循环不会停。
        if (!ToolCallBudget.tryAcquire()) {
            log.warn("🛠️[NATIVE_TOOL_CALL] 工具调用次数已达上限，中断本轮循环: tool={}", name);
            throw new ToolBudgetExceededException("已达到本次问答的工具调用次数上限 (agent.max-tool-calls)，请缩小问题范围后重试");
        }
        long start = System.currentTimeMillis();
        log.info("🛠️[NATIVE_TOOL_CALL] LLM 通过原生 tool_calls 通道调用 {}，入参={}", name, toolInput);

        String result = null;
        String error = null;
        try {
            result = governance.execute(name, toolInput, "native", invocation::execute);
            if (metadata.returnDirect()) {
                result = unquote(result);
            }
            metrics.toolCall(name, "native", "ok");
            return result;
        } catch (Exception e) {
            error = e.toString();
            metrics.toolCall(name, "native", "error");
            throw e;
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            log.info("🛠️[NATIVE_TOOL_CALL] {} 执行完毕，耗时 {}ms，结果长度={}",
                    name, elapsed, result == null ? 0 : result.length());
            safeRecord(name, toolInput, result, elapsed, error);
        }
    }

    /**
     * Spring AI 的 {@code DefaultToolCallResultConverter} 会把「不是合法 JSON」的字符串结果再 JSON 编码一次
     * （Markdown 报表 → {@code "\"## 能耗...\n\""}）。交给模型时无所谓，但直出时这串带引号和转义符的文本
     * 会原样显示给用户，所以直出前还原成原文。
     */
    static String unquote(String result) {
        if (result == null || result.length() < 2 || result.charAt(0) != '"'
                || result.charAt(result.length() - 1) != '"') {
            return result;
        }
        try {
            return JSON.readValue(result, String.class);
        } catch (Exception e) {
            return result;
        }
    }

    /** trace 写入失败绝不能影响工具调用本身 */
    private void safeRecord(String name, String toolInput, String result, long elapsed, String error) {
        try {
            traceStore.recordDecision(currentSessionId(), 1, "NATIVE_TOOL_CALL",
                    name, Map.of("rawInput", toolInput == null ? "" : toolInput), result,
                    null, null, false, "SPRING_AI_2_AUTO",
                    elapsed, error == null ? "OK" : "ERROR", error);
        } catch (Exception e) {
            log.debug("记录 NATIVE_TOOL_CALL trace 失败: {}", e.getMessage());
        }
    }

    /**
     * 从 MDC 取当前会话 id，<b>保证不返回 null</b>。
     *
     * <p>【踩过的坑】这里最初直接写死 {@code null}（理由是「工具执行在框架内部循环里，拿不到会话」），
     * 结果 trace 里出现了 sessionId 为 null 的记录 —— 决策链路页面按 sessionId 分组，
     * {@code sessions.set(null, ...)} 之后再取 {@code sid.length} 直接抛
     * 「Cannot read properties of null」，<b>整个页面白屏</b>。
     * 一条脏数据打挂一个页面，所以这里必须兜底。
     */
    private String currentSessionId() {
        String sessionId = MDC.get(TraceIdFilter.SESSION_ID_KEY);
        if (sessionId != null && !sessionId.isBlank()) return sessionId;
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_KEY);
        return traceId != null && !traceId.isBlank() ? "trace-" + traceId : "unknown-session";
    }

    private interface ToolInvocation {
        String execute();
    }
}
