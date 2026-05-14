package com.example.smpp.integration.queue;

import com.example.smpp.config.QueueProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Publishes a copy of the payload to the ops DLQ when a message becomes
 * terminal {@code FAILED}, so RabbitMQ shows poison/expired traffic alongside
 * the DB row.
 */
@Component
@ConditionalOnProperty(prefix = "smpp.queue", name = "enabled", havingValue = "true")
public class QueueDlqPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final QueueProperties queueProperties;

    public QueueDlqPublisher(RabbitTemplate rabbitTemplate, QueueProperties queueProperties) {
        this.rabbitTemplate = rabbitTemplate;
        this.queueProperties = queueProperties;
    }

    public void publishTerminalFailure(QueuePayload payload) {
        rabbitTemplate.convertAndSend(
                queueProperties.getDlxExchange(), queueProperties.getDlqRoutingKey(), payload);
    }
}
