package com.example.smpp.application.dto;

import java.time.Instant;

/**
 * Failed row snapshot for admin inspection (DB is source of truth).
 */
public record FailedMessageResponse(
        String messageId,
        String systemId,
        String sourceAddr,
        String destinationAddr,
        String body,
        Instant receivedAt,
        Instant queuedAt,
        Instant processedAt,
        int attemptCount,
        String lastError,
        Instant updatedAt) {

}
