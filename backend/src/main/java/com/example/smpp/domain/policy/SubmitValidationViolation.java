package com.example.smpp.domain.policy;

/**
 * Semantic validation failures for synchronous submit; mapped to wire status in
 * protocol layer.
 */
public enum SubmitValidationViolation {
    BLANK_SOURCE_ADDRESS,
    SOURCE_ADDRESS_TOO_LONG,
    BLANK_DESTINATION_ADDRESS,
    DESTINATION_ADDRESS_TOO_LONG,
    BLANK_MESSAGE_BODY
}
