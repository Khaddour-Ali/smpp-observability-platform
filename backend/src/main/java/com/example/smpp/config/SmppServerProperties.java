package com.example.smpp.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "smpp.server")
public class SmppServerProperties {

    /** When false, the TCP listener is not started. */
    private boolean enabled = true;

    /** Local bind address for the Cloudhopper SMPP server listener. */
    private String host = "0.0.0.0";

    /** TCP listening port for inbound SMPP (standard well-known port is 2775). */
    @Min(1)
    @Max(65535)
    private int port = 2775;

    /** Maximum simultaneous inbound TCP connections (Cloudhopper + sizing for IO pool queue). */
    @Min(1)
    private int maxConnections = 256;

    /**
     * Size of the fixed worker {@link java.util.concurrent.ThreadPoolExecutor} passed to
     * Cloudhopper (Netty pipeline work). Bounded queue capacity is aligned with {@link
     * #maxConnections}.
     */
    @Min(1)
    private int ioThreads = 32;

    /** Threads used only for deferred protocol tasks (for example delayed close after {@code unbind_resp}). */
    @Min(1)
    private int closeSchedulerThreads = 1;

    /**
     * Value returned in bind response {@code system_id} (SMSC identity), not the client's
     * bind identity.
     */
    private String systemId = "SMPP";

    /**
     * When true, any client {@code system_id} / {@code password} on {@code bind_transceiver}
     * is accepted (assignment has no auth yet).
     */
    private boolean acceptAnyBind = true;

    /** Used when {@link #acceptAnyBind} is false. */
    private String expectedSystemId = "";

    /** Used when {@link #acceptAnyBind} is false. */
    private String expectedPassword = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public int getIoThreads() {
        return ioThreads;
    }

    public void setIoThreads(int ioThreads) {
        this.ioThreads = ioThreads;
    }

    public int getCloseSchedulerThreads() {
        return closeSchedulerThreads;
    }

    public void setCloseSchedulerThreads(int closeSchedulerThreads) {
        this.closeSchedulerThreads = closeSchedulerThreads;
    }

    public String getSystemId() {
        return systemId;
    }

    public void setSystemId(String systemId) {
        this.systemId = systemId;
    }

    public boolean isAcceptAnyBind() {
        return acceptAnyBind;
    }

    public void setAcceptAnyBind(boolean acceptAnyBind) {
        this.acceptAnyBind = acceptAnyBind;
    }

    public String getExpectedSystemId() {
        return expectedSystemId;
    }

    public void setExpectedSystemId(String expectedSystemId) {
        this.expectedSystemId = expectedSystemId;
    }

    public String getExpectedPassword() {
        return expectedPassword;
    }

    public void setExpectedPassword(String expectedPassword) {
        this.expectedPassword = expectedPassword;
    }
}
