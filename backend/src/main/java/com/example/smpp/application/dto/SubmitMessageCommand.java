package com.example.smpp.application.dto;

import java.util.Objects;

/**
 * Inbound synchronous submit path; built from protocol layer, never Cloudhopper
 * types.
 */
public final class SubmitMessageCommand {

    private final String boundSystemId;
    private final String sourceAddress;
    private final String destinationAddress;
    private final String body;

    public SubmitMessageCommand(String boundSystemId, String sourceAddress, String destinationAddress, String body) {
        this.boundSystemId = Objects.requireNonNullElse(boundSystemId, "");
        this.sourceAddress = Objects.requireNonNullElse(sourceAddress, "");
        this.destinationAddress = Objects.requireNonNullElse(destinationAddress, "");
        this.body = Objects.requireNonNullElse(body, "");
    }

    public String getBoundSystemId() {
        return boundSystemId;
    }

    public String getSourceAddress() {
        return sourceAddress;
    }

    public String getDestinationAddress() {
        return destinationAddress;
    }

    public String getBody() {
        return body;
    }
}
