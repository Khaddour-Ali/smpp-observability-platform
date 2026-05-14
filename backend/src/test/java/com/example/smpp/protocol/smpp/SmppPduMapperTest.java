package com.example.smpp.protocol.smpp;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.type.Address;
import com.cloudhopper.smpp.type.SmppInvalidArgumentException;
import com.example.smpp.application.dto.SubmitMessageCommand;
import org.junit.jupiter.api.Test;

class SmppPduMapperTest {

    private final SmppPduMapper mapper = new SmppPduMapper();

    @Test
    void mapsAddressesBodyAndBoundSystemId() throws SmppInvalidArgumentException {
        SubmitSm sm = new SubmitSm();
        sm.setSourceAddress(new Address((byte) 1, (byte) 1, " 1234 "));
        sm.setDestAddress(new Address((byte) 1, (byte) 1, "5678"));
        sm.setShortMessage(new byte[] {0x48, 0x69});

        SubmitMessageCommand cmd = mapper.toSubmitCommand(sm, " client-1 ");
        assertThat(cmd.getBoundSystemId()).isEqualTo("client-1");
        assertThat(cmd.getSourceAddress()).isEqualTo("1234");
        assertThat(cmd.getDestinationAddress()).isEqualTo("5678");
        assertThat(cmd.getBody()).isEqualTo("Hi");
    }

    @Test
    void nullAddressesMapToEmptyStrings() throws SmppInvalidArgumentException {
        SubmitSm sm = new SubmitSm();
        sm.setShortMessage(new byte[] {0x41});
        SubmitMessageCommand cmd = mapper.toSubmitCommand(sm, null);
        assertThat(cmd.getBoundSystemId()).isEmpty();
        assertThat(cmd.getSourceAddress()).isEmpty();
        assertThat(cmd.getDestinationAddress()).isEmpty();
        assertThat(cmd.getBody()).isEqualTo("A");
    }
}
