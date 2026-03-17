package com.rhizodelta.api;

import com.rhizodelta.domain.decision.BranchDecisionCommand;
import com.rhizodelta.domain.decision.CrossSynthDecisionCommand;
import com.rhizodelta.domain.decision.DecisionResult;
import com.rhizodelta.domain.decision.ForkDecisionCommand;
import com.rhizodelta.domain.decision.ForkDecisionResult;
import com.rhizodelta.domain.decision.InjectDecisionCommand;
import com.rhizodelta.domain.decision.JoinDecisionCommand;
import com.rhizodelta.domain.decision.MaterializeDecisionCommand;
import com.rhizodelta.service.DecisionService;
import com.rhizodelta.domain.decision.MergeDecisionCommand;
import com.rhizodelta.domain.decision.RollbackResult;
import com.rhizodelta.service.RollbackService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/decisions")
public class DecisionController {
    private final DecisionService decisionService;
    private final RollbackService rollbackService;

    public DecisionController(DecisionService decisionService, RollbackService rollbackService) {
        this.decisionService = decisionService;
        this.rollbackService = rollbackService;
    }

    @PostMapping("/merge")
    public ResponseEntity<ApiResponse<DecisionResult>> merge(@RequestBody MergeDecisionCommand command) {
        DecisionResult result = decisionService.executeMerge(command);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result));
    }

    @PostMapping("/branch")
    public ResponseEntity<ApiResponse<DecisionResult>> branch(@RequestBody BranchDecisionCommand command) {
        DecisionResult result = decisionService.executeBranch(command);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result));
    }

    @PostMapping("/inject")
    public ResponseEntity<ApiResponse<DecisionResult>> inject(@RequestBody InjectDecisionCommand command) {
        DecisionResult result = decisionService.executeInject(command);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result));
    }

    @PostMapping("/materialize")
    public ResponseEntity<ApiResponse<DecisionResult>> materialize(@RequestBody MaterializeDecisionCommand command) {
        DecisionResult result = decisionService.executeMaterialize(command);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result));
    }

    @PostMapping("/fork")
    public ResponseEntity<ApiResponse<ForkDecisionResult>> fork(@RequestBody ForkDecisionCommand command) {
        ForkDecisionResult result = decisionService.executeFork(command);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result));
    }

    @PostMapping("/cross-synth")
    public ResponseEntity<ApiResponse<DecisionResult>> crossSynth(@RequestBody CrossSynthDecisionCommand command) {
        DecisionResult result = decisionService.executeCrossSynth(command);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result));
    }

    @PostMapping("/join")
    public ResponseEntity<ApiResponse<DecisionResult>> join(@RequestBody JoinDecisionCommand command) {
        DecisionResult result = decisionService.executeJoin(command);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result));
    }

    @PostMapping("/{decision_id}/rollback")
    public ResponseEntity<ApiResponse<RollbackResult>> rollback(
            @PathVariable("decision_id") String decisionId
    ) {
        RollbackResult result = rollbackService.rollbackDecision(decisionId);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/fork/{operation_id}/rollback")
    public ResponseEntity<ApiResponse<RollbackService.ForkRollbackResult>> rollbackFork(
            @PathVariable("operation_id") String operationId
    ) {
        RollbackService.ForkRollbackResult result = rollbackService.rollbackForkByOperationId(operationId);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
