package com.example.smpp.domain.model;

/**
 * Public message lifecycle states required by the assignment.
 */
public enum MessageStatus {
    RECEIVED,
    QUEUED,
    PROCESSED,
    FAILED
}
