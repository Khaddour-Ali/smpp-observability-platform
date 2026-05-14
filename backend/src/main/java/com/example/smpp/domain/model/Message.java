package com.example.smpp.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Domain representation of a persisted message row (lifecycle state holder).
 */
public final class Message {

    private final String messageId;
    private final String systemId;
    private final String sourceAddr;
    private final String destinationAddr;
    private final String body;
    private final MessageStatus status;
    private final Instant receivedAt;
    private final Instant queuedAt;
    private final Instant processedAt;
    private final int attemptCount;
    private final String lastError;
    /**
     * W3C {@code traceparent} captured at sync accept time for async
     * continuation; may be null.
     */
    private final String traceContext;
    private final Instant updatedAt;

    public Message(
            String messageId,
            String systemId,
            String sourceAddr,
            String destinationAddr,
            String body,
            MessageStatus status,
            Instant receivedAt,
            Instant queuedAt,
            Instant processedAt,
            int attemptCount,
            String lastError,
            String traceContext,
            Instant updatedAt) {
        this.messageId = Objects.requireNonNull(messageId);
        this.systemId = Objects.requireNonNull(systemId);
        this.sourceAddr = Objects.requireNonNull(sourceAddr);
        this.destinationAddr = Objects.requireNonNull(destinationAddr);
        this.body = Objects.requireNonNull(body);
        this.status = Objects.requireNonNull(status);
        this.receivedAt = Objects.requireNonNull(receivedAt);
        this.queuedAt = queuedAt;
        this.processedAt = processedAt;
        this.attemptCount = attemptCount;
        this.lastError = lastError;
        this.traceContext = traceContext;
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    /**
     * Factory for newly accepted synchronous-path rows (assignment initial
     * state RECEIVED).
     */
    public static Message received(
            String messageId,
            String systemId,
            String sourceAddr,
            String destinationAddr,
            String body,
            Instant now) {
        return received(messageId, systemId, sourceAddr, destinationAddr, body, now, null);
    }

    public static Message received(
            String messageId,
            String systemId,
            String sourceAddr,
            String destinationAddr,
            String body,
            Instant now,
            String traceContext) {
        return new Message(
                messageId,
                systemId,
                sourceAddr,
                destinationAddr,
                body,
                MessageStatus.RECEIVED,
                now,
                null,
                null,
                0,
                null,
                traceContext,
                now);
    }

    public String getMessageId() {
        return messageId;
    }

    public String getSystemId() {
        return systemId;
    }

    public String getSourceAddr() {
        return sourceAddr;
    }

    public String getDestinationAddr() {
        return destinationAddr;
    }

    public String getBody() {
        return body;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getLastError() {
        return lastError;
    }

    public String getTraceContext() {
        return traceContext;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
