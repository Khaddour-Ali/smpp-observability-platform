package com.example.smpp.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import io.micrometer.tracing.Tracer;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageProcessingServiceTest {

    @Mock MessageRepository messageRepository;
    @Mock MessageProcessingCompletionService completionService;
    @Mock MockMessageSender mockMessageSender;
    @Mock MockSenderProperties mockSenderProperties;
    @Mock QueueDlqPublisher queueDlqPublisher;
    @Mock SmppMetrics smppMetrics;
    @Mock SmppTracePropagation tracePropagation;
    @Mock ObjectProvider<Tracer> tracerProvider;

    MessageProcessingService service;

    @BeforeEach
    void setUp() {
        when(mockSenderProperties.getMaxAttempts()).thenReturn(3);
        when(tracerProvider.getIfAvailable()).thenReturn(null);
        Mockito.lenient()
                .doAnswer(
                        inv ->
                                ((java.util.function.Supplier<QueueProcessOutcome>) inv.getArgument(2))
                                        .get())
                .when(tracePropagation)
                .withRemoteParent(any(), any(), any(java.util.function.Supplier.class));
        service =
                new MessageProcessingService(
                        messageRepository,
                        completionService,
                        mockMessageSender,
                        mockSenderProperties,
                        queueDlqPublisher,
                        smppMetrics,
                        tracePropagation,
                        tracerProvider);
    }

    @Test
    void successMarksProcessedAndMetrics() {
        MessageEntity row = queuedEntity("id-1");
        when(messageRepository.findByMessageId("id-1")).thenReturn(Optional.of(row));
        when(mockMessageSender.send(any(MessageEntity.class))).thenReturn(new MockSendResult.Success());
        when(completionService.markProcessedIfQueued("id-1")).thenReturn(true);

        assertThat(service.process(new QueuePayload("id-1", "esme", null)))
                .isEqualTo(QueueProcessOutcome.COMPLETE);

        verify(smppMetrics).recordProcessed(MessageStatus.PROCESSED.name());
        verify(smppMetrics, atLeastOnce()).recordAsyncLatencyNanos(org.mockito.ArgumentMatchers.anyLong());
        verify(queueDlqPublisher, never()).publishTerminalFailure(any());
    }

    @Test
    void permanentFailureMarksFailedAndDlq() {
        MessageEntity row = queuedEntity("id-1");
        when(messageRepository.findByMessageId("id-1")).thenReturn(Optional.of(row));
        when(mockMessageSender.send(any(MessageEntity.class)))
                .thenReturn(new MockSendResult.Permanent("HTTP 400"));

        assertThat(service.process(new QueuePayload("id-1", "esme", null)))
                .isEqualTo(QueueProcessOutcome.COMPLETE);

        verify(completionService).markQueuedAsFailed(eq("id-1"), eq("HTTP 400"));
        verify(smppMetrics).recordProcessed(MessageStatus.FAILED.name());
        verify(smppMetrics, atLeastOnce()).recordAsyncLatencyNanos(org.mockito.ArgumentMatchers.anyLong());
        verify(queueDlqPublisher).publishTerminalFailure(any(QueuePayload.class));
        verify(completionService, never())
                .incrementAttemptOrMarkFailedExhausted(anyString(), anyString(), anyInt());
    }

    @Test
    void retryableDefersToBrokerWhenAttemptsRemain() {
        MessageEntity row = queuedEntity("id-1");
        when(messageRepository.findByMessageId("id-1")).thenReturn(Optional.of(row));
        when(mockMessageSender.send(any(MessageEntity.class)))
                .thenReturn(new MockSendResult.Retryable("HTTP 500"));
        when(completionService.incrementAttemptOrMarkFailedExhausted(eq("id-1"), eq("HTTP 500"), eq(3)))
                .thenReturn(false);

        assertThat(service.process(new QueuePayload("id-1", "esme", null)))
                .isEqualTo(QueueProcessOutcome.DEFERRED_RETRY);

        verify(smppMetrics, never()).recordProcessed(anyString());
        verify(smppMetrics, atLeastOnce()).recordAsyncLatencyNanos(org.mockito.ArgumentMatchers.anyLong());
        verify(queueDlqPublisher, never()).publishTerminalFailure(any(QueuePayload.class));
    }

    @Test
    void retryExhaustionMarksFailed() {
        MessageEntity row = queuedEntity("id-1");
        when(messageRepository.findByMessageId("id-1")).thenReturn(Optional.of(row));
        when(mockMessageSender.send(any(MessageEntity.class)))
                .thenReturn(new MockSendResult.Retryable("HTTP 500"));
        when(completionService.incrementAttemptOrMarkFailedExhausted(
                        eq("id-1"), eq("HTTP 500"), eq(3)))
                .thenReturn(true);

        assertThat(service.process(new QueuePayload("id-1", "esme", null)))
                .isEqualTo(QueueProcessOutcome.COMPLETE);

        verify(smppMetrics).recordProcessed(MessageStatus.FAILED.name());
        verify(smppMetrics, atLeastOnce()).recordAsyncLatencyNanos(org.mockito.ArgumentMatchers.anyLong());
        verify(queueDlqPublisher).publishTerminalFailure(any(QueuePayload.class));
        verify(completionService)
                .incrementAttemptOrMarkFailedExhausted(eq("id-1"), eq("HTTP 500"), eq(3));
    }

    @Test
    void alreadyProcessedCompletes() {
        MessageEntity row = entity(MessageStatus.PROCESSED);
        when(messageRepository.findByMessageId("id-1")).thenReturn(Optional.of(row));

        assertThat(service.process(new QueuePayload("id-1", "esme", null)))
                .isEqualTo(QueueProcessOutcome.COMPLETE);

        verify(mockMessageSender, never()).send(any());
        verify(smppMetrics, never()).recordProcessed(anyString());
        verify(smppMetrics, atLeastOnce()).recordAsyncLatencyNanos(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void duplicateAfterConcurrentProcessRecordsLatencyButSkipsProcessedMetric() {
        MessageEntity queued = queuedEntity("id-1");
        MessageEntity processed = entity(MessageStatus.PROCESSED);
        when(messageRepository.findByMessageId("id-1"))
                .thenReturn(Optional.of(queued), Optional.of(processed));
        when(mockMessageSender.send(any(MessageEntity.class))).thenReturn(new MockSendResult.Success());
        when(completionService.markProcessedIfQueued("id-1")).thenReturn(false);

        assertThat(service.process(new QueuePayload("id-1", "esme", null)))
                .isEqualTo(QueueProcessOutcome.COMPLETE);

        verify(smppMetrics, never()).recordProcessed(anyString());
        verify(smppMetrics, atLeastOnce()).recordAsyncLatencyNanos(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void stillReceivedAfterPollRequestsRequeue() {
        MessageEntity received = entity(MessageStatus.RECEIVED);
        when(messageRepository.findByMessageId("id-1")).thenReturn(Optional.of(received));

        assertThat(service.process(new QueuePayload("id-1", "esme", null)))
                .isEqualTo(QueueProcessOutcome.REQUEUE);

        verify(mockMessageSender, never()).send(any());
        verify(smppMetrics, atLeastOnce()).recordAsyncLatencyNanos(org.mockito.ArgumentMatchers.anyLong());
    }

    private static MessageEntity entity(MessageStatus status) {
        MessageEntity e = org.mockito.Mockito.mock(MessageEntity.class);
        when(e.getStatus()).thenReturn(status);
        return e;
    }

    private static MessageEntity queuedEntity(String id) {
        MessageEntity e = org.mockito.Mockito.mock(MessageEntity.class);
        when(e.getMessageId()).thenReturn(id);
        when(e.getStatus()).thenReturn(MessageStatus.QUEUED);
        when(e.getSystemId()).thenReturn("esme");
        when(e.getSourceAddr()).thenReturn("1");
        when(e.getDestinationAddr()).thenReturn("2");
        when(e.getBody()).thenReturn("b");
        when(e.getAttemptCount()).thenReturn(0);
        return e;
    }
}
