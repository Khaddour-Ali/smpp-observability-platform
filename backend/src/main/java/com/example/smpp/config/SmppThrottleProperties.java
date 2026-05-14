package com.example.smpp.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "smpp.throttle")
public class SmppThrottleProperties {

    private boolean enabled = true;

    @Min(1)
    private int maxTpsPerSystemId = 1000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxTpsPerSystemId() {
        return maxTpsPerSystemId;
    }

    public void setMaxTpsPerSystemId(int maxTpsPerSystemId) {
        this.maxTpsPerSystemId = maxTpsPerSystemId;
    }
}
