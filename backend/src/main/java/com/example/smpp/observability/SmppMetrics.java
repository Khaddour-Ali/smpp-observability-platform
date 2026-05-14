package com.example.smpp.observability;

import com.example.smpp.protocol.smpp.SmppSessionRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Micrometer bindings for required SMPP gateway metrics 
 * (sync + async processing).
 */
@Component
public class SmppMetrics {

    public static final String TAG_SYSTEM_ID = "system_id";
    public static final String TAG_PATH = "path";
    public static final String TAG_STATUS = "status";
    public static final String PATH_SYNC = "sync";
    public static final String PATH_ASYNC = "async";

    private final MeterRegistry registry;
    private final SmppSessionRegistry sessionRegistry;

    private Timer syncProcessingTimer;
    private Timer asyncProcessingTimer;

    public SmppMetrics(MeterRegistry registry, SmppSessionRegistry sessionRegistry) {
        this.registry = Objects.requireNonNull(registry);
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry);
    }

    @PostConstruct
    public void registerMeters() {
        this.syncProcessingTimer
                = Timer.builder("smpp.processing.latency")
                        .description("Wall time for one synchronous submit ingress call")
                        .tag(TAG_PATH, PATH_SYNC)
                        .register(registry);

        this.asyncProcessingTimer
                = Timer.builder("smpp.processing.latency")
                        .description("Wall time for async worker handling of one queued message id")
                        .tag(TAG_PATH, PATH_ASYNC)
                        .register(registry);

        Gauge.builder("smpp.sessions.active", sessionRegistry, SmppSessionRegistry::getActiveSessionCount)
                .description("Inbound SMPP sessions currently bound")
                .register(registry);
    }

    public Timer syncProcessingTimer() {
        return syncProcessingTimer;
    }

    public Timer asyncProcessingTimer() {
        return asyncProcessingTimer;
    }

    /**
     * Increments {@code smpp.messages.processed} with low-cardinality
     * {@code status} label.
     */
    public void recordProcessed(String status) {
        Counter.builder("smpp.messages.processed")
                .tag(TAG_STATUS, status)
                .register(registry)
                .increment();
    }

    public void recordReceived(String boundSystemId) {
        counter("smpp.messages.received", sanitizeSystemId(boundSystemId)).increment();
    }

    public void recordThrottled(String boundSystemId) {
        counter("smpp.messages.throttled", sanitizeSystemId(boundSystemId)).increment();
    }

    private Counter counter(String name, String systemId) {
        return Counter.builder(name)
                .tag(TAG_SYSTEM_ID, systemId)
                .register(registry);
    }

    /**
     * Low-cardinality tag: bound ESME {@code system_id} is short; unknown is
     * explicit.
     */
    public static String sanitizeSystemId(String boundSystemId) {
        if (boundSystemId == null || boundSystemId.isBlank()) {
            return "unknown";
        }
        String s = boundSystemId.strip();
        if (s.length() > 64) {
            return s.substring(0, 64);
        }
        return s;
    }

    public static long nanosSince(long startNanos) {
        return System.nanoTime() - startNanos;
    }

    public void recordSyncLatencyNanos(long nanos) {
        if (nanos < 0) {
            nanos = 0;
        }
        syncProcessingTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordAsyncLatencyNanos(long nanos) {
        if (nanos < 0) {
            nanos = 0;
        }
        asyncProcessingTimer.record(nanos, TimeUnit.NANOSECONDS);
    }
}
