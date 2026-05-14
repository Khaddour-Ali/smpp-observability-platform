package com.example.smpp.domain.policy;

import com.example.smpp.application.dto.SubmitMessageCommand;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Validates {@link SubmitMessageCommand} for the synchronous submit path (no
 * PDU types, no SMPP wire codes).
 */
@Component
public class MessageValidator {

    /**
     * Maximum address length supported by the Flyway schema
     * ({@code VARCHAR(64)}).
     */
    public static final int MAX_ADDRESS_LENGTH = 64;

    public Optional<SubmitValidationViolation> validate(SubmitMessageCommand command) {
        String src = command.getSourceAddress();
        if (src.isEmpty()) {
            return Optional.of(SubmitValidationViolation.BLANK_SOURCE_ADDRESS);
        }
        if (src.length() > MAX_ADDRESS_LENGTH) {
            return Optional.of(SubmitValidationViolation.SOURCE_ADDRESS_TOO_LONG);
        }

        String dst = command.getDestinationAddress();
        if (dst.isEmpty()) {
            return Optional.of(SubmitValidationViolation.BLANK_DESTINATION_ADDRESS);
        }
        if (dst.length() > MAX_ADDRESS_LENGTH) {
            return Optional.of(SubmitValidationViolation.DESTINATION_ADDRESS_TOO_LONG);
        }

        if (command.getBody().isEmpty()) {
            return Optional.of(SubmitValidationViolation.BLANK_MESSAGE_BODY);
        }

        return Optional.empty();
    }
}
