package com.example.smpp.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "smpp.mock-sender")
public class MockSenderProperties {

    @NotBlank
    private String baseUrl = "http://localhost:8081";

    /**
     * HTTP path on the mock sender (POST).
     */
    @NotBlank
    private String path = "/send";

    @Min(100)
    @Max(120_000)
    private int connectTimeoutMs = 5_000;

    @Min(100)
    @Max(120_000)
    private int readTimeoutMs = 10_000;

    /**
     * Total send attempts (first try + broker-delayed retries) before
     * {@code FAILED}.
     */
    @Min(1)
    @Max(32)
    private int maxAttempts = 3;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }
}
