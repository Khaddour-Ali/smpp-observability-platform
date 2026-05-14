package com.example.smpp.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.smpp.application.dto.MessageStatsResponse;
import com.example.smpp.application.dto.ThroughputResponse;
import com.example.smpp.domain.model.MessageStatus;
import com.example.smpp.repository.MessageEntity;
import com.example.smpp.repository.MessageRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class AdminMessageQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-11T12:00:00Z");

    @Mock MessageRepository messageRepository;

    AdminMessageQueryService service;

    @BeforeEach
    void setUp() {
        service = new AdminMessageQueryService(messageRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void stats_includesZeroForMissingStatuses() {
        when(messageRepository.countGroupedByStatus())
                .thenReturn(
                        List.of(
                                row(MessageStatus.RECEIVED, 2L),
                                row(MessageStatus.PROCESSED, 1L)));

        MessageStatsResponse stats = service.getMessageStats();

        assertThat(stats.received()).isEqualTo(2L);
        assertThat(stats.queued()).isZero();
        assertThat(stats.processed()).isEqualTo(1L);
        assertThat(stats.failed()).isZero();
        assertThat(stats.total()).isEqualTo(3L);
        assertThat(stats.generatedAt()).isEqualTo(NOW);
    }

    @Test
    void throughput_usesSixtySecondWindowAndRate() {
        Instant since = NOW.minusSeconds(60);
        when(messageRepository.countProcessedSince(MessageStatus.PROCESSED, since))
                .thenReturn(30L);

        ThroughputResponse t = service.getThroughputLastWindow();

        assertThat(t.windowSeconds()).isEqualTo(60);
        assertThat(t.since()).isEqualTo(since);
        assertThat(t.generatedAt()).isEqualTo(NOW);
        assertThat(t.processedCount()).isEqualTo(30L);
        assertThat(t.messagesPerSecond()).isEqualTo(0.5);
    }

    @Test
    void normalizeFailedLimit_clampsToMaxAndFallsBackToDefault() {
        assertThat(AdminMessageQueryService.normalizeFailedLimit(0)).isEqualTo(50);
        assertThat(AdminMessageQueryService.normalizeFailedLimit(-1)).isEqualTo(50);
        assertThat(AdminMessageQueryService.normalizeFailedLimit(1)).isEqualTo(1);
        assertThat(AdminMessageQueryService.normalizeFailedLimit(500)).isEqualTo(500);
        assertThat(AdminMessageQueryService.normalizeFailedLimit(501)).isEqualTo(500);
    }

    @Test
    void getFailedMessages_passesCappedPageSize() {
        when(messageRepository.findAllByStatusOrderByUpdatedAtDesc(
                        eq(MessageStatus.FAILED), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of());

        service.getFailedMessages(900);

        org.mockito.ArgumentCaptor<Pageable> cap = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(messageRepository)
                .findAllByStatusOrderByUpdatedAtDesc(eq(MessageStatus.FAILED), cap.capture());
        assertThat(cap.getValue().getPageSize()).isEqualTo(500);
    }

    private static MessageRepository.StatusCountRow row(MessageStatus status, long count) {
        return new MessageRepository.StatusCountRow() {
            @Override
            public MessageStatus getStatus() {
                return status;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }
}
