package com.example.smpp.application.service;

/**
 * How the consumer should acknowledge the RabbitMQ delivery.
 */
public enum QueueProcessOutcome {
    COMPLETE,
    REQUEUE,
    DEFERRED_RETRY
}
