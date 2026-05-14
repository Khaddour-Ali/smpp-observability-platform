package com.example.smpp.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "smpp.queue")
public class QueueProperties {

    /**
     * When false, RabbitMQ beans and handoff scheduler are disabled (e.g.
     * SMPP-only integration tests).
     */
    private boolean enabled = true;

    private String exchange = "smpp.direct";
    
    private String queue = "smpp.messages.process";
    private String routingKey = "messages.process";

    /**
     * Dead-letter exchange for nacked deliveries (routes to retry or ops DLQ).
     */
    private String dlxExchange = "smpp.dlx";

    /**
     * Routing key from main queue dead-letter -> TTL retry queue.
     */
    private String retryRoutingKey = "messages.process.retry";

    /**
     * Routing key for operator-visible DLQ (explicit publish after FAILED).
     */
    private String dlqRoutingKey = "messages.process.dlq";

    private String retryQueue = "smpp.messages.process.retry";
    private String dlqQueue = "smpp.messages.process.dlq";

    /**
     * Per-message TTL on the retry queue before RabbitMQ dead-letters back to
     * {@link #getExchange()}.
     */
    @Min(1L)
    private long messageRetryTtlMs = 2_000L;

    @Min(50)
    private long handoffIntervalMs = 500L;

    @Min(1)
    private int batchSize = 50;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public String getQueue() {
        return queue;
    }

    public void setQueue(String queue) {
        this.queue = queue;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public void setRoutingKey(String routingKey) {
        this.routingKey = routingKey;
    }

    public String getDlxExchange() {
        return dlxExchange;
    }

    public void setDlxExchange(String dlxExchange) {
        this.dlxExchange = dlxExchange;
    }

    public String getRetryRoutingKey() {
        return retryRoutingKey;
    }

    public void setRetryRoutingKey(String retryRoutingKey) {
        this.retryRoutingKey = retryRoutingKey;
    }

    public String getDlqRoutingKey() {
        return dlqRoutingKey;
    }

    public void setDlqRoutingKey(String dlqRoutingKey) {
        this.dlqRoutingKey = dlqRoutingKey;
    }

    public String getRetryQueue() {
        return retryQueue;
    }

    public void setRetryQueue(String retryQueue) {
        this.retryQueue = retryQueue;
    }

    public String getDlqQueue() {
        return dlqQueue;
    }

    public void setDlqQueue(String dlqQueue) {
        this.dlqQueue = dlqQueue;
    }

    public long getMessageRetryTtlMs() {
        return messageRetryTtlMs;
    }

    public void setMessageRetryTtlMs(long messageRetryTtlMs) {
        this.messageRetryTtlMs = messageRetryTtlMs;
    }

    public long getHandoffIntervalMs() {
        return handoffIntervalMs;
    }

    public void setHandoffIntervalMs(long handoffIntervalMs) {
        this.handoffIntervalMs = handoffIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
