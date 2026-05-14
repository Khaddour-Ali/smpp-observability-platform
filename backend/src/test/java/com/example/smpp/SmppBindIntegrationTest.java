package com.example.smpp;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.EnquireLink;
import com.cloudhopper.smpp.pdu.EnquireLinkResp;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.type.Address;
import com.cloudhopper.smpp.type.SmppInvalidArgumentException;
import com.example.smpp.domain.model.MessageStatus;
import com.example.smpp.protocol.smpp.SmppSessionRegistry;
import com.example.smpp.repository.MessageEntity;
import com.example.smpp.repository.MessageRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Cloudhopper client against the Spring-managed SMPP server
 * (bind/enquire/submit/unbind).
 */
class SmppBindIntegrationTest extends AbstractPostgresIntegrationTest {

    private static SubmitSm validSubmitPdu() throws SmppInvalidArgumentException {
        SubmitSm submit = new SubmitSm();
        submit.setSourceAddress(new Address((byte) 0x01, (byte) 0x01, "1234"));
        submit.setDestAddress(new Address((byte) 0x01, (byte) 0x01, "5678"));
        submit.setShortMessage(new byte[]{0x48, 0x69});
        return submit;
    }

    @Autowired
    SmppSessionRegistry smppSessionRegistry;

    @Autowired
    MessageRepository messageRepository;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void bindTransceiverThenEnquireLinkSubmitPersistsReceivedThenUnbind() throws Exception {
        assertThat(smppIntegrationListenPort()).isPositive();
        assertThat(smppSessionRegistry.getActiveSessionCount()).isZero();

        double receivedBefore
                = meterRegistry.counter("smpp.messages.received", "system_id", "it-client").count();
        long rowsBefore = messageRepository.count();

        AtomicInteger clientSeq = new AtomicInteger();
        ExecutorService clientIo
                = Executors.newFixedThreadPool(
                        2,
                        r -> {
                            Thread t = new Thread(r, "smpp-test-client-io-" + clientSeq.incrementAndGet());
                            t.setDaemon(true);
                            return t;
                        });

        DefaultSmppClient client = new DefaultSmppClient(clientIo, 1);
        try {
            SmppSessionConfiguration cfg = new SmppSessionConfiguration();
            cfg.setName("integration-test-session");
            cfg.setHost("127.0.0.1");
            cfg.setPort(smppIntegrationListenPort());
            cfg.setSystemId("it-client");
            cfg.setPassword("it-secret");
            cfg.setBindTimeout(7000);
            cfg.setWindowSize(1);
            cfg.setType(SmppBindType.TRANSCEIVER);

            SmppSession session = client.bind(cfg, new DefaultSmppSessionHandler());
            try {
                assertThat(smppSessionRegistry.getActiveSessionCount()).isEqualTo(1);

                EnquireLinkResp enquireResp = session.enquireLink(new EnquireLink(), 5000);
                assertThat(enquireResp.getCommandStatus()).isEqualTo(SmppConstants.STATUS_OK);

                SubmitSm submit = validSubmitPdu();
                SubmitSmResp submitResp = session.submit(submit, TimeUnit.SECONDS.toMillis(5));
                assertThat(submitResp.getCommandStatus()).isEqualTo(SmppConstants.STATUS_OK);
                assertThat(submitResp.getMessageId()).isNotBlank();

                Optional<MessageEntity> persisted = messageRepository.findByMessageId(submitResp.getMessageId());
                assertThat(persisted).isPresent();
                assertThat(persisted.get().getStatus()).isEqualTo(MessageStatus.RECEIVED);
                assertThat(persisted.get().getSystemId()).isEqualTo("it-client");
                assertThat(persisted.get().getSourceAddr()).isEqualTo("1234");
                assertThat(persisted.get().getDestinationAddr()).isEqualTo("5678");
                assertThat(persisted.get().getBody()).isEqualTo("Hi");

                double receivedAfter
                        = meterRegistry.counter("smpp.messages.received", "system_id", "it-client").count();
                assertThat(receivedAfter - receivedBefore).isEqualTo(1.0);

                assertThat(messageRepository.count()).isEqualTo(rowsBefore + 1);

                assertThat(registryHasTimerObservation()).isTrue();

                session.unbind(TimeUnit.SECONDS.toMillis(10));
            } finally {
                try {
                    session.destroy();
                } catch (Exception ignored) {
                    // session may already be torn down after unbind
                }
            }

            waitUntilActiveSessions(0, 10_000);
            assertThat(smppSessionRegistry.getActiveSessionCount()).isZero();
        } finally {
            try {
                client.destroy();
            } finally {
                clientIo.shutdown();
                if (!clientIo.awaitTermination(5, TimeUnit.SECONDS)) {
                    clientIo.shutdownNow();
                    clientIo.awaitTermination(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void invalidSubmitDoesNotPersist() throws Exception {
        long rowsBefore = messageRepository.count();

        AtomicInteger clientSeq = new AtomicInteger();
        ExecutorService clientIo
                = Executors.newFixedThreadPool(
                        2,
                        r -> {
                            Thread t = new Thread(r, "smpp-test-client-io-inv-" + clientSeq.incrementAndGet());
                            t.setDaemon(true);
                            return t;
                        });

        DefaultSmppClient client = new DefaultSmppClient(clientIo, 1);
        try {
            SmppSessionConfiguration cfg = new SmppSessionConfiguration();
            cfg.setHost("127.0.0.1");
            cfg.setPort(smppIntegrationListenPort());
            cfg.setSystemId("bad-submit-client");
            cfg.setPassword("secret");
            cfg.setBindTimeout(7000);
            cfg.setWindowSize(1);
            cfg.setType(SmppBindType.TRANSCEIVER);

            SmppSession session = client.bind(cfg, new DefaultSmppSessionHandler());
            try {
                SubmitSm submit = new SubmitSm();
                submit.setSourceAddress(new Address((byte) 1, (byte) 1, ""));
                submit.setDestAddress(new Address((byte) 1, (byte) 1, "5678"));
                submit.setShortMessage(new byte[]{0x61});

                SubmitSmResp resp = session.submit(submit, TimeUnit.SECONDS.toMillis(5));
                assertThat(resp.getCommandStatus()).isEqualTo(SmppConstants.STATUS_INVSRCADR);
            } finally {
                session.unbind(TimeUnit.SECONDS.toMillis(10));
                try {
                    session.destroy();
                } catch (Exception ignored) {
                }
            }

            waitUntilActiveSessions(0, 10_000);
            assertThat(messageRepository.count()).isEqualTo(rowsBefore);
        } finally {
            try {
                client.destroy();
            } finally {
                clientIo.shutdown();
                if (!clientIo.awaitTermination(5, TimeUnit.SECONDS)) {
                    clientIo.shutdownNow();
                    clientIo.awaitTermination(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    private boolean registryHasTimerObservation() {
        return meterRegistry.find("smpp.processing.latency").tag("path", "sync").timer().count() >= 1;
    }

    private void waitUntilActiveSessions(int expected, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (smppSessionRegistry.getActiveSessionCount() == expected) {
                return;
            }
            Thread.sleep(50L);
        }
        assertThat(smppSessionRegistry.getActiveSessionCount()).isEqualTo(expected);
    }
}
