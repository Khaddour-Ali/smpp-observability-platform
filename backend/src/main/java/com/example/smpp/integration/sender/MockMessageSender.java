package com.example.smpp.integration.sender;

import com.example.smpp.config.MockSenderProperties;
import com.example.smpp.repository.MessageEntity;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestClient;

/**
 * Calls the mock HTTP sending API. Maps transport and HTTP status to
 * retryable vs permanent outcomes; does not interpret message lifecycle.
 */
@Component
@ConditionalOnProperty(prefix = "smpp.queue", name = "enabled", havingValue = "true")
public class MockMessageSender {

    private final RestClient restClient;
    private final MockSenderProperties properties;

    public MockMessageSender(
            @Qualifier("mockSenderRestClient") RestClient mockSenderRestClient, MockSenderProperties properties) {
        this.restClient = Objects.requireNonNull(mockSenderRestClient);
        this.properties = Objects.requireNonNull(properties);
    }

    public MockSendResult send(MessageEntity row) {
        if (row.getMessageId() == null || row.getMessageId().isBlank()) {
            return new MockSendResult.Permanent("missing message_id");
        }

        URI uri = URI.create(sanitizeBase(properties.getBaseUrl()) + normalizePath(properties.getPath()));

        Map<String, Object> body
                = Map.of(
                        "messageId", row.getMessageId(),
                        "systemId", row.getSystemId(),
                        "sourceAddress", row.getSourceAddr(),
                        "destinationAddress", row.getDestinationAddr(),
                        "body", row.getBody());

        try {
            RestClient.ResponseSpec spec
                    = restClient
                            .post()
                            .uri(uri)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve();

            spec.toBodilessEntity();
            return new MockSendResult.Success();
        } catch (HttpClientErrorException ex) {
            HttpStatusCode code = ex.getStatusCode();
            if (code.value() == 408) {
                return new MockSendResult.Retryable("HTTP 408");
            }
            return new MockSendResult.Permanent(
                    "HTTP " + code.value() + ": " + truncate(ex.getResponseBodyAsString(StandardCharsets.UTF_8)));
        } catch (HttpServerErrorException ex) {
            return new MockSendResult.Retryable("HTTP " + ex.getStatusCode().value());
        } catch (ResourceAccessException ex) {
            return new MockSendResult.Retryable(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } catch (Exception ex) {
            return new MockSendResult.Retryable(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private static String sanitizeBase(String base) {
        String s = Objects.requireNonNullElse(base, "").strip();
        if (s.endsWith("/")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String normalizePath(String path) {
        String p = path == null ? "" : path.strip();
        if (p.isEmpty()) {
            return "";
        }
        return p.startsWith("/") ? p : "/" + p;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        if (s.length() <= 512) {
            return s;
        }
        return s.substring(0, 512) + "...";
    }
}
