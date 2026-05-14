package com.example.smpp.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.smpp.application.dto.SubmitMessageCommand;
import com.example.smpp.application.dto.SubmitMessageResult;
import com.example.smpp.config.SmppThrottleProperties;
import com.example.smpp.domain.policy.MessageValidator;
import com.example.smpp.domain.policy.ThrottlePolicy;
import com.example.smpp.observability.SmppMetrics;
import com.example.smpp.observability.SmppTracePropagation;
import com.example.smpp.protocol.smpp.SmppSessionRegistry;
import com.example.smpp.repository.MessageEntity;
import com.example.smpp.repository.MessageRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class MessageIngressServiceTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private SmppSessionRegistry sessionRegistry;

    @Mock
    private SmppTracePropagation tracePropagation;

    private final ObservationRegistry observationRegistry = ObservationRegistry.create();

    private MeterRegistry registry;
    private SmppMetrics smppMetrics;
    private MessageIngressService service;

    @BeforeEach
    void setup() {
        registry = new SimpleMeterRegistry();
        smppMetrics = new SmppMetrics(registry, sessionRegistry);
        smppMetrics.registerMeters();
        reset(messageRepository);
        lenient().when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(tracePropagation.currentTraceParentHeader()).thenReturn(null);
        rebuildService(disabledThrottle());
    }

    private static ThrottlePolicy disabledThrottle() {
        SmppThrottleProperties throttleProps = new SmppThrottleProperties();
        throttleProps.setEnabled(false);
        return new ThrottlePolicy(throttleProps);
    }

    private void rebuildService(ThrottlePolicy throttlePolicy) {
        service
                = new MessageIngressService(
                        new MessageValidator(),
                        throttlePolicy,
                        messageRepository,
                        smppMetrics,
                        Clock.systemUTC(),
                        observationRegistry,
                        tracePropagation);
    }

    @Test
    void acceptedSubmissionStoresTraceContextWhenCaptured() {
        when(tracePropagation.currentTraceParentHeader())
                .thenReturn("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        SubmitMessageCommand cmd
                = new SubmitMessageCommand("esme-sync", "+1000", "+2000", "hello-trace");
        Observation o = Observation.createNotStarted("submit_sm", observationRegistry).start();
        try (Observation.Scope scope = o.openScope()) {
            assertThat(service.submit(cmd)).isInstanceOf(SubmitMessageResult.Accepted.class);
        }
        ArgumentCaptor<MessageEntity> captor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageRepository).save(captor.capture());
        assertThat(captor.getValue().getTraceContext())
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    }

    @Test
    void acceptedSubmissionPersistsRecordsReceivedAndIncrementsLatency() {
        SubmitMessageCommand cmd
                = new SubmitMessageCommand("esme-sync", "+1000", "+2000", "hello-phase3");
        SubmitMessageResult result = service.submit(cmd);
        assertThat(result).isInstanceOf(SubmitMessageResult.Accepted.class);
        SubmitMessageResult.Accepted ok = (SubmitMessageResult.Accepted) result;
        assertThat(ok.messageId()).isNotBlank();

        verify(messageRepository).save(any());

        double received
                = registry.counter(SmppMetricNames.Messages.RECEIVED, "system_id", "esme-sync").count();
        assertThat(received).isEqualTo(1.0);
        double latCount = registry.timer(SmppMetricNames.Processing.LATENCY, "path", "sync").count();
        assertThat(latCount).isEqualTo(1);
    }

    @Test
    void validationFailuresDoNotPersist() {
        SubmitMessageCommand cmd
                = new SubmitMessageCommand("esme-sync", "", "+2000", "hello-phase3"); // blank source

        SubmitMessageResult result = service.submit(cmd);

        assertThat(result).isInstanceOf(SubmitMessageResult.ValidationRejected.class);
        verify(messageRepository, never()).save(any());
    }

    @Test
    void throttleSkipsPersistAndIncrementsThrottleMetric() {
        SmppThrottleProperties p = new SmppThrottleProperties();
        p.setEnabled(true);
        p.setMaxTpsPerSystemId(2);
        rebuildService(new ThrottlePolicy(p));

        SubmitMessageCommand cmd = new SubmitMessageCommand("acct-throttle", "+1", "+2", "hey");
        assertThat(service.submit(cmd)).isInstanceOf(SubmitMessageResult.Accepted.class);
        assertThat(service.submit(cmd)).isInstanceOf(SubmitMessageResult.Accepted.class);

        SubmitMessageResult third = service.submit(cmd);
        assertThat(third).isEqualTo(SubmitMessageResult.Throttled.INSTANCE);
        verify(messageRepository, times(2)).save(any());

        double throttled
                = registry.counter(SmppMetricNames.Messages.THROTTLED, "system_id", "acct-throttle").count();
        assertThat(throttled).isEqualTo(1.0);
    }

    @Test
    void storageFailuresReturnStorageFailedOutcome() {
        when(messageRepository.save(any())).thenThrow(new DataIntegrityViolationException("simulated"));

        SubmitMessageCommand cmd
                = new SubmitMessageCommand("esme-sync", "+1000", "+2000", "hello-phase3");
        SubmitMessageResult result = service.submit(cmd);

        assertThat(result).isEqualTo(SubmitMessageResult.StorageFailed.INSTANCE);

        double received
                = registry.counter(SmppMetricNames.Messages.RECEIVED, "system_id", "esme-sync").count();
        assertThat(received).isZero();
    }

    private static final class SmppMetricNames {

        static final class Messages {

            static final String RECEIVED = "smpp.messages.received";
            static final String THROTTLED = "smpp.messages.throttled";

            private Messages() {
            }
        }

        static final class Processing {

            static final String LATENCY = "smpp.processing.latency";

            private Processing() {
            }
        }
    }
}
