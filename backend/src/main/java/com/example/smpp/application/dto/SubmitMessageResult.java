package com.example.smpp.application.dto;

import com.example.smpp.domain.policy.SubmitValidationViolation;
import java.util.Objects;

/**
 * Outcome of the synchronous ingress path; consumed by protocol to build
 * {@code submit_sm_resp}.
 */
public sealed interface SubmitMessageResult {

    record Accepted(String messageId) implements SubmitMessageResult {

        public Accepted {
            Objects.requireNonNull(messageId);
        }
    }

    record ValidationRejected(SubmitValidationViolation violation) implements SubmitMessageResult {

        public ValidationRejected {
            Objects.requireNonNull(violation);
        }
    }

    enum Throttled implements SubmitMessageResult {
        INSTANCE
    }

    enum StorageFailed implements SubmitMessageResult {
        INSTANCE
    }
}
