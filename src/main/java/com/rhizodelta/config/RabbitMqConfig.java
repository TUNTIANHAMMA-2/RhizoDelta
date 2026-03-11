package com.rhizodelta.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
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

    @Bean
    public TopicExchange postsExchange() {
        return new TopicExchange(POSTS_EXCHANGE);
    }

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
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

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

    @Bean
    public MethodInterceptor rabbitRetryInterceptor() {
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(RETRY_MAX_ATTEMPTS)
                .backOffOptions(RETRY_INITIAL_INTERVAL_MS, RETRY_MULTIPLIER, RETRY_MAX_INTERVAL_MS)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }

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
        return factory;
    }
}
