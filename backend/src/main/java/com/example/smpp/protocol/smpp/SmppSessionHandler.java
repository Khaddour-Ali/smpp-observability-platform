package com.example.smpp.protocol.smpp;

import com.cloudhopper.smpp.impl.DefaultSmppSession;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.EnquireLink;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.Unbind;
import com.example.smpp.application.dto.SubmitMessageCommand;
import com.example.smpp.application.service.MessageIngressService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SmppSessionHandler extends DefaultSmppSessionHandler {

    private static final Logger log = LoggerFactory.getLogger(SmppSessionHandler.class);

    private final SmppResponseFactory responseFactory;
    private final DefaultSmppSession session;
    private final ScheduledExecutorService unbindCloseScheduler;
    private final String boundSystemId;
    private final SmppPduMapper pduMapper;
    private final MessageIngressService messageIngressService;
    private final ObservationRegistry observationRegistry;

    public SmppSessionHandler(
            SmppResponseFactory responseFactory,
            DefaultSmppSession session,
            ScheduledExecutorService unbindCloseScheduler,
            String boundSystemId,
            SmppPduMapper pduMapper,
            MessageIngressService messageIngressService,
            ObservationRegistry observationRegistry) {
        this.responseFactory = responseFactory;
        this.session = session;
        this.unbindCloseScheduler = unbindCloseScheduler;
        this.boundSystemId = boundSystemId == null ? "" : boundSystemId;
        this.pduMapper = pduMapper;
        this.messageIngressService = messageIngressService;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public PduResponse firePduRequestReceived(PduRequest pduRequest) {
        if (pduRequest instanceof EnquireLink) {
            return responseFactory.enquireLinkOk((EnquireLink) pduRequest);
        }
        if (pduRequest instanceof Unbind unbind) {
            scheduleGracefulCloseAfterUnbindResp();
            return responseFactory.unbindOk(unbind);
        }
        if (pduRequest instanceof SubmitSm submitSm) {
            return handleSubmitSm(submitSm);
        }
        return super.firePduRequestReceived(pduRequest);
    }

    private PduResponse handleSubmitSm(SubmitSm submitSm) {
        try {
            return Observation.createNotStarted("submit_sm", observationRegistry)
                    .contextualName("submit_sm")
                    .lowCardinalityKeyValue("smpp.command", "submit_sm")
                    .lowCardinalityKeyValue("smpp.system_id", maskBoundSystemId(boundSystemId))
                    .observe(
                            () -> {
                                SubmitMessageCommand command
                                = Observation.createNotStarted("pdu.receive", observationRegistry)
                                        .contextualName("pdu.receive")
                                        .lowCardinalityKeyValue("smpp.command", "submit_sm")
                                        .lowCardinalityKeyValue(
                                                "smpp.system_id", maskBoundSystemId(boundSystemId))
                                        .observe(
                                                ()
                                                -> pduMapper.toSubmitCommand(
                                                        submitSm, boundSystemId));
                                return responseFactory.submitResponse(
                                        submitSm, messageIngressService.submit(command));
                            });
        } catch (Exception ex) {
            log.error("submit_sm handling failed boundSystemId={}", maskBoundSystemId(boundSystemId), ex);
            return responseFactory.submitNotAcceptedTemporaryFailure(submitSm);
        }
    }

    private void scheduleGracefulCloseAfterUnbindResp() {
        unbindCloseScheduler.schedule(
                () -> {
                    try {
                        session.close(2000);
                    } catch (Exception ignored) {
                        // session may already be closed
                    }
                },
                25L,
                TimeUnit.MILLISECONDS);
    }

    static String maskBoundSystemId(String systemId) {
        if (systemId == null || systemId.isBlank()) {
            return "unknown";
        }
        String s = systemId.strip();
        return s.length() > 64 ? s.substring(0, 64) : s;
    }
}
