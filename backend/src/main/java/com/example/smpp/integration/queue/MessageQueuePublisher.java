package com.example.smpp.integration.queue;

import com.example.smpp.config.QueueProperties;
import com.example.smpp.observability.SmppTracePropagation;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Publishes message ids to the processing queue (integration layer only).
 */
@Component
@ConditionalOnProperty(prefix = "smpp.queue", name = "enabled", havingValue = "true")
public class MessageQueuePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final QueueProperties queueProperties;

    public MessageQueuePublisher(RabbitTemplate rabbitTemplate, QueueProperties queueProperties) {
        this.rabbitTemplate = rabbitTemplate;
        this.queueProperties = queueProperties;
    }

    /**
     * @throws AmqpException when the broker cannot accept the message
     */
    public void publish(QueuePayload payload) {
        rabbitTemplate.convertAndSend(
                queueProperties.getExchange(),
                queueProperties.getRoutingKey(),
                payload,
                msg -> {
                    if (payload.getTraceContext() != null && !payload.getTraceContext().isBlank()) {
                        msg.getMessageProperties()
                                .setHeader(SmppTracePropagation.TRACEPARENT_HEADER, payload.getTraceContext());
                    }
                    return msg;
                });
    }
}
