package com.example.smpp.protocol.smpp;

import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppServerConfiguration;
import com.cloudhopper.smpp.SmppServerHandler;
import com.cloudhopper.smpp.SmppServerSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.impl.DefaultSmppServer;
import com.cloudhopper.smpp.impl.DefaultSmppSession;
import com.cloudhopper.smpp.pdu.BaseBind;
import com.cloudhopper.smpp.pdu.BaseBindResp;
import com.cloudhopper.smpp.pdu.BindTransceiver;
import com.cloudhopper.smpp.type.SmppChannelException;
import com.cloudhopper.smpp.type.SmppProcessingException;
import com.example.smpp.application.service.MessageIngressService;
import com.example.smpp.config.SmppServerProperties;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "smpp.server.enabled", havingValue = "true", matchIfMissing = true)
public class SmppServerLifecycle implements SmppServerHandler {

    private static final Logger log = LoggerFactory.getLogger(SmppServerLifecycle.class);

    private final SmppServerProperties props;
    private final SmppSessionRegistry sessionRegistry;
    private final SmppResponseFactory responseFactory;
    private final SmppPduMapper pduMapper;
    private final MessageIngressService messageIngressService;
    private final ObservationRegistry observationRegistry;

    private ThreadPoolExecutor smppIoExecutor;
    private ScheduledThreadPoolExecutor unbindCloseScheduler;
    private DefaultSmppServer smppServer;

    public SmppServerLifecycle(
            SmppServerProperties props,
            SmppSessionRegistry sessionRegistry,
            SmppResponseFactory responseFactory,
            SmppPduMapper pduMapper,
            MessageIngressService messageIngressService,
            ObservationRegistry observationRegistry) {
        this.props = props;
        this.sessionRegistry = sessionRegistry;
        this.responseFactory = responseFactory;
        this.pduMapper = pduMapper;
        this.messageIngressService = messageIngressService;
        this.observationRegistry = observationRegistry;
    }

    @PostConstruct
    void start() throws SmppChannelException {
        int maxConnections = props.getMaxConnections();
        int effectiveIoPoolSize = Math.max(maxConnections, props.getIoThreads());
        SmppServerConfiguration configuration = new SmppServerConfiguration();
        configuration.setHost(props.getHost());
        configuration.setPort(props.getPort());
        configuration.setName("smpp-observability-platform-server");
        configuration.setSystemId(props.getSystemId());
        configuration.setMaxConnectionSize(maxConnections);
        configuration.setBindTimeout(5000L);
        configuration.setJmxEnabled(false);

        int queueCapacity = Math.max(16, Math.max(maxConnections, effectiveIoPoolSize));
        ThreadFactory ioThreadFactory = daemonThreadFactory("smpp-io-");
        smppIoExecutor
                = new ThreadPoolExecutor(
                        effectiveIoPoolSize,
                        effectiveIoPoolSize,
                        60L,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(queueCapacity),
                        ioThreadFactory,
                        new ThreadPoolExecutor.CallerRunsPolicy());
        smppIoExecutor.prestartAllCoreThreads();

        unbindCloseScheduler
                = new ScheduledThreadPoolExecutor(
                        props.getCloseSchedulerThreads(),
                        daemonThreadFactory("smpp-unbind-sched-"),
                        new ThreadPoolExecutor.CallerRunsPolicy());
        unbindCloseScheduler.setRemoveOnCancelPolicy(true);

        smppServer = new DefaultSmppServer(configuration, this, smppIoExecutor, null);
        smppServer.start();
        log.info(
                "SMPP server listening on {}:{} (maxConnections={}, ioThreads={}, effectiveIoPoolSize={}, queueCap={}, closeSchedulerThreads={})",
                props.getHost(),
                props.getPort(),
                maxConnections,
                props.getIoThreads(),
                effectiveIoPoolSize,
                queueCapacity,
                props.getCloseSchedulerThreads());
    }

    @PreDestroy
    public void stopListener() {
        if (smppServer != null) {
            log.info("Stopping SMPP server");
            smppServer.stop();
            smppServer.destroy();
            smppServer = null;
        }

        shutdownExecutor(unbindCloseScheduler, "unbind close scheduler");
        unbindCloseScheduler = null;

        shutdownExecutor(smppIoExecutor, "SMPP IO workers");
        smppIoExecutor = null;
    }

    private static void shutdownExecutor(ExecutorService executor, String label) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("{} did not terminate cleanly", label);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    @Override
    public void sessionBindRequested(Long sessionId, SmppSessionConfiguration sessionConfiguration, BaseBind bindRequest)
            throws SmppProcessingException {
        if (!(bindRequest instanceof BindTransceiver)) {
            throw new SmppProcessingException(
                    SmppConstants.STATUS_INVBNDSTS, "Only bind_transceiver is supported in Phase 2");
        }
        if (!props.isAcceptAnyBind()) {
            if (!Objects.equals(bindRequest.getSystemId(), props.getExpectedSystemId())) {
                throw new SmppProcessingException(SmppConstants.STATUS_INVSYSID, "Invalid system id");
            }
            if (!Objects.equals(bindRequest.getPassword(), props.getExpectedPassword())) {
                throw new SmppProcessingException(SmppConstants.STATUS_INVPASWD, "Invalid password");
            }
        }
    }

    @Override
    public void sessionCreated(Long sessionId, SmppServerSession session, BaseBindResp preparedBindResponse)
            throws SmppProcessingException {
        DefaultSmppSession defaultSession = (DefaultSmppSession) session;
        String boundSystemId = defaultSession.getSystemId() != null ? defaultSession.getSystemId() : "";
        defaultSession.serverReady(
                new SmppSessionHandler(
                        responseFactory,
                        defaultSession,
                        unbindCloseScheduler,
                        boundSystemId,
                        pduMapper,
                        messageIngressService,
                        observationRegistry));
        sessionRegistry.sessionBound();
    }

    @Override
    public void sessionDestroyed(Long sessionId, SmppServerSession session) {
        sessionRegistry.sessionUnbound();
    }
}
