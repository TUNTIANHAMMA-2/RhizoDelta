package com.rhizodelta.consensus.api;

import com.rhizodelta.infrastructure.web.ApiResponse;
import com.rhizodelta.consensus.domain.audit.AuditDetail;
import com.rhizodelta.consensus.domain.audit.AuditListResponse;
import com.rhizodelta.consensus.service.AuditService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 提供决策审计查询入口。
 *
 * <p>该控制器只负责读取已经发生过的决策记录，不会触发任何图谱写操作。
 */
@RestController
@RequestMapping("/api/audit/decisions")
public class AuditController {
    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * 按条件分页查询决策审计记录。
     *
     * <p>支持按类型、操作者、节点以及时间窗口过滤，并使用游标继续翻页。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<AuditListResponse>> listDecisions(
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "operator_id", required = false) String operatorId,
            @RequestParam(value = "node_id", required = false) String nodeId,
            @RequestParam(value = "since", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
            @RequestParam(value = "until", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant until,
            @RequestParam(value = "after", required = false) String after,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        AuditListResponse response = auditService.listDecisions(type, operatorId, nodeId, since, until, after, limit);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * 返回单条决策的审计详情。
     */
    @GetMapping("/{decision_id}")
    public ResponseEntity<ApiResponse<AuditDetail>> getDecisionDetail(
            @PathVariable("decision_id") String decisionId
    ) {
        AuditDetail response = auditService.getDecisionDetail(decisionId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
