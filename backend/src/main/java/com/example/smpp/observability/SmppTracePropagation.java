package com.example.smpp.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import jakarta.annotation.Nullable;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public class SmppTracePropagation {

    public static final String TRACEPARENT_HEADER = "traceparent";

    private final @Nullable
    Tracer tracer;
    private final @Nullable
    Propagator propagator;

    public SmppTracePropagation(
            ObjectProvider<Tracer> tracerProvider, ObjectProvider<Propagator> propagatorProvider) {
        this.tracer = tracerProvider.getIfAvailable();
        this.propagator = propagatorProvider.getIfAvailable();
    }

    @Nullable
    public String currentTraceParentHeader() {
        if (tracer == null || propagator == null) {
            return null;
        }
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        TraceContext ctx = span.context();
        if (ctx == null) {
            return null;
        }
        HttpHeaders carrier = new HttpHeaders();
        propagator.inject(ctx, carrier, (c, key, value) -> {
            if (value != null) {
                c.add(key, value);
            }
        });
        return carrier.getFirst(TRACEPARENT_HEADER);
    }

    /**
     * Run work as a child span of the remote trace (e.g. {@code queue.publish},
     * {@code async.worker.processing}).
     */
    public void withRemoteParent(@Nullable String traceparent, String spanName, Runnable work) {
        withRemoteParent(traceparent, spanName, null, work);
    }

    public void withRemoteParent(
            @Nullable String traceparent, String spanName, @Nullable String queueName, Runnable work) {
        withRemoteParent(traceparent, spanName, queueName, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Same as {@link #withRemoteParent(String, String, Runnable)} but returns a
     * value.
     */
    public <T> T withRemoteParent(@Nullable String traceparent, String spanName, Supplier<T> work) {
        return withRemoteParent(traceparent, spanName, null, work);
    }

    public <T> T withRemoteParent(
            @Nullable String traceparent,
            String spanName,
            @Nullable String queueName,
            Supplier<T> work) {
        if (traceparent == null || traceparent.isBlank() || tracer == null || propagator == null) {
            return work.get();
        }
        HttpHeaders incoming = new HttpHeaders();
        incoming.add(TRACEPARENT_HEADER, traceparent);
        Span.Builder remote = propagator.extract(incoming, HttpHeaders::getFirst);
        if (remote == null || remote == Span.Builder.NOOP) {
            return work.get();
        }
        Span.Builder builder = remote.name(spanName).tag("smpp.command", spanName);
        if (queueName != null && !queueName.isBlank()) {
            builder = builder.tag("queue.name", queueName);
        }
        Span span = builder.start();
        if (span.isNoop()) {
            span.end();
            return work.get();
        }
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            return work.get();
        } finally {
            span.end();
        }
    }
}
