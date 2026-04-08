package com.rhizodelta.consensus.domain.exception;

/**
 * 表示决策执行将破坏图的 DAG 完整性。
 *
 * <p>该异常用于显式暴露诸如自环、回路或非法拓扑演化等结构性错误，
 * 防止服务层在图约束被破坏后继续提交写操作。
 */
public class DagIntegrityViolationException extends RuntimeException {
    /**
     * 使用指定原因创建异常。
     *
     * <p>消息通常会直接描述哪条图约束被违反，供上层转换为冲突响应或审计日志。
     */
    public DagIntegrityViolationException(String message) {
        super(message);
    }
}
