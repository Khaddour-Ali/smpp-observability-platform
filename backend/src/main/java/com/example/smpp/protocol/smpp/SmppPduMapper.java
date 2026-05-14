package com.example.smpp.protocol.smpp;

import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.type.Address;
import com.example.smpp.application.dto.SubmitMessageCommand;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public class SmppPduMapper {

    public SubmitMessageCommand toSubmitCommand(SubmitSm pdu, String boundSystemId) {
        String systemId = boundSystemId == null ? "" : boundSystemId.strip();
        return new SubmitMessageCommand(
                systemId,
                addressToNormalizedString(pdu.getSourceAddress()),
                addressToNormalizedString(pdu.getDestAddress()),
                decodeShortMessage(pdu.getShortMessage()));
    }

    private static String addressToNormalizedString(Address address) {
        if (address == null || address.getAddress() == null) {
            return "";
        }
        return address.getAddress().strip();
    }

    static String decodeShortMessage(byte[] sm) {
        if (sm == null || sm.length == 0) {
            return "";
        }
        return new String(sm, StandardCharsets.UTF_8);
    }
}
