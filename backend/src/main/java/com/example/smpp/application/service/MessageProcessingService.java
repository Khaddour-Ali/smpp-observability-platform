package com.example.smpp.application.service;

import com.example.smpp.config.MockSenderProperties;
import com.example.smpp.domain.model.MessageStatus;
import com.example.smpp.integration.queue.QueueDlqPublisher;
import com.example.smpp.integration.queue.QueuePayload;
import com.example.smpp.integration.sender.MockMessageSender;
import com.example.smpp.integration.sender.MockSendResult;
import com.example.smpp.observability.SmppMetrics;
import com.example.smpp.observability.SmppTracePropagation;
import com.example.smpp.repository.MessageEntity;
import com.example.smpp.repository.MessageRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Async worker: {@code QUEUED} -> mock HTTP -> {@code PROCESSED} or
 * {@code FAILED} with bounded broker retry.
 */
@Service
@ConditionalOnProperty(prefix = "smpp.queue", name = "enabled", havingValue = "true")
public class MessageProcessingService {

    private static final Logger log = LoggerFactory.getLogger(MessageProcessingService.class);

    /**
     * Wait for promotion RECEIVED -> QUEUED after publish (consumer can win the
     * race briefly).
     */
    private static final int POLL_MS = 25;

    private static final int MAX_POLL_ATTEMPTS = 30;

    private final MessageRepository messageRepository;
    private final MessageProcessingCompletionService completionService;
    private final MockMessageSender mockMessageSender;
    private final MockSenderProperties mockSenderProperties;
    private final QueueDlqPublisher queueDlqPublisher;
    private final SmppMetrics smppMetrics;
    private final SmppTracePropagation tracePropagation;
    private final ObjectProvider<Tracer> tracerProvider;

    public MessageProcessingService(
            MessageRepository messageRepository,
            MessageProcessingCompletionService completionService,
            MockMessageSender mockMessageSender,
            MockSenderProperties mockSenderProperties,
            QueueDlqPublisher queueDlqPublisher,
            SmppMetrics smppMetrics,
            SmppTracePropagation tracePropagation,
            ObjectProvider<Tracer> tracerProvider) {
        this.messageRepository = Objects.requireNonNull(messageRepository);
        this.completionService = Objects.requireNonNull(completionService);
        this.mockMessageSender = Objects.requireNonNull(mockMessageSender);
        this.mockSenderProperties = Objects.requireNonNull(mockSenderProperties);
        this.queueDlqPublisher = Objects.requireNonNull(queueDlqPublisher);
        this.smppMetrics = Objects.requireNonNull(smppMetrics);
        this.tracePropagation = Objects.requireNonNull(tracePropagation);
        this.tracerProvider = Objects.requireNonNull(tracerProvider);
    }

    public QueueProcessOutcome process(QueuePayload payload) {
        return process(payload, null);
    }

    public QueueProcessOutcome process(QueuePayload payload, @Nullable String traceparentHeader) {
        String tp
                = traceparentHeader != null && !traceparentHeader.isBlank()
                ? traceparentHeader
                : payload.getTraceContext();
        return tracePropagation.withRemoteParent(
                tp,
                "async.worker.processing",
                () -> {
                    long startNanos = System.nanoTime();
                    try {
                        QueueProcessOutcome o = processOnce(payload);
                        tagOutcome(o);
                        return o;
                    } finally {
                        smppMetrics.recordAsyncLatencyNanos(SmppMetrics.nanosSince(startNanos));
                    }
                });
    }

    private void tagOutcome(QueueProcessOutcome outcome) {
        Tracer tracer = tracerProvider.getIfAvailable();
        if (tracer == null) {
            return;
        }
        Span span = tracer.currentSpan();
        if (span != null) {
            span.tag("message.status", outcome.name());
        }
    }

    private QueueProcessOutcome processOnce(QueuePayload payload) {
        Optional<MessageEntity> row = waitForVisibleState(payload.getMessageId());
        if (row.isEmpty()) {
            log.warn("async process unknown messageId={}", payload.getMessageId());
            return QueueProcessOutcome.COMPLETE;
        }

        MessageEntity e = row.get();
        if (e.getStatus() == MessageStatus.PROCESSED) {
            return QueueProcessOutcome.COMPLETE;
        }

        if (e.getStatus() == MessageStatus.FAILED) {
            return QueueProcessOutcome.COMPLETE;
        }

        if (e.getStatus() == MessageStatus.RECEIVED) {
            return QueueProcessOutcome.REQUEUE;
        }

        if (e.getStatus() != MessageStatus.QUEUED) {
            log.warn(
                    "async process unexpected status messageId={} status={}",
                    payload.getMessageId(),
                    e.getStatus());
            return QueueProcessOutcome.COMPLETE;
        }

        int maxAttempts = mockSenderProperties.getMaxAttempts();
        MockSendResult send = mockMessageSender.send(e);

        if (send instanceof MockSendResult.Success) {
            return completeSuccess(payload.getMessageId());
        }

        if (send instanceof MockSendResult.Permanent p) {
            completionService.markQueuedAsFailed(payload.getMessageId(), p.detail());
            smppMetrics.recordProcessed(MessageStatus.FAILED.name());
            queueDlqPublisher.publishTerminalFailure(payload);
            return QueueProcessOutcome.COMPLETE;
        }

        if (send instanceof MockSendResult.Retryable r) {
            boolean exhausted
                    = completionService.incrementAttemptOrMarkFailedExhausted(
                            payload.getMessageId(), r.detail(), maxAttempts);
            if (exhausted) {
                smppMetrics.recordProcessed(MessageStatus.FAILED.name());
                queueDlqPublisher.publishTerminalFailure(payload);
                return QueueProcessOutcome.COMPLETE;
            }
            return QueueProcessOutcome.DEFERRED_RETRY;
        }

        log.error("unhandled MockSendResult for messageId={}", payload.getMessageId());
        return QueueProcessOutcome.COMPLETE;
    }

    private QueueProcessOutcome completeSuccess(String messageId) {
        boolean updated = completionService.markProcessedIfQueued(messageId);
        if (!updated) {
            Optional<MessageEntity> again = messageRepository.findByMessageId(messageId);
            if (again.isPresent() && again.get().getStatus() == MessageStatus.PROCESSED) {
                return QueueProcessOutcome.COMPLETE;
            }
            log.warn("async process no row updated messageId={}", messageId);
            return QueueProcessOutcome.COMPLETE;
        }

        smppMetrics.recordProcessed(MessageStatus.PROCESSED.name());
        return QueueProcessOutcome.COMPLETE;
    }

    /**
     * After publish, promotion to QUEUED may commit slightly after the broker
     * delivers the message. Poll while status is {@code RECEIVED}; if still
     * {@code RECEIVED} after attempts, caller requeues.
     */
    private Optional<MessageEntity> waitForVisibleState(String messageId) {
        Optional<MessageEntity> latest = Optional.empty();
        for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
            latest = messageRepository.findByMessageId(messageId);
            if (latest.isEmpty()) {
                return latest;
            }
            MessageStatus s = latest.get().getStatus();
            if (s != MessageStatus.RECEIVED) {
                return latest;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return latest;
            }
        }
        return latest;
    }
}
