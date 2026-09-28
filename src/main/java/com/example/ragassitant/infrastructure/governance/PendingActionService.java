package com.example.ragassitant.infrastructure.governance;

import com.example.ragassitant.infrastructure.agent.ToolCatalog;
import com.example.ragassitant.infrastructure.persistence.jpa.PendingToolActionEntity;
import com.example.ragassitant.infrastructure.persistence.jpa.PendingToolActionEntity.Status;
import com.example.ragassitant.infrastructure.persistence.jpa.PendingToolActionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 待确认高风险操作的处理：列出、确认执行、驳回、过期。
 *
 * <p>确认后执行走 {@link ToolCatalog#toolCallbacks()} 里的<b>裸</b>回调，而不是带治理的回调 ——
 * 否则 DANGEROUS 工具会再次被拦成待确认，永远执行不了。审计以 source=approval 单独记录。
 */
@Service
public class PendingActionService {

    private static final Logger log = LoggerFactory.getLogger(PendingActionService.class);

    private final PendingToolActionRepository repository;
    private final ToolCatalog toolCatalog;
    private final ToolGovernance governance;

    public PendingActionService(PendingToolActionRepository repository, ToolCatalog toolCatalog,
                                ToolGovernance governance) {
        this.repository = repository;
        this.toolCatalog = toolCatalog;
        this.governance = governance;
    }

    public List<PendingToolActionEntity> list(Status status) {
        return status == null ? repository.findTop100ByOrderByCreatedAtDesc()
                : repository.findByStatusOrderByCreatedAtDesc(status);
    }

    public PendingToolActionEntity approve(String id, String approver) {
        PendingToolActionEntity action = requirePending(id);
        if (approver != null && approver.equals(action.getRequestedBy()) && !"anonymous".equals(approver)) {
            // 四眼原则：发起人不能自己确认自己的高风险操作
            throw new IllegalStateException("发起人不能确认自己提交的高风险操作");
        }
        var callback = toolCatalog.toolCallbacks().stream()
                .filter(cb -> cb.getToolDefinition().name().equals(action.getToolName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("工具已不存在: " + action.getToolName()));

        long start = System.currentTimeMillis();
        String result;
        try {
            result = callback.call(action.getArguments() == null ? "{}" : action.getArguments());
            action.setStatus(Status.EXECUTED);
        } catch (RuntimeException e) {
            result = e.toString();
            action.setStatus(Status.FAILED);
        }
        action.setResult(result);
        action.setDecidedBy(approver);
        action.setDecidedAt(LocalDateTime.now());
        repository.save(action);
        governance.audit(action.getToolName(), governance.riskOf(action.getToolName()), "approval",
                action.getArguments(), approver, action.getSessionId(),
                action.getStatus() == Status.EXECUTED ? "OK" : "ERROR", result,
                System.currentTimeMillis() - start, true);
        log.info("🛡️ 高风险操作已确认: id={}, tool={}, approver={}, status={}",
                id, action.getToolName(), approver, action.getStatus());
        return action;
    }

    public PendingToolActionEntity reject(String id, String approver, String reason) {
        PendingToolActionEntity action = requirePending(id);
        action.setStatus(Status.REJECTED);
        action.setDecidedBy(approver);
        action.setDecidedAt(LocalDateTime.now());
        action.setResult(reason == null ? "已驳回" : "已驳回：" + reason);
        repository.save(action);
        governance.audit(action.getToolName(), governance.riskOf(action.getToolName()), "approval",
                action.getArguments(), approver, action.getSessionId(), "REJECTED", reason, 0, true);
        return action;
    }

    @Scheduled(fixedDelay = 60_000)
    public void expireStale() {
        LocalDateTime now = LocalDateTime.now();
        for (PendingToolActionEntity a : repository.findByStatusOrderByCreatedAtDesc(Status.PENDING)) {
            if (a.getExpiresAt() != null && a.getExpiresAt().isBefore(now)) {
                a.setStatus(Status.EXPIRED);
                a.setDecidedAt(now);
                repository.save(a);
            }
        }
    }

    private PendingToolActionEntity requirePending(String id) {
        PendingToolActionEntity action = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("确认单不存在: " + id));
        if (action.getStatus() != Status.PENDING) {
            throw new IllegalStateException("确认单已处理，当前状态: " + action.getStatus());
        }
        if (action.getExpiresAt() != null && action.getExpiresAt().isBefore(LocalDateTime.now())) {
            action.setStatus(Status.EXPIRED);
            repository.save(action);
            throw new IllegalStateException("确认单已过期");
        }
        return action;
    }
}
