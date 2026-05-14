package com.example.smpp;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.smpp.config.QueueProperties;
import com.example.smpp.domain.model.Message;
import com.example.smpp.domain.model.MessageStatus;
import com.example.smpp.integration.queue.QueuePayload;
import com.example.smpp.repository.MessageEntityMapper;
import com.example.smpp.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * Optional dual-container (Postgres + RabbitMQ) pipeline tests:
 * {@code RECEIVED -> QUEUED -> outcome} with the mock HTTP sender.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MessageQueuePipelineIntegrationTest {

    /**
     * HTTP status for POST /send; default 200, failure test sets 500.
     */
    private static final AtomicInteger MOCK_SEND_HTTP_STATUS = new AtomicInteger(200);

    private static final PostgreSQLContainer<?> POSTGRES
            = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final RabbitMQContainer RABBIT
            = new RabbitMQContainer("rabbitmq:3.13-alpine");
    private static final HttpServer MOCK_SENDER;

    static {
        POSTGRES.start();
        RABBIT.start();
        try {
            MOCK_SENDER = HttpServer.create(new InetSocketAddress(0), 0);
            MOCK_SENDER.createContext(
                    "/send",
                    exchange -> {
                        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                            exchange.sendResponseHeaders(405, -1);
                            exchange.close();
                            return;
                        }
                        int status = MOCK_SEND_HTTP_STATUS.get();
                        String body
                        = status >= 400
                                ? "ERR"
                                : "OK"; // avoids zero-length confusion on strict clients
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(status, bytes.length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(bytes);
                        }
                    });
            MOCK_SENDER.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void registerContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> String.valueOf(RABBIT.getAmqpPort()));
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
        registry.add("smpp.server.enabled", () -> "false");
        registry.add("smpp.queue.enabled", () -> "true");
        registry.add("smpp.queue.handoff-interval-ms", () -> "150");
        registry.add("smpp.queue.message-retry-ttl-ms", () -> "150");
        registry.add("smpp.mock-sender.max-attempts", () -> "2");
        registry.add(
                "smpp.mock-sender.base-url",
                () -> "http://127.0.0.1:" + MOCK_SENDER.getAddress().getPort());
    }

    @Autowired
    MessageRepository messageRepository;
    @Autowired
    MeterRegistry meterRegistry;
    @Autowired
    RabbitTemplate rabbitTemplate;
    @Autowired
    QueueProperties queueProperties;
    @Autowired
    ObjectMapper objectMapper;

    @Test
    @Order(1)
    void receivedEventuallyQueuedThenProcessed() throws Exception {
        MOCK_SEND_HTTP_STATUS.set(200);
        Instant now = Instant.now();
        Message pending
                = Message.received("pipeline-id-1", "it-esme", "111", "222", "Hi", now);
        messageRepository.save(MessageEntityMapper.toEntity(pending));

        awaitStatus("pipeline-id-1", MessageStatus.PROCESSED, 30_000);

        assertThat(meterRegistry.counter("smpp.messages.processed", "status", "PROCESSED").count())
                .isGreaterThanOrEqualTo(1.0);
        assertThat(
                meterRegistry
                        .find("smpp.processing.latency")
                        .tag("path", "async")
                        .timer()
                        .count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(2)
    void retryExhaustionMarksFailedDlqPayloadAndMetrics() throws Exception {
        MOCK_SEND_HTTP_STATUS.set(500);
        Instant now = Instant.now();
        String messageId = "pipeline-id-fail-1";
        Message pending
                = Message.received(messageId, "it-esme", "333", "444", "boom", now);
        messageRepository.save(MessageEntityMapper.toEntity(pending));

        awaitStatus(messageId, MessageStatus.FAILED, 45_000);

        var row = messageRepository.findByMessageId(messageId);
        assertThat(row).isPresent();
        assertThat(row.get().getAttemptCount()).isEqualTo(2);
        assertThat(row.get().getLastError())
                .isNotBlank()
                .containsIgnoringCase("exhausted");

        assertThat(meterRegistry.counter("smpp.messages.processed", "status", "FAILED").count())
                .isGreaterThanOrEqualTo(1.0);

        String dlqName = queueProperties.getDlqQueue();
        org.springframework.amqp.core.Message raw = rabbitTemplate.receive(dlqName, 25_000L);
        assertThat(raw).isNotNull();
        QueuePayload fromDlq
                = objectMapper.readValue(raw.getBody(), QueuePayload.class);
        assertThat(fromDlq.getMessageId()).isEqualTo(messageId);
        assertThat(fromDlq.getSystemId()).isEqualTo("it-esme");

        MOCK_SEND_HTTP_STATUS.set(200);
    }

    private void awaitStatus(String messageId, MessageStatus expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            var rowOpt = messageRepository.findByMessageId(messageId);
            if (rowOpt.isPresent() && rowOpt.get().getStatus() == expected) {
                return;
            }
            Thread.sleep(50L);
        }
        assertThat(messageRepository.findByMessageId(messageId).map(r -> r.getStatus()))
                .hasValue(expected);
    }
}
