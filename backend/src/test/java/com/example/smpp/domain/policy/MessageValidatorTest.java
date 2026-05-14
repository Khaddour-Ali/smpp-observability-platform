package com.example.smpp.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.smpp.application.dto.SubmitMessageCommand;
import org.junit.jupiter.api.Test;

class MessageValidatorTest {

    private final MessageValidator validator = new MessageValidator();

    @Test
    void acceptsWellFormedCommand() {
        var cmd = new SubmitMessageCommand("esme1", "441234", "449876", "hello");
        assertThat(validator.validate(cmd)).isEmpty();
    }

    @Test
    void rejectsBlankSource() {
        var cmd = new SubmitMessageCommand("esme1", "", "449876", "hello");
        assertThat(validator.validate(cmd))
                .contains(SubmitValidationViolation.BLANK_SOURCE_ADDRESS);
    }

    @Test
    void rejectsBlankDestination() {
        var cmd = new SubmitMessageCommand("esme1", "441234", "", "hello");
        assertThat(validator.validate(cmd))
                .contains(SubmitValidationViolation.BLANK_DESTINATION_ADDRESS);
    }

    @Test
    void rejectsBlankBody() {
        var cmd = new SubmitMessageCommand("esme1", "441234", "449876", "");
        assertThat(validator.validate(cmd)).contains(SubmitValidationViolation.BLANK_MESSAGE_BODY);
    }

    @Test
    void rejectsSourceLongerThanSchema() {
        var cmd = new SubmitMessageCommand(
                "esme1", "x".repeat(MessageValidator.MAX_ADDRESS_LENGTH + 1), "449876", "hello");
        assertThat(validator.validate(cmd))
                .contains(SubmitValidationViolation.SOURCE_ADDRESS_TOO_LONG);
    }
}
