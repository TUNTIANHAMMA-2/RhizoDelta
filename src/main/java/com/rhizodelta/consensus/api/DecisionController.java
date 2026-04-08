package com.rhizodelta.consensus.api;

import com.rhizodelta.infrastructure.web.ApiResponse;
import com.rhizodelta.infrastructure.security.model.AuthenticatedUser;
import com.rhizodelta.consensus.domain.decision.BranchDecisionCommand;
import com.rhizodelta.consensus.domain.decision.CrossSynthDecisionCommand;
import com.rhizodelta.consensus.domain.decision.DecisionResult;
import com.rhizodelta.consensus.domain.decision.ForkDecisionCommand;
import com.rhizodelta.consensus.domain.decision.ForkDecisionResult;
import com.rhizodelta.consensus.domain.decision.InjectDecisionCommand;
import com.rhizodelta.consensus.domain.decision.JoinDecisionCommand;
import com.rhizodelta.consensus.domain.decision.MaterializeDecisionCommand;
import com.rhizodelta.consensus.service.DecisionService;
import com.rhizodelta.consensus.domain.decision.MergeDecisionCommand;
import com.rhizodelta.consensus.domain.decision.RollbackResult;
import com.rhizodelta.consensus.service.RollbackService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 暴露共识决策与回滚的 HTTP 入口。
 *
 * <p>该控制器负责接收外部提交的决策命令，并把认证主体绑定为最终操作者，
 * 再委托给 {@link DecisionService} 或 {@link RollbackService} 执行真正的图谱变更。
 *
 * <p><b>关键副作用</b>：
 * <ul>
 *   <li>各类决策接口会触发数据库写入，并在事务提交后继续触发事件监听链路。</li>
 *   <li>回滚接口会尝试删除关系或软删除节点，可能因下游依赖而失败。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/decisions")
public class DecisionController {
    private final DecisionService decisionService;
    private final RollbackService rollbackService;

    public DecisionController(DecisionService decisionService, RollbackService rollbackService) {
        this.decisionService = decisionService;
        this.rollbackService = rollbackService;
    }

    /**
     * 提交一次合并决策。
     *
     * <p>该接口会用当前认证用户覆盖命令中的操作者标识，防止客户端伪造执行者身份。
     */
    @PostMapping("/merge")
    public ResponseEntity<ApiResponse<DecisionResult>> merge(
            @RequestBody MergeDecisionCommand command,
            Authentication authentication
    ) {
        DecisionResult result = decisionService.executeMerge(
                withOperatorId(command, requireAuthenticatedUser(authentication).sub()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result));
    }

    /**
     * 提交一次分支决策。
     */
    @PostMapping("/branch")
    public ResponseEntity<ApiResponse<DecisionResult>> branch(
            @RequestBody BranchDecisionCommand command,
            Authentication authentication
    ) {
        DecisionResult result = decisionService.executeBranch(
                withOperatorId(command, requireAuthenticatedUser(authentication).sub()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result));
    }

    /**
     * 提交一次注入决策。
     */
    @PostMapping("/inject")
    public ResponseEntity<ApiResponse<DecisionResult>> inject(
            @RequestBody InjectDecisionCommand command,
            Authentication authentication
    ) {
        DecisionResult result = decisionService.executeInject(
                withOperatorId(command, requireAuthenticatedUser(authentication).sub()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result));
    }

    /**
     * 提交一次物化决策。
     */
    @PostMapping("/materialize")
    public ResponseEntity<ApiResponse<DecisionResult>> materialize(
            @RequestBody MaterializeDecisionCommand command,
            Authentication authentication
    ) {
        DecisionResult result = decisionService.executeMaterialize(
                withOperatorId(command, requireAuthenticatedUser(authentication).sub()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result));
    }

    /**
     * 提交一次分叉决策。
     */
    @PostMapping("/fork")
    public ResponseEntity<ApiResponse<ForkDecisionResult>> fork(
            @RequestBody ForkDecisionCommand command,
            Authentication authentication
    ) {
        ForkDecisionResult result = decisionService.executeFork(
                withOperatorId(command, requireAuthenticatedUser(authentication).sub()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result));
    }

    /**
     * 提交一次跨结果综合决策。
     */
    @PostMapping("/cross-synth")
    public ResponseEntity<ApiResponse<DecisionResult>> crossSynth(
            @RequestBody CrossSynthDecisionCommand command,
            Authentication authentication
    ) {
        DecisionResult result = decisionService.executeCrossSynth(
                withOperatorId(command, requireAuthenticatedUser(authentication).sub()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result));
    }

    /**
     * 提交一次汇合决策。
     */
    @PostMapping("/join")
    public ResponseEntity<ApiResponse<DecisionResult>> join(
            @RequestBody JoinDecisionCommand command,
            Authentication authentication
    ) {
        DecisionResult result = decisionService.executeJoin(
                withOperatorId(command, requireAuthenticatedUser(authentication).sub()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result));
    }

    /**
     * 回滚一条普通决策。
     *
     * <p><b>注意</b>：返回成功仅表示回滚动作已执行；若仍存在下游依赖，会在服务层抛出异常阻断回滚。
     */
    @PostMapping("/{decision_id}/rollback")
    public ResponseEntity<ApiResponse<RollbackResult>> rollback(
            @PathVariable("decision_id") String decisionId
    ) {
        RollbackResult result = rollbackService.rollbackDecision(decisionId);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * 按操作批次回滚一次分叉操作。
     */
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
                command.reason(),
                command.contributor_node_ids()
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
