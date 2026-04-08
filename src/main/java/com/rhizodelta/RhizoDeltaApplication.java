package com.rhizodelta;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 启动 RhizoDelta 应用并装配顶层 Spring 上下文。
 *
 * <p>该类是整个系统的进程入口，负责触发自动配置、组件扫描以及配置属性绑定。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RhizoDeltaApplication {
    /**
     * 启动应用主进程。
     *
     * <p>该方法本身不承载业务逻辑，但它决定了所有基础设施配置、事件监听器和 HTTP/MQ 入口的初始化时机。
     *
     * <p>
     *
     * @param args 启动参数。
     */
    public static void main(String[] args) {
        SpringApplication.run(RhizoDeltaApplication.class, args);
    }
}
