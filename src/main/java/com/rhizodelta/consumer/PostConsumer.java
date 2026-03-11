package com.rhizodelta.consumer;

import com.rabbitmq.client.Channel;
import com.rhizodelta.config.RabbitMqConfig;
import com.rhizodelta.domain.post.PostEventMessage;
import com.rhizodelta.service.PostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Component
public class PostConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger(PostConsumer.class);
    private static final int MAX_FAILURE_ATTEMPTS = 3;
    private static final String RETRY_COUNT_HEADER = "x-retry-count";

    private final PostService postService;
    private final RabbitTemplate rabbitTemplate;

    public PostConsumer(PostService postService, RabbitTemplate rabbitTemplate) {
        this.postService = postService;
        this.rabbitTemplate = rabbitTemplate;
    }

    @RabbitListener(queues = RabbitMqConfig.POSTS_QUEUE)
    public void handleMessage(PostEventMessage message, Message amqpMessage, Channel channel) {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        try {
            processMessage(message);
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            handleFailure(message, amqpMessage, channel, deliveryTag, exception);
        }
    }

    private void processMessage(PostEventMessage message) {
        PostService.CreateHumanPostCommand command = new PostService.CreateHumanPostCommand(
                message.requestId(),
                message.authorId(),
                message.content(),
                message.targetNodeId()
        );
        postService.createHumanPost(command);
    }

    private void handleFailure(
            PostEventMessage message,
            Message amqpMessage,
            Channel channel,
            long deliveryTag,
            Exception exception
    ) {
        int retryCount = resolveRetryCount(amqpMessage.getMessageProperties().getHeaders());
        int nextRetryCount = retryCount + 1;
        try {
            if (nextRetryCount >= MAX_FAILURE_ATTEMPTS) {
                sendToDlq(message, nextRetryCount, exception);
            } else {
                republishForRetry(message, nextRetryCount, exception);
            }
            channel.basicAck(deliveryTag, false);
        } catch (AmqpException amqpException) {
            LOGGER.error("Failed to republish post message eventId={}", message.eventId(), amqpException);
            nackRequeue(channel, deliveryTag);
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to acknowledge post message", ioException);
        }
    }

    private void sendToDlq(PostEventMessage message, int retryCount, Exception exception) {
        LOGGER.error("Post message failed after {} attempts; routing to DLQ. eventId={}",
                retryCount,
                message.eventId(),
                exception
        );
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.POSTS_DLQ_EXCHANGE,
                RabbitMqConfig.POSTS_DLQ_ROUTING_KEY,
                message,
                outbound -> withRetryHeader(outbound, retryCount)
        );
    }

    private void republishForRetry(PostEventMessage message, int nextRetryCount, Exception exception) {
        LOGGER.warn("Post message failed; retrying attempt {} eventId={}",
                nextRetryCount,
                message.eventId(),
                exception
        );
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.POSTS_EXCHANGE,
                RabbitMqConfig.POSTS_ROUTING_KEY,
                message,
                outbound -> withRetryHeader(outbound, nextRetryCount)
        );
    }

    private Message withRetryHeader(Message outbound, int retryCount) {
        return MessageBuilder.fromMessage(outbound)
                .setHeader(RETRY_COUNT_HEADER, retryCount)
                .build();
    }

    private int resolveRetryCount(Map<String, Object> headers) {
        Object value = headers.get(RETRY_COUNT_HEADER);
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Long) {
            return ((Long) value).intValue();
        }
        if (value instanceof String) {
            return parseRetryCount((String) value);
        }
        if (value != null) {
            LOGGER.warn("Unexpected retry header type: {}", value.getClass().getName());
        }
        return 0;
    }

    private int parseRetryCount(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            LOGGER.warn("Invalid retry header value: {}", value);
            return 0;
        }
    }

    private void nackRequeue(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to requeue post message", ioException);
        }
    }
}
