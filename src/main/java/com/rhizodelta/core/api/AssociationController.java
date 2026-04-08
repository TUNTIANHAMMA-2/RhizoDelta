package com.rhizodelta.core.api;

import com.rhizodelta.infrastructure.web.ApiResponse;
import com.rhizodelta.infrastructure.security.model.AuthenticatedUser;
import com.rhizodelta.core.domain.association.AssociationResult;
import com.rhizodelta.core.service.AssociationService;
import com.rhizodelta.core.domain.association.CreateAssociationCommand;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 提供图谱节点关联关系的写接口。
 *
 * <p>该控制器位于 {@code com.rhizodelta.core.api}，负责把外部的关联创建与删除请求
 * 收敛为对 {@link AssociationService} 的调用，并在入口层绑定认证用户身份。
 *
 * <p><b>关键副作用</b>：
 * <ul>
 *   <li>创建关联会写 Neo4j 关系边。</li>
 *   <li>删除关联会物理删除允许回收的关联关系。</li>
 * </ul>
 *
 * <p><b>隐藏约束</b>：
 * <ul>
 *   <li>创建时传入的 {@code creator_id} 不可信，最终会被当前认证用户覆盖。</li>
 *   <li>删除接口没有显式鉴权逻辑，真正的权限边界由安全配置决定。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/associations")
public class AssociationController {
    private final AssociationService associationService;

    public AssociationController(AssociationService associationService) {
        this.associationService = associationService;
    }

    /**
     * 创建或幂等返回一条节点关联。
     *
     * <p>该方法存在的意义，是把“谁发起了这条关联”从客户端输入中剥离出来，
     * 强制以认证主体作为最终的 {@code creator_id}，从而保证关联审计信息可信。
     *
     * <p><b>关键副作用</b>：
     * <ul>
     *   <li>会调用 {@link AssociationService#createAssociation(CreateAssociationCommand)} 写入图关系。</li>
     *   <li>当目标关联已存在时，会返回 {@code 200 OK} 而不是重复创建。</li>
     * </ul>
     *
     * <p>
     *
     * @param command 关联创建命令；其中 {@code creator_id} 会被认证用户覆盖。
     * @param authentication 当前请求的认证主体。
     * @return 统一响应，包含关联信息以及创建/复用后的 HTTP 状态。
     */
    @PostMapping
    public ResponseEntity<ApiResponse<AssociationResult>> create(
            @Valid @RequestBody CreateAssociationCommand command,
            Authentication authentication
    ) {
        AuthenticatedUser authenticatedUser = requireAuthenticatedUser(authentication);
        CreateAssociationCommand authenticatedCommand = new CreateAssociationCommand(
                command.source_node_id(),
                command.target_node_id(),
                command.type(),
                authenticatedUser.sub(),
                command.reason(),
                command.confidence()
        );
        AssociationService.CreateAssociationOutcome outcome = associationService.createAssociation(authenticatedCommand);
        HttpStatus status = outcome.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(outcome.association()));
    }

    /**
     * 删除指定的关联关系。
     *
     * <p>该方法存在的意义，是暴露一个显式的关系撤销入口，使上层不需要直接感知
     * 图数据库中的关系类型和删除细节。
     *
     * <p><b>关键副作用</b>：
     * <ul>
     *   <li>会调用 {@link AssociationService#deleteAssociation(UUID)} 删除底层关系边。</li>
     *   <li>若关联不存在，会由服务层抛出异常并映射为错误响应。</li>
     * </ul>
     *
     * <p>
     *
     * @param associationId 要删除的关联 ID。
     * @return 删除结果回执。
     */
    @DeleteMapping("/{associationId}")
    public ResponseEntity<ApiResponse<DeleteAssociationResponse>> delete(@PathVariable("associationId") UUID associationId) {
        AssociationService.DeleteAssociationOutcome outcome = associationService.deleteAssociation(associationId);
        DeleteAssociationResponse response = new DeleteAssociationResponse(outcome.association_id(), outcome.deleted());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * 表示关联删除结果。
     *
     * <p>该对象用于让调用方区分“删除成功”与“幂等无变化”等不同结果语义。
     */
    public record DeleteAssociationResponse(UUID association_id, boolean deleted) {
    }

    private static AuthenticatedUser requireAuthenticatedUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new IllegalStateException("authenticated user principal not available");
        }
        return user;
    }
}
