package com.rhizodelta.consensus.api;

import com.rhizodelta.consensus.service.AuditRelationService;
import com.rhizodelta.infrastructure.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 暴露审计关系的查询接口。
 *
 * <ul>
 *   <li>{@code GET /api/decisions/{decisionId}/reviews} —— 该决策的全部 REVIEWED 记录</li>
 *   <li>{@code GET /api/nodes/{nodeId}/operations}      —— 该节点的全部 OPERATED 记录</li>
 * </ul>
 *
 * <p>这两条历史在治理（"谁改过这条决策"、"谁回滚了那个节点"）与可观测性场景中是高频引用，
 * 没有它们 Phase 3 引入的 REVIEWED/OPERATED 边只对底层图遍历有用，外部消费者拿不到数据。
 */
@RestController
public class AuditRelationController {

    private final AuditRelationService auditRelationService;

    public AuditRelationController(AuditRelationService auditRelationService) {
        this.auditRelationService = auditRelationService;
    }

    @GetMapping("/api/decisions/{decisionId}/reviews")
    public ApiResponse<List<Map<String, Object>>> reviews(@PathVariable("decisionId") String decisionId) {
        return ApiResponse.ok(auditRelationService.getReviewHistory(decisionId));
    }

    @GetMapping("/api/nodes/{nodeId}/operations")
    public ApiResponse<List<Map<String, Object>>> operations(@PathVariable("nodeId") String nodeId) {
        return ApiResponse.ok(auditRelationService.getOperationHistory(nodeId));
    }
}
