package com.example.smpp.application.service;

import com.example.smpp.domain.model.MessageStatus;
import com.example.smpp.repository.MessageRepository;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application-layer transition RECEIVED -> QUEUED after successful broker
 * publish.
 */
@Service
@ConditionalOnProperty(prefix = "smpp.queue", name = "enabled", havingValue = "true")
public class MessageQueuePromotionService {

    private static final Logger log = LoggerFactory.getLogger(MessageQueuePromotionService.class);

    private final MessageRepository messageRepository;
    private final Clock clock;

    public MessageQueuePromotionService(MessageRepository messageRepository, Clock clock) {
        this.messageRepository = Objects.requireNonNull(messageRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * @return rows updated (0 if row was not RECEIVED)
     */
    @Transactional
    public int markReceivedAsQueued(String messageId) {
        int updated
                = messageRepository.updateReceivedToQueued(
                        messageId,
                        MessageStatus.RECEIVED,
                        MessageStatus.QUEUED,
                        clock.instant());
        if (updated == 0) {
            log.debug("markQueued skipped messageId={} (not in RECEIVED)", messageId);
        }
        return updated;
    }
}
