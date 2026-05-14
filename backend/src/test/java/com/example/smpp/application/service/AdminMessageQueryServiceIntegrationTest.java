package com.example.smpp.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.smpp.AbstractPostgresIntegrationTest;
import com.example.smpp.domain.model.Message;
import com.example.smpp.domain.model.MessageStatus;
import com.example.smpp.repository.MessageEntityMapper;
import com.example.smpp.repository.MessageRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Shares the static Postgres container; {@code @Transactional} rolls back so other ITs stay clean. */
@Transactional
class AdminMessageQueryServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired AdminMessageQueryService adminMessageQueryService;
    @Autowired MessageRepository messageRepository;

    @Test
    void stats_onEmptyDatabase_returnsZeros() {
        var stats = adminMessageQueryService.getMessageStats();

        assertThat(stats.received()).isZero();
        assertThat(stats.queued()).isZero();
        assertThat(stats.processed()).isZero();
        assertThat(stats.failed()).isZero();
        assertThat(stats.total()).isZero();
        assertThat(stats.generatedAt()).isNotNull();
    }

    @Test
    void throughput_countsOnlyProcessedInRollingWindow() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        var inWindow1 = MessageEntityMapper.toEntity(Message.received(newId(), "s", "1", "2", "a", now.minusSeconds(120)));
        inWindow1.setStatus(MessageStatus.PROCESSED);
        inWindow1.setProcessedAt(now.minusSeconds(30));
        inWindow1.setUpdatedAt(now.minusSeconds(30));
        messageRepository.save(inWindow1);

        var inWindow2 = MessageEntityMapper.toEntity(Message.received(newId(), "s", "1", "2", "b", now.minusSeconds(120)));
        inWindow2.setStatus(MessageStatus.PROCESSED);
        inWindow2.setProcessedAt(now.minusSeconds(5));
        inWindow2.setUpdatedAt(now.minusSeconds(5));
        messageRepository.save(inWindow2);

        var outWindow = MessageEntityMapper.toEntity(Message.received(newId(), "s", "1", "2", "c", now.minusSeconds(120)));
        outWindow.setStatus(MessageStatus.PROCESSED);
        outWindow.setProcessedAt(now.minusSeconds(120));
        outWindow.setUpdatedAt(now.minusSeconds(120));
        messageRepository.save(outWindow);

        var failed = MessageEntityMapper.toEntity(Message.received(newId(), "s", "1", "2", "d", now.minusSeconds(120)));
        failed.setStatus(MessageStatus.FAILED);
        failed.setProcessedAt(null);
        failed.setUpdatedAt(now.minusSeconds(10));
        messageRepository.save(failed);

        var t = adminMessageQueryService.getThroughputLastWindow();

        assertThat(t.processedCount()).isEqualTo(2L);
        assertThat(t.windowSeconds()).isEqualTo(60);
        assertThat(t.messagesPerSecond()).isEqualTo(2.0 / 60.0);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
