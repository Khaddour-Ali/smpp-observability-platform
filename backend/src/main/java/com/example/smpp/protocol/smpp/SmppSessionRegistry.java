package com.example.smpp.protocol.smpp;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Tracks the number of successfully bound (active) SMPP server sessions.
 * Incremented when a server session becomes ready after
 * {@code bind_transceiver_resp}; decremented when the session is destroyed
 * (client {@code unbind} or disconnect).
 */
@Component
public class SmppSessionRegistry {

    private final AtomicInteger activeBoundSessions = new AtomicInteger(0);

    public void sessionBound() {
        activeBoundSessions.incrementAndGet();
    }

    public void sessionUnbound() {
        activeBoundSessions.updateAndGet(v -> v > 0 ? v - 1 : 0);
    }

    public int getActiveSessionCount() {
        return activeBoundSessions.get();
    }
}
