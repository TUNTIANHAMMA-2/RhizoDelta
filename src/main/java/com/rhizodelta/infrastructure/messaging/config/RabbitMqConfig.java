package com.rhizodelta.infrastructure.messaging.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.aopalliance.intercept.MethodInterceptor;

/**
 * 配置 RabbitMQ 的交换机、队列、重试和消息转换器。
 *
 * <p>该配置类定义了帖子异步处理链路和 SSE 广播链路所需的核心消息基础设施。
 */
@Configuration
public class RabbitMqConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitMqConfig.class);
    private static final int RETRY_MAX_ATTEMPTS = 4;
    private static final long RETRY_INITIAL_INTERVAL_MS = 1000L;
    private static final double RETRY_MULTIPLIER = 2.0;
    private static final long RETRY_MAX_INTERVAL_MS = 4000L;
    public static final String POSTS_EXCHANGE = "rhizodelta.posts";
    public static final String POSTS_QUEUE = "rhizodelta.posts.queue";
    public static final String POSTS_DLQ = "rhizodelta.posts.dlq";
    public static final String POSTS_ROUTING_KEY = "posts.created";
    public static final String POSTS_DLQ_EXCHANGE = "rhizodelta.posts.dlx";
    public static final String POSTS_DLQ_ROUTING_KEY = "posts.dlq";
    public static final String SSE_EVENTS_EXCHANGE = "rhizodelta.sse.events";
    public static final String SSE_EVENTS_ROUTING_KEY = "";

    /**
     * 定义帖子主题交换机。
     */
    @Bean
    public TopicExchange postsExchange() {
        return new TopicExchange(POSTS_EXCHANGE);
    }

    /**
     * 定义帖子处理主队列。
     *
     * <p>该队列绑定死信交换机，用于承接无法成功消费的消息。
     */
    @Bean
    public Queue postsQueue() {
        return QueueBuilder.durable(POSTS_QUEUE)
                .withArgument("x-dead-letter-exchange", POSTS_DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", POSTS_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue postsDeadLetterQueue() {
        return QueueBuilder.durable(POSTS_DLQ).build();
    }

    @Bean
    public Binding postsBinding(Queue postsQueue, TopicExchange postsExchange) {
        return BindingBuilder.bind(postsQueue).to(postsExchange).with(POSTS_ROUTING_KEY);
    }

    @Bean
    public TopicExchange postsDeadLetterExchange() {
        return new TopicExchange(POSTS_DLQ_EXCHANGE);
    }

    @Bean
    public Binding postsDeadLetterBinding(Queue postsDeadLetterQueue, TopicExchange postsDeadLetterExchange) {
        return BindingBuilder.bind(postsDeadLetterQueue).to(postsDeadLetterExchange).with(POSTS_DLQ_ROUTING_KEY);
    }

    @Bean
    public FanoutExchange sseEventsExchange() {
        return new FanoutExchange(SSE_EVENTS_EXCHANGE);
    }

    @Bean
    public AnonymousQueue sseEventsQueue() {
        return new AnonymousQueue();
    }

    @Bean
    public Binding sseEventsBinding(AnonymousQueue sseEventsQueue, FanoutExchange sseEventsExchange) {
        return BindingBuilder.bind(sseEventsQueue).to(sseEventsExchange);
    }

    @Bean
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 创建启用 publisher confirm 和 return 的连接工厂。
     */
    @Bean
    public CachingConnectionFactory rabbitConnectionFactory(RabbitProperties rabbitProperties) {
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory();
        connectionFactory.setHost(rabbitProperties.getHost());
        connectionFactory.setPort(rabbitProperties.getPort());
        connectionFactory.setUsername(rabbitProperties.getUsername());
        connectionFactory.setPassword(rabbitProperties.getPassword());
        connectionFactory.setVirtualHost(rabbitProperties.getVirtualHost());
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        return connectionFactory;
    }

    /**
     * 创建 RabbitTemplate 并启用返回与确认日志。
     *
     * <p>发布失败不会在这里自动重试，而是通过 confirm/return 机制把失败暴露给上层。
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        template.setMandatory(true);
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                String correlationId = correlationData == null ? "unknown" : correlationData.getId();
                LOGGER.error("RabbitMQ publish not acknowledged. correlationId={}, cause={}", correlationId, cause);
            }
        });
        template.setReturnsCallback(returned -> LOGGER.error(
                "RabbitMQ message returned. replyCode={}, replyText={}, exchange={}, routingKey={}",
                returned.getReplyCode(),
                returned.getReplyText(),
                returned.getExchange(),
                returned.getRoutingKey()
        ));
        return template;
    }

    /**
     * 创建监听器重试拦截器。
     *
     * <p>超过最大次数后消息会被拒绝并进入死信链路。
     */
    @Bean
    public MethodInterceptor rabbitRetryInterceptor() {
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(RETRY_MAX_ATTEMPTS)
                .backOffOptions(RETRY_INITIAL_INTERVAL_MS, RETRY_MULTIPLIER, RETRY_MAX_INTERVAL_MS)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }

    /**
     * 创建 Rabbit 监听容器工厂。
     *
     * <p>该工厂统一设置消息转换器、重试链与自动确认模式。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter,
            MethodInterceptor rabbitRetryInterceptor
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAdviceChain(rabbitRetryInterceptor);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
