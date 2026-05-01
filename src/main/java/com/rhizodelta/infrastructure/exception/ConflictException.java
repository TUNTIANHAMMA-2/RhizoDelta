package com.rhizodelta.infrastructure.exception;

/**
 * 表示资源已存在或状态冲突，应映射为 HTTP 409。
 *
 * <p>用于关注/屏蔽等场景中重复创建相同关系的语义冲突。
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
