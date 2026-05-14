package com.example.smpp.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.smpp.config.SmppThrottleProperties;
import org.junit.jupiter.api.Test;

class ThrottlePolicyTest {

    @Test
    void allowsUpToMaxTpsPerSecond() {
        SmppThrottleProperties p = new SmppThrottleProperties();
        p.setEnabled(true);
        p.setMaxTpsPerSystemId(2);
        ThrottlePolicy policy = new ThrottlePolicy(p);
        assertThat(policy.tryAcquire("a")).isTrue();
        assertThat(policy.tryAcquire("a")).isTrue();
        assertThat(policy.tryAcquire("a")).isFalse();
    }

    @Test
    void whenDisabledAlwaysAllows() {
        SmppThrottleProperties p = new SmppThrottleProperties();
        p.setEnabled(false);
        p.setMaxTpsPerSystemId(1);
        ThrottlePolicy policy = new ThrottlePolicy(p);
        assertThat(policy.tryAcquire("x")).isTrue();
        assertThat(policy.tryAcquire("x")).isTrue();
    }

    @Test
    void separateSystemIdsDoNotShareCounters() {
        SmppThrottleProperties p = new SmppThrottleProperties();
        p.setEnabled(true);
        p.setMaxTpsPerSystemId(1);
        ThrottlePolicy policy = new ThrottlePolicy(p);
        assertThat(policy.tryAcquire("u1")).isTrue();
        assertThat(policy.tryAcquire("u2")).isTrue();
    }
}
