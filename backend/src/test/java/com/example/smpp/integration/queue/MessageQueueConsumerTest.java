package com.example.smpp.integration.queue;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.smpp.application.service.MessageProcessingService;
import com.example.smpp.application.service.QueueProcessOutcome;
import com.example.smpp.observability.SmppTracePropagation;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

@ExtendWith(MockitoExtension.class)
class MessageQueueConsumerTest {

    @Mock
    MessageProcessingService processingService;
    @Mock
    Channel channel;

    MessageQueueConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new MessageQueueConsumer(processingService);
    }

    @Test
    void forwardsTraceparentHeaderToProcessingService() throws Exception {
        QueuePayload payload = new QueuePayload("mid", "esme", null);
        MessageProperties props = new MessageProperties();
        props.setHeader(SmppTracePropagation.TRACEPARENT_HEADER, "00-abc-def-01");
        Message amqpMessage = new Message(new byte[0], props);
        when(processingService.process(eq(payload), eq("00-abc-def-01")))
                .thenReturn(QueueProcessOutcome.COMPLETE);

        consumer.onMessage(payload, amqpMessage, channel, 7L);

        verify(processingService).process(payload, "00-abc-def-01");
        verify(channel).basicAck(7L, false);
    }
}
