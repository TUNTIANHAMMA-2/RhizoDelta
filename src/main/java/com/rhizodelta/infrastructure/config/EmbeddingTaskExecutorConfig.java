package com.rhizodelta.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 配置 embedding 与 routing 异步任务线程池。
 *
 * <p>该配置类把两类 AI 异步工作拆分到不同线程池，
 * 避免向量生成和路由编排在高负载下互相抢占执行资源。
 */
@Configuration
public class EmbeddingTaskExecutorConfig {
    private static final String CORE_POOL_SIZE_PROPERTY = "rhizodelta.embedding.executor.corePoolSize";
    private static final String MAX_POOL_SIZE_PROPERTY = "rhizodelta.embedding.executor.maxPoolSize";
    private static final String QUEUE_CAPACITY_PROPERTY = "rhizodelta.embedding.executor.queueCapacity";
    private static final int DEFAULT_CORE_POOL_SIZE = 4;
    private static final int DEFAULT_MAX_POOL_SIZE = 8;
    private static final int DEFAULT_QUEUE_CAPACITY = 100;
    private static final String THREAD_NAME_PREFIX = "embedding-";

    private static final String ROUTING_CORE_POOL_SIZE_PROPERTY = "rhizodelta.routing.executor.corePoolSize";
    private static final String ROUTING_MAX_POOL_SIZE_PROPERTY = "rhizodelta.routing.executor.maxPoolSize";
    private static final String ROUTING_QUEUE_CAPACITY_PROPERTY = "rhizodelta.routing.executor.queueCapacity";
    private static final int ROUTING_DEFAULT_CORE_POOL_SIZE = 2;
    private static final int ROUTING_DEFAULT_MAX_POOL_SIZE = 4;
    private static final int ROUTING_DEFAULT_QUEUE_CAPACITY = 50;
    private static final String ROUTING_THREAD_NAME_PREFIX = "routing-";

    private static final String PREFERENCE_EVENT_CORE_POOL_SIZE_PROPERTY = "rhizodelta.preference-event.executor.corePoolSize";
    private static final String PREFERENCE_EVENT_MAX_POOL_SIZE_PROPERTY = "rhizodelta.preference-event.executor.maxPoolSize";
    private static final String PREFERENCE_EVENT_QUEUE_CAPACITY_PROPERTY = "rhizodelta.preference-event.executor.queueCapacity";
    private static final int PREFERENCE_EVENT_DEFAULT_CORE_POOL_SIZE = 2;
    private static final int PREFERENCE_EVENT_DEFAULT_MAX_POOL_SIZE = 4;
    private static final int PREFERENCE_EVENT_DEFAULT_QUEUE_CAPACITY = 200;
    private static final String PREFERENCE_EVENT_THREAD_NAME_PREFIX = "preference-event-";

    /**
     * 创建 embedding 任务线程池。
     *
     * <p>该线程池主要服务于 embedding 生成、摘要后向量更新等偏 IO/模型调用型任务。
     */
    @Bean(name = "embeddingTaskExecutor")
    public Executor embeddingTaskExecutor(Environment environment) {
        int corePoolSize = resolvePositiveInt(environment, CORE_POOL_SIZE_PROPERTY, DEFAULT_CORE_POOL_SIZE);
        int maxPoolSize = resolvePositiveInt(environment, MAX_POOL_SIZE_PROPERTY, DEFAULT_MAX_POOL_SIZE);
        int queueCapacity = resolvePositiveInt(environment, QUEUE_CAPACITY_PROPERTY, DEFAULT_QUEUE_CAPACITY);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(THREAD_NAME_PREFIX);
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.initialize();
        return executor;
    }

    /**
     * 创建 routing 任务线程池。
     *
     * <p>该线程池主要服务于 AI 路由编排与工作流执行，避免与 embedding 任务互相干扰。
     */
    @Bean(name = "routingTaskExecutor")
    public Executor routingTaskExecutor(Environment environment) {
        int corePoolSize = resolvePositiveInt(environment, ROUTING_CORE_POOL_SIZE_PROPERTY, ROUTING_DEFAULT_CORE_POOL_SIZE);
        int maxPoolSize = resolvePositiveInt(environment, ROUTING_MAX_POOL_SIZE_PROPERTY, ROUTING_DEFAULT_MAX_POOL_SIZE);
        int queueCapacity = resolvePositiveInt(environment, ROUTING_QUEUE_CAPACITY_PROPERTY, ROUTING_DEFAULT_QUEUE_CAPACITY);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(ROUTING_THREAD_NAME_PREFIX);
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.initialize();
        return executor;
    }

    /**
     * 创建 PreferenceEvent 任务线程池。
     *
     * <p>该线程池服务于隐式 VIEW 等读路径触发的偏好事件写入，让 {@code GET /api/nodes/{id}}
     * 等读接口在请求线程上不再等待 Neo4j 写事务返回。队列满时直接丢弃（{@code AbortPolicy} 由
     * 服务层捕获），保证偏好事件不阻塞主请求；显式 POST 创建事件的路径仍保持同步以便给用户
     * 即时反馈。
     */
    @Bean(name = "preferenceEventExecutor")
    public Executor preferenceEventExecutor(Environment environment) {
        int corePoolSize = resolvePositiveInt(environment, PREFERENCE_EVENT_CORE_POOL_SIZE_PROPERTY, PREFERENCE_EVENT_DEFAULT_CORE_POOL_SIZE);
        int maxPoolSize = resolvePositiveInt(environment, PREFERENCE_EVENT_MAX_POOL_SIZE_PROPERTY, PREFERENCE_EVENT_DEFAULT_MAX_POOL_SIZE);
        int queueCapacity = resolvePositiveInt(environment, PREFERENCE_EVENT_QUEUE_CAPACITY_PROPERTY, PREFERENCE_EVENT_DEFAULT_QUEUE_CAPACITY);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(PREFERENCE_EVENT_THREAD_NAME_PREFIX);
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.initialize();
        return executor;
    }

    /**
     * 从环境变量中读取正整数配置。
     *
     * <p>非法值会直接抛异常，避免系统带着错误线程池配置启动。
     */
    private static int resolvePositiveInt(Environment environment, String key, int defaultValue) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid integer for " + key + ": " + value, exception);
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException(key + " must be positive, got: " + parsed);
        }
        return parsed;
    }
}
