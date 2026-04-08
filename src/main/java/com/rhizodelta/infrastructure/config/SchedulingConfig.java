package com.rhizodelta.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用应用内定时任务能力。
 *
 * <p>该配置类本身不声明具体任务，但它决定了诸如心跳发送等调度逻辑能否被 Spring 激活。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
