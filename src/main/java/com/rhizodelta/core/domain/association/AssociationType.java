package com.rhizodelta.core.domain.association;

/**
 * 定义核心关联关系的业务类型。
 *
 * <p>该枚举用于约束当前系统允许创建和查询的关联关系种类，
 * 避免调用方直接依赖底层图关系名称字符串。
 */
public enum AssociationType {
    CONCEPTUAL_OVERLAP,
    RELATES_TO
}
