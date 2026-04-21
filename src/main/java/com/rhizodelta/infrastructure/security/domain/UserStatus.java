package com.rhizodelta.infrastructure.security.domain;

/**
 * 用户账户生命周期状态。
 *
 * <p>该枚举是 {@code UserAccount.status} 的 <b>唯一来源</b>。任何写入
 * {@code UserAccount.status} 的代码都必须从这里取值，避免 Cypher 中
 * 出现散落的字符串字面量（例如 {@code 'ACTIVE'}）。
 *
 * <p>详见 OpenSpec change {@code user-identity-hardening} 的 D4 决策
 * 以及 {@code user-identity-schema} spec 的 "UserAccount carries
 * lifecycle status" 要求。
 */
public enum UserStatus {
    /** 账号处于正常使用中。新注册默认写入该值。 */
    ACTIVE,

    /** 账号被暂时封禁；暂未有写入点，预留给未来 suspend 流程。 */
    SUSPENDED,

    /** 账号被软删除；暂未有写入点，预留给未来 soft-delete 流程。 */
    DELETED
}
