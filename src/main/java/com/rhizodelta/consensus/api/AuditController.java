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

@RestController
@RequestMapping("/api/audit/decisions")
public class AuditController {
    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

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

    @GetMapping("/{decision_id}")
    public ResponseEntity<ApiResponse<AuditDetail>> getDecisionDetail(
            @PathVariable("decision_id") String decisionId
    ) {
        AuditDetail response = auditService.getDecisionDetail(decisionId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
