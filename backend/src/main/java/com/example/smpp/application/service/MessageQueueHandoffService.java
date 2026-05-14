package com.example.smpp.application.service;

import com.example.smpp.config.QueueProperties;
import com.example.smpp.domain.model.MessageStatus;
import com.example.smpp.integration.queue.MessageQueuePublisher;
import com.example.smpp.integration.queue.QueuePayload;
import com.example.smpp.observability.SmppTracePropagation;
import com.example.smpp.repository.MessageEntity;
import com.example.smpp.repository.MessageRepository;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Bounded recovery/handler loop: publishes message ids for {@code RECEIVED}
 * rows and only then promotes to {@code QUEUED}. Independent of SMPP protocol
 * threads.
 */
@Service
@ConditionalOnProperty(prefix = "smpp.queue", name = "enabled", havingValue = "true")
public class MessageQueueHandoffService {

    private static final Logger log = LoggerFactory.getLogger(MessageQueueHandoffService.class);

    private final MessageRepository messageRepository;
    private final MessageQueuePublisher publisher;
    private final MessageQueuePromotionService promotionService;
    private final QueueProperties queueProperties;
    private final SmppTracePropagation tracePropagation;

    public MessageQueueHandoffService(
            MessageRepository messageRepository,
            MessageQueuePublisher publisher,
            MessageQueuePromotionService promotionService,
            QueueProperties queueProperties,
            SmppTracePropagation tracePropagation) {
        this.messageRepository = Objects.requireNonNull(messageRepository);
        this.publisher = Objects.requireNonNull(publisher);
        this.promotionService = Objects.requireNonNull(promotionService);
        this.queueProperties = Objects.requireNonNull(queueProperties);
        this.tracePropagation = Objects.requireNonNull(tracePropagation);
    }

    @Scheduled(fixedDelayString = "${smpp.queue.handoff-interval-ms}")
    public void runHandoffBatch() {
        Pageable page = PageRequest.of(0, queueProperties.getBatchSize());
        List<MessageEntity> batch
                = messageRepository.findAllByStatusOrderByReceivedAtAsc(MessageStatus.RECEIVED, page);

        for (MessageEntity row : batch) {
            try {
                tracePropagation.withRemoteParent(
                        row.getTraceContext(),
                        "queue.publish",
                        queueProperties.getQueue(),
                        ()
                        -> publisher.publish(
                                new QueuePayload(
                                        row.getMessageId(),
                                        row.getSystemId(),
                                        row.getTraceContext())));
                promotionService.markReceivedAsQueued(row.getMessageId());
            } catch (AmqpException ex) {
                log.warn(
                        "queue publish failed messageId={} boundSystemId={}: {}",
                        row.getMessageId(),
                        row.getSystemId(),
                        ex.toString());
            }
        }
    }
}
