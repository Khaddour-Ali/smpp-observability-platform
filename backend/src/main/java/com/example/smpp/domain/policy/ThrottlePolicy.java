package com.example.smpp.domain.policy;

import com.example.smpp.config.SmppThrottleProperties;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Simple per-{@code system_id} in-memory throttle for assignment load
 * scenarios. Uses a rolling 1-second window with synchronized counters - no
 * timers or executor threads (only caller threads touching the map).
 *
 * <p>
 * Map entries are bounded in practice by the number of concurrently bound ESME
 * {@code system_id} identities; acceptable for assignment scope.</p>
 */
@Component
public final class ThrottlePolicy {

    static final String UNKNOWN_SYSTEM_KEY = "__unknown__";

    private final SmppThrottleProperties props;

    /**
     * Per-window state; {@link #UNKNOWN_SYSTEM_KEY} if {@code systemId} is
     * blank.
     */
    private final ConcurrentHashMap<String, SecondBucket> buckets = new ConcurrentHashMap<>();

    public ThrottlePolicy(SmppThrottleProperties props) {
        this.props = Objects.requireNonNull(props);
    }

    /**
     * @return {@code true} when the caller may proceed, {@code false} when
     * throttled (do not persist).
     */
    public boolean tryAcquire(String systemId) {
        if (!props.isEnabled()) {
            return true;
        }
        String key = systemId == null || systemId.isBlank() ? UNKNOWN_SYSTEM_KEY : systemId.strip();
        int max = props.getMaxTpsPerSystemId();
        Instant now = Instant.now();
        long epochSecond = now.getEpochSecond();
        SecondBucket b = buckets.computeIfAbsent(key, k -> new SecondBucket());
        return b.offer(epochSecond, max);
    }

    /**
     * Single-key bucket; guarded for atomic window reset + count.
     */
    static final class SecondBucket {

        private long epochSecond = -1L;
        private int count;

        synchronized boolean offer(long nowSecond, int max) {
            if (epochSecond != nowSecond) {
                epochSecond = nowSecond;
                count = 0;
            }
            if (count >= max) {
                return false;
            }
            count++;
            return true;
        }
    }
}
