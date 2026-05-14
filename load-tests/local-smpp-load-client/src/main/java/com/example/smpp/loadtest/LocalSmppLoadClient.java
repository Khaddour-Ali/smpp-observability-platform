package com.example.smpp.loadtest;

import com.cloudhopper.commons.util.windowing.WindowFuture;
import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.type.Address;
import com.cloudhopper.smpp.type.SmppInvalidArgumentException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal Cloudhopper-based SMPP transceiver client that issues {@code submit_sm} at a target
 * aggregate TPS with per-bind windowing. Intended for <strong>local supplemental</strong> load
 * validation only - not Melrose and not a substitute for assignment tooling.
 */
public final class LocalSmppLoadClient {

    /** SMPP interface version 3.4 */
    private static final byte INTERFACE_VERSION_3_4 = 0x34;

    /** TON international (GSM 03.40) */
    private static final byte TON_INTERNATIONAL = 0x01;

    /** NPI ISDN / E.164 */
    private static final byte NPI_E164 = 0x01;

    private LocalSmppLoadClient() {}

    public static void main(String[] args) {
        int code;
        try {
            Config cfg = Config.parse(args);
            code = run(cfg);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            printUsage();
            code = 2;
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            code = 1;
        }
        if (code != 0) {
            System.exit(code);
        }
    }

    private static void printUsage() {
        System.err.println(
                """
                Local SMPP load client (supplemental; not Melrose).

                mvn -q exec:java -Dexec.args="--host HOST --port PORT --system-id ID --password PW \\
                  --messages N --tps T --binds B --submit-window W \\
                  --source SRC --destination DST --message BODY \\
                  [--connect-timeout-ms MS] [--request-expiry-ms MS] [--window-monitor-interval-ms MS]
                """
                        .strip());
    }

