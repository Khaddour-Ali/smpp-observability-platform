package com.example.smpp.integration.sender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.smpp.config.MockSenderClientConfig;
import com.example.smpp.config.MockSenderProperties;
import com.example.smpp.domain.model.MessageStatus;
import com.example.smpp.repository.MessageEntity;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class MockMessageSenderTest {

    static HttpServer server;
    static volatile int responseCode = 200;

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(
                "/send",
                exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "text/plain");
                    byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
                    if (exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                        if (responseCode >= 400) {
                            exchange.sendResponseHeaders(responseCode, -1);
                            exchange.close();
                        } else {
                            exchange.sendResponseHeaders(responseCode, body.length);
                            try (OutputStream os = exchange.getResponseBody()) {
                                os.write(body);
                            }
                        }
                    } else {
                        exchange.sendResponseHeaders(405, -1);
                        exchange.close();
                    }
                });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void successMapsToSuccess() {
        responseCode = 200;
        MockSenderProperties props = senderProps();
        RestClient rc = new MockSenderClientConfig().mockSenderRestClient(props);
        MockMessageSender sender = new MockMessageSender(rc, props);

        MockSendResult r = sender.send(sampleRow());

        assertThat(r).isInstanceOf(MockSendResult.Success.class);
    }

    @Test
    void http500MapsToRetryable() {
        responseCode = 500;
        MockSenderProperties props = senderProps();
        RestClient rc = new MockSenderClientConfig().mockSenderRestClient(props);
        MockMessageSender sender = new MockMessageSender(rc, props);

        MockSendResult r = sender.send(sampleRow());

        assertThat(r).isInstanceOf(MockSendResult.Retryable.class);
        assertThat(((MockSendResult.Retryable) r).detail()).contains("500");
        responseCode = 200;
    }

    @Test
    void http400MapsToPermanent() {
        responseCode = 400;
        MockSenderProperties props = senderProps();
        RestClient rc = new MockSenderClientConfig().mockSenderRestClient(props);
        MockMessageSender sender = new MockMessageSender(rc, props);

        MockSendResult r = sender.send(sampleRow());

        assertThat(r).isInstanceOf(MockSendResult.Permanent.class);
        responseCode = 200;
    }

    private static MockSenderProperties senderProps() {
        MockSenderProperties props = new MockSenderProperties();
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setPath("/send");
        return props;
    }

    private static MessageEntity sampleRow() {
        MessageEntity e = mock(MessageEntity.class);
        when(e.getMessageId()).thenReturn("mid-1");
        when(e.getSystemId()).thenReturn("sys");
        when(e.getSourceAddr()).thenReturn("1");
        when(e.getDestinationAddr()).thenReturn("2");
        when(e.getBody()).thenReturn("hi");
        when(e.getStatus()).thenReturn(MessageStatus.QUEUED);
        return e;
    }
}
