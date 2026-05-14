package com.example.smpp.integration.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.smpp.config.QueueProperties;
import com.example.smpp.observability.SmppTracePropagation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@ExtendWith(MockitoExtension.class)
class MessageQueuePublisherTest {

    @Mock
    RabbitTemplate rabbitTemplate;
    @Mock
    QueueProperties queueProperties;

    MessageQueuePublisher publisher;

    @BeforeEach
    void setUp() {
        when(queueProperties.getExchange()).thenReturn("smpp.direct");
        when(queueProperties.getRoutingKey()).thenReturn("messages.process");
        publisher = new MessageQueuePublisher(rabbitTemplate, queueProperties);
    }

    @Test
    void publishInjectsTraceparentHeaderWhenPayloadCarriesTraceContext() throws Exception {
        String tp = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        QueuePayload payload = new QueuePayload("mid-1", "esme", tp);

        publisher.publish(payload);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<MessagePostProcessor> captor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate)
                .convertAndSend(eq("smpp.direct"), eq("messages.process"), eq(payload), captor.capture());

        Message raw = new Message("{}".getBytes(), new MessageProperties());
        Message processed = captor.getValue().postProcessMessage(raw);
        assertThat(processed.getMessageProperties().getHeaders().get(SmppTracePropagation.TRACEPARENT_HEADER))
                .isEqualTo(tp);
    }
}
