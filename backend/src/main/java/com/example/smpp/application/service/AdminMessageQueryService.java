package com.example.smpp.application.service;

import com.example.smpp.application.dto.FailedMessageResponse;
import com.example.smpp.application.dto.MessageStatsResponse;
import com.example.smpp.application.dto.ThroughputResponse;
import com.example.smpp.domain.model.MessageStatus;
import com.example.smpp.repository.MessageEntity;
import com.example.smpp.repository.MessageRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** Read-only admin queries over {@link MessageRepository}; no side effects. */
@Service
public class AdminMessageQueryService {

    public static final int FAILED_LIMIT_DEFAULT = 50;
    public static final int FAILED_LIMIT_MAX = 500;
    public static final int THROUGHPUT_WINDOW_SECONDS = 60;

    private final MessageRepository messageRepository;
    private final Clock clock;

    public AdminMessageQueryService(MessageRepository messageRepository, Clock clock) {
        this.messageRepository = Objects.requireNonNull(messageRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    public MessageStatsResponse getMessageStats() {
        Instant now = clock.instant();
        Map<MessageStatus, Long> byStatus = new EnumMap<>(MessageStatus.class);
        for (MessageStatus s : MessageStatus.values()) {
            byStatus.put(s, 0L);
        }
        for (MessageRepository.StatusCountRow row : messageRepository.countGroupedByStatus()) {
            byStatus.put(row.getStatus(), row.getCount());
        }
        long received = byStatus.getOrDefault(MessageStatus.RECEIVED, 0L);
        long queued = byStatus.getOrDefault(MessageStatus.QUEUED, 0L);
        long processed = byStatus.getOrDefault(MessageStatus.PROCESSED, 0L);
        long failed = byStatus.getOrDefault(MessageStatus.FAILED, 0L);
        long total = received + queued + processed + failed;
        return new MessageStatsResponse(received, queued, processed, failed, total, now);
    }

    public List<FailedMessageResponse> getFailedMessages(int limit) {
        int capped = normalizeFailedLimit(limit);
        return messageRepository
                .findAllByStatusOrderByUpdatedAtDesc(MessageStatus.FAILED, PageRequest.of(0, capped))
                .stream()
                .map(AdminMessageQueryService::toFailedResponse)
                .toList();
    }

    public ThroughputResponse getThroughputLastWindow() {
        Instant now = clock.instant();
        Instant since = now.minus(THROUGHPUT_WINDOW_SECONDS, ChronoUnit.SECONDS);
        long processedCount =
                messageRepository.countProcessedSince(MessageStatus.PROCESSED, since);
        double mps = (double) processedCount / (double) THROUGHPUT_WINDOW_SECONDS;
        return new ThroughputResponse(
                THROUGHPUT_WINDOW_SECONDS, since, now, processedCount, mps);
    }

    static int normalizeFailedLimit(int requested) {
        if (requested < 1) {
            return FAILED_LIMIT_DEFAULT;
        }
        return Math.min(requested, FAILED_LIMIT_MAX);
    }

    private static FailedMessageResponse toFailedResponse(MessageEntity e) {
        return new FailedMessageResponse(
                e.getMessageId(),
                e.getSystemId(),
                e.getSourceAddr(),
                e.getDestinationAddr(),
                e.getBody(),
                e.getReceivedAt(),
                e.getQueuedAt(),
                e.getProcessedAt(),
                e.getAttemptCount(),
                e.getLastError(),
                e.getUpdatedAt());
    }
}
