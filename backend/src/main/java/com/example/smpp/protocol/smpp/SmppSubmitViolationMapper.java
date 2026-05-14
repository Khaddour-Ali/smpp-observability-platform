package com.example.smpp.protocol.smpp;

import com.cloudhopper.smpp.SmppConstants;
import com.example.smpp.domain.policy.SubmitValidationViolation;

/**
 * Maps semantic domain violations to SMPP wire {@code command_status} values.
 */
public final class SmppSubmitViolationMapper {

    private SmppSubmitViolationMapper() {
    }

    public static int toCommandStatus(SubmitValidationViolation violation) {
        return switch (violation) {
            case BLANK_SOURCE_ADDRESS ->
                SmppConstants.STATUS_INVSRCADR;
            case SOURCE_ADDRESS_TOO_LONG ->
                SmppConstants.STATUS_INVPARLEN;
            case BLANK_DESTINATION_ADDRESS ->
                SmppConstants.STATUS_INVDSTADR;
            case DESTINATION_ADDRESS_TOO_LONG ->
                SmppConstants.STATUS_INVPARLEN;
            case BLANK_MESSAGE_BODY ->
                SmppConstants.STATUS_INVMSGLEN;
        };
    }
}