    private static int run(Config cfg) throws Exception {
        AtomicLong completed = new AtomicLong();
        int[] perBind = splitCounts(cfg.messages, cfg.binds);

        ExecutorService ioPool =
                Executors.newCachedThreadPool(
                        r -> {
                            Thread t = new Thread(r, "smpp-load-netty");
                            t.setDaemon(true);
                            return t;
                        });
        int nettyWorkers =
                Math.max(
                        32,
                        Math.max(cfg.binds + 8, Runtime.getRuntime().availableProcessors() * 2));
        DefaultSmppClient client = new DefaultSmppClient(ioPool, nettyWorkers);
        GlobalPacer pacer = new GlobalPacer(cfg.tps);

        List<SmppSession> sessions = new ArrayList<>();
        ExecutorService workers =
                Executors.newFixedThreadPool(
                        cfg.binds, r -> new Thread(r, "smpp-load-submit"));
        try {
            for (int i = 0; i < cfg.binds; i++) {
                SmppSessionConfiguration c = baseConfig(cfg, "bind-" + i);
                SmppSession s = client.bind(c, new DefaultSmppSessionHandler());
                sessions.add(s);
            }

            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < cfg.binds; i++) {
                final int idx = i;
                final int count = perBind[i];
                futures.add(
                        workers.submit(
                                () -> {
                                    if (count == 0) {
                                        return null;
                                    }
                                    runBind(sessions.get(idx), count, cfg, pacer, completed);
                                    return null;
                                }));
            }

            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable c = e.getCause();
                    if (c instanceof Exception ex) {
                        throw ex;
                    }
                    if (c instanceof Error err) {
                        throw err;
                    }
                    throw e;
                }
            }

            if (completed.get() != cfg.messages) {
                System.err.printf(
                        "Expected %d submit_sm_resp successes, got %d%n", cfg.messages, completed.get());
                return 1;
            }
            return 0;
        } finally {
            workers.shutdown();
            workers.awaitTermination(10, TimeUnit.MINUTES);
            for (SmppSession s : sessions) {
                try {
                    if (s != null && s.isBound()) {
                        s.unbind(cfg.requestExpiryMs);
                    }
                } catch (Exception e) {
                    System.err.println("unbind warning: " + e.getMessage());
                }
            }
            client.destroy();
            ioPool.shutdown();
            ioPool.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    /** Split {@code total} into {@code parts} non-negative counts (larger shares first). */
    private static int[] splitCounts(int total, int parts) {
        int[] out = new int[parts];
        if (parts <= 0) {
            return out;
        }
        int base = total / parts;
        int rem = total % parts;
        for (int i = 0; i < parts; i++) {
            out[i] = base + (i < rem ? 1 : 0);
        }
        return out;
    }

    private static SmppSessionConfiguration baseConfig(Config cfg, String name) {
        SmppSessionConfiguration c = new SmppSessionConfiguration();
        c.setName(name);
        c.setHost(cfg.host);
        c.setPort(cfg.port);
        c.setConnectTimeout(cfg.connectTimeoutMs);
        c.setBindTimeout(cfg.connectTimeoutMs);
        c.setSystemId(cfg.systemId);
        c.setPassword(cfg.password);
        c.setType(SmppBindType.TRANSCEIVER);
        c.setInterfaceVersion(INTERFACE_VERSION_3_4);
        c.setWindowSize(cfg.submitWindow);
        c.setWindowWaitTimeout(cfg.requestExpiryMs);
        c.setRequestExpiryTimeout(cfg.requestExpiryMs);
        c.setWindowMonitorInterval(cfg.windowMonitorIntervalMs);
        return c;
    }

    @SuppressWarnings("unchecked")
    private static void runBind(
            SmppSession session,
            int messageCount,
            Config cfg,
            GlobalPacer pacer,
            AtomicLong completed)
            throws Exception {

        Deque<WindowFuture<Integer, PduRequest, PduResponse>> inflight = new ArrayDeque<>();

        for (int n = 0; n < messageCount; n++) {
            while (inflight.size() >= cfg.submitWindow) {
                awaitFuture(inflight.removeFirst(), cfg);
                completed.incrementAndGet();
            }
            pacer.acquire();
            SubmitSm pdu = buildSubmit(cfg);
            WindowFuture<Integer, PduRequest, PduResponse> wf =
                    session.sendRequestPdu(pdu, cfg.requestExpiryMs, false);
            inflight.addLast(wf);
        }

        while (!inflight.isEmpty()) {
            awaitFuture(inflight.removeFirst(), cfg);
            completed.incrementAndGet();
        }
    }

    private static void awaitFuture(WindowFuture<Integer, PduRequest, PduResponse> f, Config cfg)
            throws Exception {
        if (!f.await(cfg.requestExpiryMs)) {
            f.cancel();
            throw new IllegalStateException("submit_sm_resp timed out (await)");
        }
        if (!f.isSuccess()) {
            Throwable c = f.getCause();
            throw new IllegalStateException(
                    "submit failed: " + (c != null ? c.getMessage() : "unknown"), c);
        }
        PduResponse resp = f.getResponse();
        if (!(resp instanceof SubmitSmResp smr)) {
            throw new IllegalStateException("Unexpected response type: " + resp.getClass().getName());
        }
        if (smr.getCommandStatus() != SmppConstants.STATUS_OK) {
            throw new IllegalStateException(
                    "submit_sm_resp not OK: status=0x"
                            + Integer.toHexString(smr.getCommandStatus()));
        }
    }

    private static SubmitSm buildSubmit(Config cfg) throws SmppInvalidArgumentException {
        SubmitSm sm = new SubmitSm();
        sm.setSourceAddress(new Address(TON_INTERNATIONAL, NPI_E164, cfg.source));
        sm.setDestAddress(new Address(TON_INTERNATIONAL, NPI_E164, cfg.destination));
        sm.setShortMessage(cfg.messageBody.getBytes(StandardCharsets.UTF_8));
        sm.setDataCoding((byte) 0);
        sm.setEsmClass((byte) 0);
        sm.setRegisteredDelivery((byte) 0);
        sm.setProtocolId((byte) 0);
        sm.setPriority((byte) 0);
        return sm;
    }

    /** Serialize global submit rate to ~{@code tps} across all binds (best-effort). */
    private static final class GlobalPacer {
        private final long spacingNanos;
        private long nextPermittedNanos;
        private final Object lock = new Object();

        GlobalPacer(int tps) {
            int t = Math.max(1, tps);
            this.spacingNanos = 1_000_000_000L / t;
            this.nextPermittedNanos = System.nanoTime();
        }

        void acquire() throws InterruptedException {
            synchronized (lock) {
                long now = System.nanoTime();
                long wait = nextPermittedNanos - now;
                if (wait > 0) {
                    long ms = wait / 1_000_000L;
                    int ns = (int) (wait % 1_000_000L);
                    if (ms > 0) {
                        Thread.sleep(ms);
                    }
                    if (ns > 0) {
                        Thread.sleep(0, ns);
                    }
                }
                nextPermittedNanos += spacingNanos;
                long n = System.nanoTime();
                if (nextPermittedNanos < n - spacingNanos) {
                    nextPermittedNanos = n;
                }
            }
        }
    }

    private record Config(
            String host,
            int port,
            String systemId,
            String password,
            int messages,
            int tps,
            int binds,
            int submitWindow,
            String source,
            String destination,
            String messageBody,
            long connectTimeoutMs,
            long requestExpiryMs,
            long windowMonitorIntervalMs) {

        static Config parse(String[] args) {
            Map<String, String> m = new HashMap<>();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (!a.startsWith("--")) {
                    throw new IllegalArgumentException("Unexpected token: " + a);
                }
                String key = a.substring(2);
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("Missing value for --" + key);
                }
                m.put(key, args[++i]);
            }

            require(m, "host");
            require(m, "port");
            require(m, "system-id");
            require(m, "password");
            require(m, "messages");
            require(m, "tps");
            require(m, "binds");
            require(m, "submit-window");
            require(m, "source");
            require(m, "destination");
            require(m, "message");

            int port = Integer.parseInt(m.get("port"));
            int messages = Integer.parseInt(m.get("messages"));
            int tps = Integer.parseInt(m.get("tps"));
            int binds = Integer.parseInt(m.get("binds"));
            int submitWindow = Integer.parseInt(m.get("submit-window"));

            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("Invalid --port");
            }
            if (messages <= 0) {
                throw new IllegalArgumentException("--messages must be > 0");
            }
            if (tps <= 0) {
                throw new IllegalArgumentException("--tps must be > 0");
            }
            if (binds <= 0) {
                throw new IllegalArgumentException("--binds must be > 0");
            }
            if (submitWindow <= 0) {
                throw new IllegalArgumentException("--submit-window must be > 0");
            }

            long connectTimeoutMs = Long.parseLong(m.getOrDefault("connect-timeout-ms", "10000"));
            long requestExpiryMs = Long.parseLong(m.getOrDefault("request-expiry-ms", "30000"));
            long windowMonitorIntervalMs =
                    Long.parseLong(m.getOrDefault("window-monitor-interval-ms", "1000"));

            for (String k : m.keySet()) {
                if (!isKnownOption(k)) {
                    throw new IllegalArgumentException("Unknown option: --" + k);
                }
            }

            return new Config(
                    m.get("host"),
                    port,
                    m.get("system-id"),
                    m.get("password"),
                    messages,
                    tps,
                    binds,
                    submitWindow,
                    m.get("source"),
                    m.get("destination"),
                    m.get("message"),
                    connectTimeoutMs,
                    requestExpiryMs,
                    windowMonitorIntervalMs);
        }

        private static boolean isKnownOption(String k) {
            return k.equals("host")
                    || k.equals("port")
                    || k.equals("system-id")
                    || k.equals("password")
                    || k.equals("messages")
                    || k.equals("tps")
                    || k.equals("binds")
                    || k.equals("submit-window")
                    || k.equals("source")
                    || k.equals("destination")
                    || k.equals("message")
                    || k.equals("connect-timeout-ms")
                    || k.equals("request-expiry-ms")
                    || k.equals("window-monitor-interval-ms");
        }

        private static void require(Map<String, String> m, String k) {
            if (!m.containsKey(k)) {
                throw new IllegalArgumentException("Missing required --" + k);
            }
        }
    }
}
