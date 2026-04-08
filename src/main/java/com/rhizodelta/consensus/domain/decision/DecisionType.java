package com.rhizodelta.consensus.domain.decision;

/**
 * 定义系统支持的共识决策类型。
 *
 * <p>该枚举为 API、审计、事件和服务层提供统一的决策分类，
 * 避免不同模块直接依赖关系类型或字符串常量。
 */
public enum DecisionType {
    MERGE,
    BRANCH,
    INJECT,
    MATERIALIZE,
    FORK,
    CROSS_SYNTH,
    JOIN
}
