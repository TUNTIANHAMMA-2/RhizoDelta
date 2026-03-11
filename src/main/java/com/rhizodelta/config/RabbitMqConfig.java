package com.rhizodelta.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {
    public static final String POSTS_EXCHANGE = "rhizodelta.posts";
    public static final String POSTS_QUEUE = "rhizodelta.posts.queue";
    public static final String POSTS_DLQ = "rhizodelta.posts.dlq";
    public static final String POSTS_ROUTING_KEY = "posts.created";

    @Bean
    public TopicExchange postsExchange() {
        return new TopicExchange(POSTS_EXCHANGE);
    }

    @Bean
    public Queue postsQueue() {
        return QueueBuilder.durable(POSTS_QUEUE).build();
    }

    @Bean
    public Queue postsDeadLetterQueue() {
        return QueueBuilder.durable(POSTS_DLQ).build();
    }

    @Bean
    public Binding postsBinding(Queue postsQueue, TopicExchange postsExchange) {
        return BindingBuilder.bind(postsQueue).to(postsExchange).with(POSTS_ROUTING_KEY);
    }
}
