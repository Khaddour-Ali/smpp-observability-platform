package com.example.smpp.integration.sender;

/**
 * Outcome of calling the mock HTTP sender (integration layer only).
 */
public sealed interface MockSendResult {

    record Success() implements MockSendResult {
    }

    /**
     * Transient failures: timeout, connection error, HTTP 5xx.
     */
    record Retryable(String detail) implements MockSendResult {

    }

    /**
     * No point retrying: HTTP 4xx, bad payload, etc.
     */
    record Permanent(String detail) implements MockSendResult {

    }
}
