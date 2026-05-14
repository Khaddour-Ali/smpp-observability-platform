package com.example.smpp.protocol.smpp;

import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.pdu.EnquireLink;
import com.cloudhopper.smpp.pdu.EnquireLinkResp;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.pdu.Unbind;
import com.cloudhopper.smpp.pdu.UnbindResp;
import com.example.smpp.application.dto.SubmitMessageResult;
import org.springframework.stereotype.Component;

/**
 * Factory for outbound SMPP response PDUs. Keeps response construction
 * centralized in the protocol layer.
 */
@Component
public class SmppResponseFactory {

    public SubmitSmResp submitNotAcceptedTemporaryFailure(SubmitSm request) {
        SubmitSmResp response = request.createResponse();
        response.setCommandStatus(SmppConstants.STATUS_SYSERR);
        return response;
    }

    public SubmitSmResp submitResponse(SubmitSm request, SubmitMessageResult outcome) {
        SubmitSmResp response = request.createResponse();
        switch (outcome) {
            case SubmitMessageResult.Accepted ok -> {
                response.setCommandStatus(SmppConstants.STATUS_OK);
                response.setMessageId(ok.messageId());
            }
            case SubmitMessageResult.ValidationRejected vr ->
                response.setCommandStatus(SmppSubmitViolationMapper.toCommandStatus(vr.violation()));
            case SubmitMessageResult.Throttled ignored ->
                response.setCommandStatus(SmppConstants.STATUS_THROTTLED);
            case SubmitMessageResult.StorageFailed ignored ->
                response.setCommandStatus(SmppConstants.STATUS_SYSERR);
        }
        return response;
    }

    public EnquireLinkResp enquireLinkOk(EnquireLink request) {
        EnquireLinkResp response = request.createResponse();
        response.setCommandStatus(SmppConstants.STATUS_OK);
        return response;
    }

    public UnbindResp unbindOk(Unbind request) {
        UnbindResp response = request.createResponse();
        response.setCommandStatus(SmppConstants.STATUS_OK);
        return response;
    }
}
