package com.example.ragassitant.interfaces.rest;

import com.example.ragassitant.infrastructure.governance.PendingActionService;
import com.example.ragassitant.infrastructure.governance.ToolGovernance;
import com.example.ragassitant.infrastructure.persistence.jpa.PendingToolActionEntity;
import com.example.ragassitant.infrastructure.persistence.jpa.ToolAuditLogEntity;
import com.example.ragassitant.infrastructure.persistence.jpa.ToolAuditLogRepository;
import com.example.ragassitant.interfaces.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 高风险工具操作的人工确认，以及工具审计查询。
 */
@RestController
@RequestMapping("/api/agent")
public class ToolActionController {

    private final PendingActionService pendingActionService;
    private final ToolAuditLogRepository auditRepository;

    public ToolActionController(PendingActionService pendingActionService, ToolAuditLogRepository auditRepository) {
        this.pendingActionService = pendingActionService;
        this.auditRepository = auditRepository;
    }

    @GetMapping("/actions")
    public ApiResponse<List<PendingToolActionEntity>> list(
            @RequestParam(value = "status", required = false) PendingToolActionEntity.Status status) {
        return ApiResponse.ok("待确认操作", pendingActionService.list(status));
    }

    @PostMapping("/actions/{id}/approve")
    public ApiResponse<PendingToolActionEntity> approve(@PathVariable String id) {
        try {
            return ApiResponse.ok("已确认并执行", pendingActionService.approve(id, ToolGovernance.currentUser()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.fail("ACTION_REJECTED", e.getMessage());
        }
    }

    @PostMapping("/actions/{id}/reject")
    public ApiResponse<PendingToolActionEntity> reject(@PathVariable String id,
                                                       @RequestBody(required = false) Map<String, String> body) {
        try {
            String reason = body == null ? null : body.get("reason");
            return ApiResponse.ok("已驳回", pendingActionService.reject(id, ToolGovernance.currentUser(), reason));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.fail("ACTION_REJECTED", e.getMessage());
        }
    }

    @GetMapping("/audit")
    public ApiResponse<List<ToolAuditLogEntity>> audit() {
        return ApiResponse.ok("最近工具审计", auditRepository.findTop100ByOrderByCreatedAtDesc());
    }
}
