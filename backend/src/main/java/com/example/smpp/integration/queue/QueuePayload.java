package com.example.smpp.integration.queue;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Minimal broker payload: DB remains the source of truth for full body and
 * addresses.
 */
public final class QueuePayload {

    private final String messageId;
    private final String systemId;
    /**
     * W3C {@code traceparent} from sync accept; propagated in AMQP headers for
     * async continuation.
     */
    private final String traceContext;

    @JsonCreator
    public QueuePayload(
            @JsonProperty("messageId") String messageId,
            @JsonProperty("systemId") String systemId,
            @JsonProperty(value = "traceContext", required = false) String traceContext) {
        this.messageId = Objects.requireNonNull(messageId);
        this.systemId = Objects.requireNonNullElse(systemId, "");
        this.traceContext = traceContext;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getSystemId() {
        return systemId;
    }

    public String getTraceContext() {
        return traceContext;
    }
}
