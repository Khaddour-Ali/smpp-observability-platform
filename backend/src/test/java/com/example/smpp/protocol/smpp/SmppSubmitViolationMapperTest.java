package com.example.smpp.protocol.smpp;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudhopper.smpp.SmppConstants;
import com.example.smpp.domain.policy.SubmitValidationViolation;
import org.junit.jupiter.api.Test;

class SmppSubmitViolationMapperTest {

    @Test
    void mapsViolationsToStableWireCodes() {
        assertThat(SmppSubmitViolationMapper.toCommandStatus(SubmitValidationViolation.BLANK_SOURCE_ADDRESS))
                .isEqualTo(SmppConstants.STATUS_INVSRCADR);
        assertThat(SmppSubmitViolationMapper.toCommandStatus(SubmitValidationViolation.BLANK_DESTINATION_ADDRESS))
                .isEqualTo(SmppConstants.STATUS_INVDSTADR);
        assertThat(SmppSubmitViolationMapper.toCommandStatus(SubmitValidationViolation.BLANK_MESSAGE_BODY))
                .isEqualTo(SmppConstants.STATUS_INVMSGLEN);
        assertThat(SmppSubmitViolationMapper.toCommandStatus(SubmitValidationViolation.SOURCE_ADDRESS_TOO_LONG))
                .isEqualTo(SmppConstants.STATUS_INVPARLEN);
    }
}
