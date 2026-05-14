package com.example.smpp.application.dto;

import java.time.Instant;

/** Rolling window throughput derived from {@code PROCESSED} rows and {@code processedAt}. */
public record ThroughputResponse(
        int windowSeconds,
        Instant since,
        Instant generatedAt,
        long processedCount,
        double messagesPerSecond) {}
