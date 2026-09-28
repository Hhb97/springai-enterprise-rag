package com.example.ragassitant.infrastructure.governance;

import com.example.ragassitant.common.filter.BasicAuthFilter;
import com.example.ragassitant.common.trace.TraceIdFilter;
import com.example.ragassitant.infrastructure.observability.AgentMetrics;
import com.example.ragassitant.infrastructure.persistence.jpa.PendingToolActionEntity;
import com.example.ragassitant.infrastructure.persistence.jpa.PendingToolActionRepository;
import com.example.ragassitant.infrastructure.persistence.jpa.ToolAuditLogEntity;
import com.example.ragassitant.infrastructure.persistence.jpa.ToolAuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 工具治理：<b>所有</b>工具执行路径（原生 tool_calls、FORCE_EXEC 反射、Plan/ReAct/Graph）共用的一道闸。
 *
 * <p>判定顺序：
 * <ol>
 *   <li>工具被禁用 → 拒绝；</li>
 *   <li>配置了 allowedUsers 且当前用户不在其中 → 拒绝；</li>
 *   <li>DANGEROUS → 不执行，生成待确认记录，返回确认单号（由人在 {@code /api/agent/actions} 确认后执行）；</li>
 *   <li>WRITE → 幂等窗口内同会话同参数的重复调用直接返回上次结果（LLM 重试 / 用户连点都会造成重复写）；</li>
 *   <li>执行，按配置写审计。</li>
 * </ol>
 *
 * <p>拒绝和待确认都<b>以文本返回</b>而不是抛异常：返回给模型的文本会被它转述给用户
 * （「该操作需要人工确认，单号 xxx」），抛异常则只会得到一句笼统的失败。
 *
 * <p>当前用户取自 MDC（{@link BasicAuthFilter#MDC_USER_KEY}）。Spring AI 流式调用内部的工具执行
 * 可能跑在 Reactor 线程上拿不到 MDC，此时视为 {@code anonymous} —— 对配置了 allowedUsers 的工具
 * 这意味着<b>拒绝</b>（失败方向是安全的）。
 */
@Component
public class ToolGovernance {

    private static final Logger log = LoggerFactory.getLogger(ToolGovernance.class);
    private static final int PREVIEW_LIMIT = 2000;

    private final GovernanceProperties props;
    private final ToolAuditLogRepository auditRepository;
    private final PendingToolActionRepository pendingRepository;
    private final AgentMetrics metrics;
    private final LongSupplier clock;

    /** 幂等缓存：key = 会话|工具|参数，value = (时间, 结果)。 */
    private final Map<String, CachedResult> idempotencyCache = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ToolGovernance(GovernanceProperties props,
                          ObjectProvider<ToolAuditLogRepository> auditRepository,
                          ObjectProvider<PendingToolActionRepository> pendingRepository,
                          AgentMetrics metrics) {
        this(props, auditRepository.getIfAvailable(), pendingRepository.getIfAvailable(), metrics,
                System::currentTimeMillis);
    }

    public ToolGovernance(GovernanceProperties props,
                          ToolAuditLogRepository auditRepository,
                          PendingToolActionRepository pendingRepository,
                          AgentMetrics metrics,
                          LongSupplier clock) {
        this.props = props;
        this.auditRepository = auditRepository;
        this.pendingRepository = pendingRepository;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** 不做任何限制的实例，供单测和未接入治理的构造路径使用。 */
    public static ToolGovernance permissive() {
        return new ToolGovernance(new GovernanceProperties(), null, null, AgentMetrics.noop(),
                System::currentTimeMillis);
    }

    public ToolRisk riskOf(String tool) {
        GovernanceProperties.ToolPolicy p = props.getTools().get(tool);
        return p != null && p.getRisk() != null ? p.getRisk() : props.getDefaultRisk();
    }

    /**
     * 该工具的输出是否直接作为最终回答（跳过模型总结轮）。只有只读工具可以直出：
     * 写操作 / 危险操作的返回需要模型结合上下文向用户解释。
     */
    public boolean returnDirect(String tool) {
        if (!props.getDirectOutput().isEnabled()) return false;
        if (riskOf(tool) != ToolRisk.READ_ONLY) return false;
        GovernanceProperties.ToolPolicy p = props.getTools().get(tool);
        if (p != null && p.getReturnDirect() != null) return p.getReturnDirect();
        return props.getDirectOutput().isDefaultForReadOnly();
    }

    /**
     * 经治理后执行工具。
     *
     * @param tool      工具名
     * @param arguments 参数的规范化文本（JSON），用于幂等键、待确认记录和审计
     * @param source    调用来源：native / registry
     * @param invoker   真正执行工具的动作
     */
    public String execute(String tool, String arguments, String source, Supplier<String> invoker) {
        String user = currentUser();
        String sessionId = MDC.get(TraceIdFilter.SESSION_ID_KEY);
        ToolRisk risk = riskOf(tool);
        GovernanceProperties.ToolPolicy policy = props.getTools().get(tool);

        if (policy != null && !policy.isEnabled()) {
            audit(tool, risk, source, arguments, user, sessionId, "DENIED", "工具已禁用", 0, true);
            metrics.toolCall(tool, source, "denied");
            return "❌ 工具 " + tool + " 已被管理员禁用，无法执行。请告知用户该能力暂不可用。";
        }
        if (policy != null && !policy.getAllowedUsers().isEmpty() && !policy.getAllowedUsers().contains(user)) {
            audit(tool, risk, source, arguments, user, sessionId, "DENIED", "用户无权限", 0, true);
            metrics.toolCall(tool, source, "denied");
            return "❌ 当前用户无权执行 " + tool + "。请告知用户联系管理员开通权限。";
        }
        if (risk == ToolRisk.DANGEROUS) {
            String id = createPending(tool, arguments, user, sessionId);
            audit(tool, risk, source, arguments, user, sessionId, "PENDING_APPROVAL", "确认单 " + id, 0, true);
            metrics.toolCall(tool, source, "pending_approval");
            return "⏸️ 操作 " + tool + " 属于高风险操作，【尚未执行】，已提交人工确认，确认单号：" + id
                    + "。请如实告知用户：需要有权限的人员在管理端确认后才会执行，不要声称已经执行成功。";
        }

        String idemKey = null;
        if (risk == ToolRisk.WRITE) {
            idemKey = sessionId + "|" + tool + "|" + arguments;
            CachedResult cached = idempotencyCache.get(idemKey);
            long now = clock.getAsLong();
            if (cached != null && now - cached.at < props.getIdempotency().getWindowSeconds() * 1000) {
                audit(tool, risk, source, arguments, user, sessionId, "DUPLICATE", cached.result, 0, true);
                metrics.toolCall(tool, source, "duplicate");
                log.info("🛡️ 幂等命中，跳过重复写操作: tool={}, session={}", tool, sessionId);
                return cached.result;
            }
        }

        long start = clock.getAsLong();
        try {
            String result = invoker.get();
            long elapsed = clock.getAsLong() - start;
            if (idemKey != null) {
                evictExpired();
                idempotencyCache.put(idemKey, new CachedResult(clock.getAsLong(), result));
            }
            audit(tool, risk, source, arguments, user, sessionId, "OK", result, elapsed, false);
            return result;
        } catch (RuntimeException e) {
            audit(tool, risk, source, arguments, user, sessionId, "ERROR", e.toString(),
                    clock.getAsLong() - start, false);
            throw e;
        }
    }

    public static String currentUser() {
        String user = MDC.get(BasicAuthFilter.MDC_USER_KEY);
        return user == null || user.isBlank() ? "anonymous" : user;
    }

    public long approvalTtlMinutes() {
        return props.getApproval().getTtlMinutes();
    }

    private String createPending(String tool, String arguments, String user, String sessionId) {
        String id = UUID.randomUUID().toString();
        if (pendingRepository == null) {
            log.warn("🛡️ 未配置待确认存储，高风险操作 {} 被直接拦截", tool);
            return id;
        }
        PendingToolActionEntity e = new PendingToolActionEntity();
        e.setId(id);
        e.setToolName(tool);
        e.setArguments(arguments);
        e.setSessionId(sessionId);
        e.setRequestedBy(user);
        e.setStatus(PendingToolActionEntity.Status.PENDING);
        LocalDateTime now = LocalDateTime.now();
        e.setCreatedAt(now);
        e.setExpiresAt(now.plusMinutes(props.getApproval().getTtlMinutes()));
        pendingRepository.save(e);
        log.info("🛡️ 高风险操作待确认: id={}, tool={}, user={}", id, tool, user);
        return id;
    }

    /**
     * 写审计。{@code alwaysForReadOnly} 为 true 表示「拦截类」事件（拒绝、待确认、重复），
     * 即使是只读工具也要记录 —— 被拦下来的调用正是事后最需要追查的。
     */
    void audit(String tool, ToolRisk risk, String source, String arguments, String user, String sessionId,
               String outcome, String result, long elapsed, boolean alwaysForReadOnly) {
        if (auditRepository == null || !props.getAudit().isEnabled()) return;
        if (risk == ToolRisk.READ_ONLY && !props.getAudit().isIncludeReadOnly() && !alwaysForReadOnly) return;
        try {
            ToolAuditLogEntity e = new ToolAuditLogEntity();
            e.setToolName(tool);
            e.setRisk(risk.name());
            e.setSource(source);
            e.setArguments(arguments);
            e.setUsername(user);
            e.setSessionId(sessionId);
            e.setTraceId(MDC.get(TraceIdFilter.TRACE_ID_KEY));
            e.setOutcome(outcome);
            e.setResultPreview(result == null ? null
                    : result.length() > PREVIEW_LIMIT ? result.substring(0, PREVIEW_LIMIT) + "…" : result);
            e.setDurationMs(elapsed);
            auditRepository.save(e);
        } catch (Exception ex) {
            // 审计失败要大声报出来，但不反过来打断业务 —— 已经执行完的写操作回滚不了
            log.error("🛡️ 写工具审计失败: tool={}, outcome={}", tool, outcome, ex);
        }
    }

    private void evictExpired() {
        long cutoff = clock.getAsLong() - props.getIdempotency().getWindowSeconds() * 1000;
        idempotencyCache.values().removeIf(c -> c.at < cutoff);
    }

    private record CachedResult(long at, String result) {}
}
