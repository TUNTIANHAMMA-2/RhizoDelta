package com.rhizodelta.ai.shared.domain;

/**
 * 定义模型在系统中的用途分类。
 *
 * <p>该枚举用于把不同 AI 能力与对应模型配置稳定绑定，
 * 避免调用方在代码中散落用途字符串常量。
 */
public enum ModelPurpose {
    ROUTING,
    SUMMARY,
    QUALITY,
    EMBEDDING
}
