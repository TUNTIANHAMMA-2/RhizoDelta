package com.rhizodelta.core.validation;

import com.rhizodelta.consensus.domain.decision.DecisionOperatorType;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 提供决策与写模型共享的基础校验工具。
 *
 * <p>该工具类存在的意义，是把跨 {@code core}、{@code consensus}、{@code ai}
 * 层都会复用的参数校验逻辑收敛到统一入口，避免不同命令对象出现不一致的错误语义。
 *
 * <p><b>注意</b>：这里不负责复杂业务规则，只负责文本、UUID、枚举和值域等基础约束。
 */
public final class DecisionCommandValidation {
    private DecisionCommandValidation() {
    }

    /**
     * 断言文本值非空且非空白。
     *
     * <p>该方法用于统一生成“字段不能为空”的错误语义，避免各命令对象重复拼接校验代码。
     *
     * <p>
     *
     * @param value 待校验文本。
     * @param fieldName 业务字段名，用于构造异常消息。
     * @return 原始文本值。
     */
    public static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    /**
     * 断言 UUID 值非空。
     *
     * <p>该方法只负责空值校验，不负责额外的业务存在性判断。
     *
     * <p>
     *
     * @param value 待校验 UUID。
     * @param fieldName 业务字段名。
     * @return 原始 UUID。
     */
    public static UUID requireUuid(UUID value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        return value;
    }

    /**
     * 断言决策操作者类型已提供。
     *
     * <p>该方法的意义，在于把 {@link DecisionOperatorType} 的非空要求显式集中到一处，
     * 供多个决策命令复用。
     *
     * <p>
     *
     * @param value 待校验的操作者类型。
     * @return 原始枚举值。
     */
    public static DecisionOperatorType requireOperatorType(DecisionOperatorType value) {
        if (value == null) {
            throw new IllegalArgumentException("operator_type must not be null");
        }
        return value;
    }

    /**
     * 校验置信度是否落在允许区间内。
     *
     * <p>这里允许 {@code null}，因为部分流程会把置信度视为可选信息；一旦提供，则必须处于
     * {@code [0.0, 1.0]} 区间内。
     *
     * <p>
     *
     * @param confidence 待校验置信度。
     */
    public static void validateConfidence(Float confidence) {
        if (confidence == null) {
            return;
        }
        if (confidence < 0.0f || confidence > 1.0f) {
            throw new IllegalArgumentException("confidence must be within [0.0,1.0]");
        }
    }

    /**
     * 断言 UUID 列表非空且元素完整。
     *
     * <p>该方法主要服务于综合、合并等需要显式来源节点列表的决策命令，
     * 防止调用方传入空集合或包含空洞元素的列表。
     *
     * <p>
     *
     * @param values 待校验 UUID 列表。
     * @return 不可变副本。
     */
    public static List<UUID> requireUuidList(List<UUID> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("synthesized_from must not be empty");
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("synthesized_from must not contain null");
        }
        return List.copyOf(values);
    }
}
