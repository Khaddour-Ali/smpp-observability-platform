package com.example.smpp.application.service;

import com.example.smpp.application.dto.SubmitMessageCommand;
import com.example.smpp.application.dto.SubmitMessageResult;
import com.example.smpp.domain.model.Message;
import com.example.smpp.domain.policy.MessageValidator;
import com.example.smpp.domain.policy.SubmitValidationViolation;
import com.example.smpp.domain.policy.ThrottlePolicy;
import com.example.smpp.observability.SmppMetrics;
import com.example.smpp.observability.SmppTracePropagation;
import com.example.smpp.repository.MessageEntityMapper;
import com.example.smpp.repository.MessageRepository;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Synchronous submit acceptance: validate, throttle, persist {@code RECEIVED},
 * then report metrics. RabbitMQ handoff runs on a scheduled worker
 * ({@code RECEIVED} -> publish -> {@code QUEUED}), not on this thread.
 */
@Service
public class MessageIngressService {

    private static final Logger log = LoggerFactory.getLogger(MessageIngressService.class);

    private final MessageValidator validator;
    private final ThrottlePolicy throttlePolicy;
    private final MessageRepository messageRepository;
    private final SmppMetrics smppMetrics;
    private final Clock clock;
    private final ObservationRegistry observationRegistry;
    private final SmppTracePropagation tracePropagation;

    public MessageIngressService(
            MessageValidator validator,
            ThrottlePolicy throttlePolicy,
            MessageRepository messageRepository,
            SmppMetrics smppMetrics,
            Clock clock,
            ObservationRegistry observationRegistry,
            SmppTracePropagation tracePropagation) {
        this.validator = Objects.requireNonNull(validator);
        this.throttlePolicy = Objects.requireNonNull(throttlePolicy);
        this.messageRepository = Objects.requireNonNull(messageRepository);
        this.smppMetrics = Objects.requireNonNull(smppMetrics);
        this.clock = Objects.requireNonNull(clock);
        this.observationRegistry = Objects.requireNonNull(observationRegistry);
        this.tracePropagation = Objects.requireNonNull(tracePropagation);
    }

    /**
     * Full synchronous handling for one {@link SubmitMessageCommand} (same
     * thread as caller).
     */
    @Transactional
    public SubmitMessageResult submit(SubmitMessageCommand command) {
        long start = System.nanoTime();
        try {
            Optional<SubmitValidationViolation> violation
                    = childObservation("input.validation")
                            .lowCardinalityKeyValue(
                                    "smpp.system_id",
                                    SmppMetrics.sanitizeSystemId(command.getBoundSystemId()))
                            .observe(() -> validator.validate(command));
            if (violation.isPresent()) {
                return new SubmitMessageResult.ValidationRejected(violation.get());
            }
            if (!throttlePolicy.tryAcquire(command.getBoundSystemId())) {
                smppMetrics.recordThrottled(command.getBoundSystemId());
                return SubmitMessageResult.Throttled.INSTANCE;
            }

            String messageId = UUID.randomUUID().toString();
            Instant now = clock.instant();

            try {
                childObservation("db.persist")
                        .lowCardinalityKeyValue("message.status", "RECEIVED")
                        .observe(
                                () -> {
                                    String traceParent = tracePropagation.currentTraceParentHeader();
                                    Message pending
                                    = Message.received(
                                            messageId,
                                            command.getBoundSystemId(),
                                            command.getSourceAddress(),
                                            command.getDestinationAddress(),
                                            command.getBody(),
                                            now,
                                            traceParent);
                                    messageRepository.save(MessageEntityMapper.toEntity(pending));
                                });
            } catch (DataAccessException ex) {
                log.error(
                        "persist submit_sm failed generatedMessageId={} boundSystemId={}",
                        messageId,
                        SmppMetrics.sanitizeSystemId(command.getBoundSystemId()),
                        ex);
                return SubmitMessageResult.StorageFailed.INSTANCE;
            }

            smppMetrics.recordReceived(command.getBoundSystemId());
            return new SubmitMessageResult.Accepted(messageId);
        } finally {
            smppMetrics.recordSyncLatencyNanos(SmppMetrics.nanosSince(start));
        }
    }

    private Observation childObservation(String name) {
        Observation obs = Observation.createNotStarted(name, observationRegistry);
        Observation current = observationRegistry.getCurrentObservation();
        return current != null ? obs.parentObservation(current) : obs;
    }
}
