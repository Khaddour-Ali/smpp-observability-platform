package com.example.smpp.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.smpp.config.QueueProperties;
import com.example.smpp.integration.queue.MessageQueuePublisher;
import com.example.smpp.integration.queue.QueuePayload;
import com.example.smpp.observability.SmppTracePropagation;
import com.example.smpp.repository.MessageEntity;
import com.example.smpp.repository.MessageRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.Mockito;
import org.springframework.amqp.AmqpException;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageQueueHandoffServiceTest {

    @Mock MessageRepository messageRepository;
    @Mock MessageQueuePublisher publisher;
    @Mock MessageQueuePromotionService promotionService;
    @Mock QueueProperties queueProperties;
    @Mock SmppTracePropagation tracePropagation;

    MessageQueueHandoffService service;

    @BeforeEach
    void setUp() {
        when(queueProperties.getBatchSize()).thenReturn(10);
        when(queueProperties.getQueue()).thenReturn("smpp.messages.process");
        Mockito.lenient()
                .doAnswer(
                        inv -> {
                            inv.getArgument(3, Runnable.class).run();
                            return null;
                        })
                .when(tracePropagation)
                .withRemoteParent(any(), any(), any(), any(Runnable.class));
        service =
                new MessageQueueHandoffService(
                        messageRepository, publisher, promotionService, queueProperties, tracePropagation);
    }

    @Test
    void marksQueuedOnlyAfterPublishSucceeds() {
        MessageEntity row = mockRow("msg-1", "esme");
        when(messageRepository.findAllByStatusOrderByReceivedAtAsc(any(), any(Pageable.class)))
                .thenReturn(List.of(row));

        service.runHandoffBatch();

        ArgumentCaptor<QueuePayload> captor = ArgumentCaptor.forClass(QueuePayload.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue().getMessageId()).isEqualTo("msg-1");
        assertThat(captor.getValue().getSystemId()).isEqualTo("esme");
        verify(promotionService, times(1)).markReceivedAsQueued("msg-1");
    }

    @Test
    void failedPublishLeavesPromotionNotCalled() {
        MessageEntity row = mockRow("msg-2", "esme");
        when(messageRepository.findAllByStatusOrderByReceivedAtAsc(any(), any(Pageable.class)))
                .thenReturn(List.of(row));
        org.mockito.Mockito.doThrow(new AmqpException("broken")).when(publisher).publish(any());

        service.runHandoffBatch();

        verify(promotionService, never()).markReceivedAsQueued(any());
    }

    private static MessageEntity mockRow(String messageId, String systemId) {
        MessageEntity row = org.mockito.Mockito.mock(MessageEntity.class);
        when(row.getMessageId()).thenReturn(messageId);
        when(row.getSystemId()).thenReturn(systemId);
        when(row.getTraceContext()).thenReturn("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        return row;
    }
}
