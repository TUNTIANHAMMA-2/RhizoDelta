package com.rhizodelta.core.domain.association;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.core.validation.DecisionCommandValidation;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 表示一次节点关联创建请求。
 *
 * <p>该命令对象用于把 API 层输入收敛为稳定的领域参数，供
 * {@link com.rhizodelta.core.service.AssociationService} 执行建边操作。
 *
 * <p><b>关键约束</b>：
 * <ul>
 *   <li>{@code source_node_id} 与 {@code target_node_id} 必须存在，且后续服务层会进一步校验两者不能相同。</li>
 *   <li>{@code confidence} 允许为空，但一旦提供必须落在 {@code [0.0, 1.0]}。</li>
 *   <li>{@code creator_id} 在入口层可能会被认证主体覆盖，因此调用方不应把它视为最终可信身份。</li>
 * </ul>
 */
public record CreateAssociationCommand(
        @JsonProperty("source_node_id")
        @NotNull(message = "source_node_id must not be null")
        UUID source_node_id,
        @JsonProperty("target_node_id")
        @NotNull(message = "target_node_id must not be null")
        UUID target_node_id,
        @JsonProperty("type")
        @NotNull(message = "type must not be null")
        AssociationType type,
        @JsonProperty("creator_id")
        @NotBlank(message = "creator_id must not be blank")
        String creator_id,
        @JsonProperty("reason")
        @NotBlank(message = "reason must not be blank")
        String reason,
        @JsonProperty("confidence")
        @DecimalMin(value = "0.0", message = "confidence must be greater than or equal to 0.0")
        @DecimalMax(value = "1.0", message = "confidence must be less than or equal to 1.0")
        Float confidence
) {
    /**
     * 创建并校验关联命令。
     *
     * <p>这里在模型层提前执行基础校验，是为了尽早失败并保持控制器、服务层对非法输入的处理一致。
     */
    public CreateAssociationCommand {
        source_node_id = DecisionCommandValidation.requireUuid(source_node_id, "source_node_id");
        target_node_id = DecisionCommandValidation.requireUuid(target_node_id, "target_node_id");
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        creator_id = DecisionCommandValidation.requireText(creator_id, "creator_id");
        reason = DecisionCommandValidation.requireText(reason, "reason");
        DecisionCommandValidation.validateConfidence(confidence);
    }
}
