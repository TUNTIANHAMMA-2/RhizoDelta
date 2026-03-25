package com.rhizodelta.api;

import com.rhizodelta.config.AuthenticatedUser;
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
import org.springframework.security.core.Authentication;
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
    public ResponseEntity<ApiResponse<DecisionResult>> merge(
            @RequestBody MergeDecisionCommand command,
            Authentication authentication
    ) {
        DecisionResult result = decisionService.executeMerge(
                withOperatorId(command, requireAuthenticatedUser(authentication).sub()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result));
    }

    @PostMapping("/branch")
    public ResponseEntity<ApiResponse<DecisionResult>> branch(
            @RequestBody BranchDecisionCommand command,
            Authentication authentication
    ) {
        DecisionResult result = decisionService.executeBranch(
                withOperatorId(command, requireAuthenticatedUser(authentication).sub()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result));
    }

    @PostMapping("/inject")
    public ResponseEntity<ApiResponse<DecisionResult>> inject(
            @RequestBody InjectDecisionCommand command,
            Authentication authentication
    ) {
        DecisionResult result = decisionService.executeInject(
                withOperatorId(command, requireAuthenticatedUser(authentication).sub()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result));
    }

    @PostMapping("/materialize")
    public ResponseEntity<ApiResponse<DecisionResult>> materialize(
            @RequestBody MaterializeDecisionCommand command,
            Authentication authentication
    ) {
        DecisionResult result = decisionService.executeMaterialize(
                withOperatorId(command, requireAuthenticatedUser(authentication).sub()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result));
    }

    @PostMapping("/fork")
    public ResponseEntity<ApiResponse<ForkDecisionResult>> fork(
            @RequestBody ForkDecisionCommand command,
            Authentication authentication
    ) {
        ForkDecisionResult result = decisionService.executeFork(
                withOperatorId(command, requireAuthenticatedUser(authentication).sub()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result));
    }

    @PostMapping("/cross-synth")
    public ResponseEntity<ApiResponse<DecisionResult>> crossSynth(
            @RequestBody CrossSynthDecisionCommand command,
            Authentication authentication
    ) {
        DecisionResult result = decisionService.executeCrossSynth(
                withOperatorId(command, requireAuthenticatedUser(authentication).sub()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result));
    }

    @PostMapping("/join")
    public ResponseEntity<ApiResponse<DecisionResult>> join(
            @RequestBody JoinDecisionCommand command,
            Authentication authentication
    ) {
        DecisionResult result = decisionService.executeJoin(
                withOperatorId(command, requireAuthenticatedUser(authentication).sub()));
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

    private static AuthenticatedUser requireAuthenticatedUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new IllegalStateException("authenticated user principal not available");
        }
        return user;
    }

    private static MergeDecisionCommand withOperatorId(MergeDecisionCommand command, String operatorId) {
        return new MergeDecisionCommand(
                command.decision_id(),
                command.request_id(),
                command.source_node_id(),
                command.agent_version(),
                command.summary_content(),
                command.synthesized_from(),
                command.operator_type(),
                operatorId,
                command.reason()
        );
    }

    private static BranchDecisionCommand withOperatorId(BranchDecisionCommand command, String operatorId) {
        return new BranchDecisionCommand(
                command.decision_id(),
                command.request_id(),
                command.source_node_id(),
                command.content(),
                command.author_id(),
                command.operator_type(),
                operatorId,
                command.reason()
        );
    }

    private static InjectDecisionCommand withOperatorId(InjectDecisionCommand command, String operatorId) {
        return new InjectDecisionCommand(
                command.decision_id(),
                command.request_id(),
                command.source_node_id(),
                command.content(),
                command.author_id(),
                command.operator_type(),
                operatorId,
                command.reason()
        );
    }

    private static MaterializeDecisionCommand withOperatorId(MaterializeDecisionCommand command, String operatorId) {
        return new MaterializeDecisionCommand(
                command.decision_id(),
                command.request_id(),
                command.source_node_id(),
                command.content(),
                command.operator_type(),
                operatorId,
                command.reason()
        );
    }

    private static ForkDecisionCommand withOperatorId(ForkDecisionCommand command, String operatorId) {
        return new ForkDecisionCommand(
                command.operation_id(),
                command.request_id(),
                command.source_node_id(),
                command.branches(),
                command.operator_type(),
                operatorId,
                command.reason()
        );
    }

    private static CrossSynthDecisionCommand withOperatorId(CrossSynthDecisionCommand command, String operatorId) {
        return new CrossSynthDecisionCommand(
                command.decision_id(),
                command.request_id(),
                command.source_result_ids(),
                command.content(),
                command.operator_type(),
                operatorId,
                command.reason()
        );
    }

    private static JoinDecisionCommand withOperatorId(JoinDecisionCommand command, String operatorId) {
        return new JoinDecisionCommand(
                command.decision_id(),
                command.request_id(),
                command.source_node_ids(),
                command.summary_content(),
                command.agent_version(),
                command.operator_type(),
                operatorId,
                command.reason()
        );
    }
}
