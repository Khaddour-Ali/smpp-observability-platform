package com.example.smpp.application.service;

import com.example.smpp.domain.model.MessageStatus;
import com.example.smpp.repository.MessageEntity;
import com.example.smpp.repository.MessageRepository;
import java.time.Clock;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "smpp.queue", name = "enabled", havingValue = "true")
public class MessageProcessingCompletionService {

    private final MessageRepository messageRepository;
    private final Clock clock;

    public MessageProcessingCompletionService(MessageRepository messageRepository, Clock clock) {
        this.messageRepository = Objects.requireNonNull(messageRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * @return true if this call performed the transition to PROCESSED
     */
    @Transactional
    public boolean markProcessedIfQueued(String messageId) {
        return messageRepository.updateQueuedToProcessed(
                messageId,
                MessageStatus.QUEUED,
                MessageStatus.PROCESSED,
                clock.instant())
                > 0;
    }

    @Transactional
    public void markQueuedAsFailed(String messageId, String lastError) {
        messageRepository.updateQueuedToFailed(
                messageId,
                MessageStatus.QUEUED,
                MessageStatus.FAILED,
                truncate(lastError),
                clock.instant());
    }

    /**
     * Records one failed delivery attempt for a {@code QUEUED} message. If
     * {@code attempt_count} reaches {@code maxAttempts}, transitions to
     * {@code FAILED}.
     *
     * @return true if exhausted and FAILED was written; false if more
     * broker-delayed retries allowed
     */
    @Transactional
    public boolean incrementAttemptOrMarkFailedExhausted(
            String messageId, String detailForLastFailure, int maxAttempts) {
        messageRepository.incrementAttemptCountForQueued(messageId, MessageStatus.QUEUED, clock.instant());
        MessageEntity e
                = messageRepository
                        .findByMessageId(messageId)
                        .orElseThrow(
                                ()
                                -> new IllegalStateException(
                                        "message missing after attempt increment: " + messageId));
        if (e.getAttemptCount() >= maxAttempts) {
            String err
                    = "exhausted retries (" + maxAttempts + ") last=" + truncate(detailForLastFailure);
            markQueuedAsFailed(messageId, err);
            return true;
        }
        return false;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        if (s.length() <= 2048) {
            return s;
        }
        return s.substring(0, 2048) + "...";
    }
}
