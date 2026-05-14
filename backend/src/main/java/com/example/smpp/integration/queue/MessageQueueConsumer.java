package com.example.smpp.integration.queue;

import com.example.smpp.application.service.MessageProcessingService;
import com.example.smpp.application.service.QueueProcessOutcome;
import com.example.smpp.observability.SmppTracePropagation;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "smpp.queue", name = "enabled", havingValue = "true")
public class MessageQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(MessageQueueConsumer.class);

    private final MessageProcessingService processingService;

    public MessageQueueConsumer(MessageProcessingService processingService) {
        this.processingService = processingService;
    }

    @RabbitListener(queues = "${smpp.queue.queue}", ackMode = "MANUAL")
    public void onMessage(
            @Payload QueuePayload payload,
            Message amqpMessage,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag)
            throws IOException {
        try {
            String traceparent = extractTraceparent(amqpMessage);
            QueueProcessOutcome outcome = processingService.process(payload, traceparent);
            switch (outcome) {
                case REQUEUE ->
                    channel.basicNack(deliveryTag, false, true);
                case DEFERRED_RETRY ->
                    channel.basicNack(deliveryTag, false, false);
                case COMPLETE ->
                    channel.basicAck(deliveryTag, false);
            }
        } catch (Exception ex) {
            log.error(
                    "async worker failed messageId={}",
                    payload != null ? payload.getMessageId() : "?",
                    ex);
            // Broker-delayed retry without immediate CPU spin; bounded by DB attempt_count.
            channel.basicNack(deliveryTag, false, false);
        }
    }

    private static String extractTraceparent(Message amqpMessage) {
        if (amqpMessage == null) {
            return null;
        }
        Map<String, Object> headers = amqpMessage.getMessageProperties().getHeaders();
        if (headers == null) {
            return null;
        }
        Object raw = headers.get(SmppTracePropagation.TRACEPARENT_HEADER);
        return raw == null ? null : raw.toString();
    }
}
