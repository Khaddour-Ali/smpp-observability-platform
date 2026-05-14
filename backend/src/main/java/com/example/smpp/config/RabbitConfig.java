package com.example.smpp.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Main processing queue -> DLX -> TTL retry queue -> main exchange (bounded
 * retries via DB {@code attempt_count}). Separate DLQ binding for explicit
 * publishes when a message is terminal {@code FAILED}.
 */
@Configuration
@EnableRabbit
@ConditionalOnProperty(prefix = "smpp.queue", name = "enabled", havingValue = "true")
public class RabbitConfig {

    @Bean
    DirectExchange smppExchange(QueueProperties properties) {
        return new DirectExchange(properties.getExchange(), true, false);
    }

    @Bean
    DirectExchange smppDlxExchange(QueueProperties properties) {
        return new DirectExchange(properties.getDlxExchange(), true, false);
    }

    @Bean
    Queue smppRetryQueue(QueueProperties properties) {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", properties.getExchange());
        args.put("x-dead-letter-routing-key", properties.getRoutingKey());
        int ttl = (int) Math.min(Math.max(properties.getMessageRetryTtlMs(), 1), Integer.MAX_VALUE);
        args.put("x-message-ttl", ttl);
        return QueueBuilder.durable(properties.getRetryQueue()).withArguments(args).build();
    }

    @Bean
    Queue smppDlqQueue(QueueProperties properties) {
        return QueueBuilder.durable(properties.getDlqQueue()).build();
    }

    @Bean
    Queue smppProcessingQueue(QueueProperties properties) {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", properties.getDlxExchange());
        args.put("x-dead-letter-routing-key", properties.getRetryRoutingKey());
        return QueueBuilder.durable(properties.getQueue()).withArguments(args).build();
    }

    @Bean
    Binding smppRetryBinding(
            @Qualifier("smppRetryQueue") Queue retryQueue,
            @Qualifier("smppDlxExchange") DirectExchange dlx,
            QueueProperties properties) {
        return BindingBuilder.bind(retryQueue).to(dlx).with(properties.getRetryRoutingKey());
    }

    @Bean
    Binding smppDlqBinding(
            @Qualifier("smppDlqQueue") Queue dlqQueue,
            @Qualifier("smppDlxExchange") DirectExchange dlx,
            QueueProperties properties) {
        return BindingBuilder.bind(dlqQueue).to(dlx).with(properties.getDlqRoutingKey());
    }

    @Bean
    Binding smppProcessingBinding(
            @Qualifier("smppProcessingQueue") Queue queue,
            @Qualifier("smppExchange") DirectExchange exchange,
            QueueProperties properties) {
        return BindingBuilder.bind(queue).to(exchange).with(properties.getRoutingKey());
    }

    @Bean
    MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
