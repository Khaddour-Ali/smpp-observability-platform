package com.example.smpp.application.dto;

import java.time.Instant;

/** Aggregate message counts by lifecycle status for admin dashboard / ops. */
public record MessageStatsResponse(
        long received,
        long queued,
        long processed,
        long failed,
        long total,
        Instant generatedAt) {}
