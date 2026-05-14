package com.example.smpp.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.smpp.domain.model.MessageStatus;
import com.example.smpp.repository.MessageEntity;
import com.example.smpp.repository.MessageRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MessageProcessingCompletionServiceTest {

    static final Instant NOW = Instant.parse("2025-06-01T12:00:00Z");

    @Mock
    MessageRepository messageRepository;

    MessageProcessingCompletionService service;

    @BeforeEach
    void setUp() {
        service = new MessageProcessingCompletionService(messageRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void exhaustedRetriesMarkFailedWithErrorDetail() {
        when(messageRepository.incrementAttemptCountForQueued(
                eq("mid"), eq(MessageStatus.QUEUED), eq(NOW)))
                .thenReturn(1);
        MessageEntity e = org.mockito.Mockito.mock(MessageEntity.class);
        when(e.getAttemptCount()).thenReturn(3);
        when(messageRepository.findByMessageId("mid")).thenReturn(Optional.of(e));

        boolean exhausted
                = service.incrementAttemptOrMarkFailedExhausted("mid", "HTTP 503", 3);

        assertThat(exhausted).isTrue();
        ArgumentCaptor<String> err = ArgumentCaptor.forClass(String.class);
        verify(messageRepository)
                .updateQueuedToFailed(
                        eq("mid"),
                        eq(MessageStatus.QUEUED),
                        eq(MessageStatus.FAILED),
                        err.capture(),
                        eq(NOW));
        assertThat(err.getValue()).contains("exhausted").contains("503");
    }

    @Test
    void notExhaustedLeavesQueued() {
        when(messageRepository.incrementAttemptCountForQueued(any(), any(), any())).thenReturn(1);
        MessageEntity e = org.mockito.Mockito.mock(MessageEntity.class);
        when(e.getAttemptCount()).thenReturn(1);
        when(messageRepository.findByMessageId("mid")).thenReturn(Optional.of(e));

        boolean exhausted = service.incrementAttemptOrMarkFailedExhausted("mid", "HTTP 500", 3);

        assertThat(exhausted).isFalse();
        verify(messageRepository, never())
                .updateQueuedToFailed(any(), any(), any(), any(), any());
    }

    @Test
    void markQueuedAsFailedStoresLastError() {
        service.markQueuedAsFailed("mid", "bad request");

        verify(messageRepository)
                .updateQueuedToFailed(
                        eq("mid"),
                        eq(MessageStatus.QUEUED),
                        eq(MessageStatus.FAILED),
                        eq("bad request"),
                        eq(NOW));
    }
}
