import { useCallback, useEffect, useMemo, useState } from "react";
import { fetchJson, fetchText } from "./api";
import {
  findScalar,
  latestByLabels,
  parsePrometheusText,
  type MetricSample,
} from "./prometheus";
import "./styles.css";

const REFRESH_MS = 5000;

interface MessageStats {
  received: number;
  queued: number;
  processed: number;
  failed: number;
  total: number;
  generatedAt: string;
}

interface Throughput {
  windowSeconds: number;
  since: string;
  generatedAt: string;
  processedCount: number;
  messagesPerSecond: number;
}

interface FailedMessage {
  messageId: string;
  systemId?: string;
  sourceAddr?: string;
  destinationAddr?: string;
  body?: string;
  receivedAt?: string;
  queuedAt?: string;
  processedAt?: string;
  attemptCount?: number;
  lastError?: string;
  updatedAt?: string;
}

interface HealthJson {
  status?: string;
}

function fmtTime(iso: string | undefined): string {
  if (!iso) return "-";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

function fmtNum(n: number | null | undefined): string {
  if (n === null || n === undefined) return "-";
  if (typeof n === "number" && Number.isNaN(n)) return "NaN";
  return String(n);
}

function latencyRow(
  samples: MetricSample[],
  path: "sync" | "async"
): { count: number | null; sumSec: number | null; maxSec: number | null } {
  return {
    count: findScalar(samples, "smpp_processing_latency_seconds_count", {
      path,
    }),
    sumSec: findScalar(samples, "smpp_processing_latency_seconds_sum", { path }),
    maxSec: findScalar(samples, "smpp_processing_latency_seconds_max", { path }),
  };
}

export default function App() {
  const [lastRefresh, setLastRefresh] = useState<Date | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  const [health, setHealth] = useState<{
    loading: boolean;
    data: HealthJson | null;
    error: string | null;
  }>({ loading: true, data: null, error: null });

  const [stats, setStats] = useState<{
    loading: boolean;
    data: MessageStats | null;
    error: string | null;
  }>({ loading: true, data: null, error: null });

  const [throughput, setThroughput] = useState<{
    loading: boolean;
    data: Throughput | null;
    error: string | null;
  }>({ loading: true, data: null, error: null });

  const [failed, setFailed] = useState<{
    loading: boolean;
    data: FailedMessage[] | null;
    error: string | null;
  }>({ loading: true, data: null, error: null });

  const [prom, setProm] = useState<{
    loading: boolean;
    samples: MetricSample[];
    error: string | null;
  }>({ loading: true, samples: [], error: null });

  const refresh = useCallback(async () => {
    setRefreshing(true);
    setLastRefresh(new Date());

    const load = async <T,>(
      fn: () => Promise<T>,
      ok: (data: T) => void,
      fail: (msg: string) => void
    ) => {
      try {
        ok(await fn());
      } catch (e) {
        fail(e instanceof Error ? e.message : String(e));
      }
    };

    await Promise.all([
      load(
        () => fetchJson<HealthJson>("/actuator/health"),
        (d) => setHealth({ loading: false, data: d, error: null }),
        (msg) => setHealth((s) => ({ ...s, loading: false, error: msg }))
      ),
      load(
        () => fetchJson<MessageStats>("/admin/messages/stats"),
        (d) => setStats({ loading: false, data: d, error: null }),
        (msg) => setStats((s) => ({ ...s, loading: false, error: msg }))
      ),
      load(
        () => fetchJson<Throughput>("/admin/throughput"),
        (d) => setThroughput({ loading: false, data: d, error: null }),
        (msg) => setThroughput((s) => ({ ...s, loading: false, error: msg }))
      ),
      load(
        () => fetchJson<FailedMessage[]>("/admin/messages/failed?limit=10"),
        (d) => setFailed({ loading: false, data: d, error: null }),
        (msg) => setFailed((s) => ({ ...s, loading: false, error: msg }))
      ),
      load(
        async () => {
          const text = await fetchText("/actuator/prometheus");
          return parsePrometheusText(text);
        },
        (samples) => setProm({ loading: false, samples, error: null }),
        (msg) => setProm((s) => ({ ...s, loading: false, error: msg }))
      ),
    ]);

    setRefreshing(false);
  }, []);

  useEffect(() => {
    void refresh();
    const id = window.setInterval(() => void refresh(), REFRESH_MS);
    return () => window.clearInterval(id);
  }, [refresh]);

  const samples = prom.samples;
  const receivedRows = useMemo(
    () => latestByLabels(samples, "smpp_messages_received_total"),
    [samples]
  );
  const processedRows = useMemo(
    () => latestByLabels(samples, "smpp_messages_processed_total"),
    [samples]
  );
  const throttledRows = useMemo(
    () => latestByLabels(samples, "smpp_messages_throttled_total"),
    [samples]
  );

  const syncLat = useMemo(() => latencyRow(samples, "sync"), [samples]);
  const asyncLat = useMemo(() => latencyRow(samples, "async"), [samples]);

  const sessions = useMemo(
    () => findScalar(samples, "smpp_sessions_active"),
    [samples]
  );

  const aggReceived = useMemo(() => {
    let t = 0;
    for (const r of receivedRows) t += r.value;
    return t;
  }, [receivedRows]);

  const aggProcessed = useMemo(() => {
    let t = 0;
    for (const r of processedRows) t += r.value;
    return t;
  }, [processedRows]);

  const aggThrottled = useMemo(() => {
    let t = 0;
    for (const r of throttledRows) t += r.value;
    return t;
  }, [throttledRows]);

  return (
    <div className="app">
      <header className="topbar">
        <div>
          <h1 className="title">SMPP observability</h1>
          <p className="subtitle">
            Live data from <code>/admin</code> and <code>/actuator</code>.
          </p>
        </div>
        <div className="topbar-actions">
          <span className="meta">
            Last refresh:{" "}
            <strong>
              {lastRefresh ? lastRefresh.toLocaleString() : "-"}
            </strong>
            <span className="meta-sep">|</span>
            Auto every {REFRESH_MS / 1000}s
          </span>
          <button
            type="button"
            className="btn"
            onClick={() => void refresh()}
            disabled={refreshing}
          >
            {refreshing ? "Refreshing..." : "Refresh now"}
          </button>
        </div>
      </header>

      <main className="grid">
        <section className="panel">
          <h2>Application health</h2>
          {health.loading && <p className="loading">Loading...</p>}
          {health.error && (
            <p className="err" role="alert">
              {health.error}
            </p>
          )}
          {!health.loading && !health.error && health.data && (
            <p className="status-line">
              Status:{" "}
              <span
                className={
                  health.data.status === "UP" ? "badge ok" : "badge warn"
                }
              >
                {health.data.status ?? "unknown"}
              </span>
            </p>
          )}
        </section>

        <section className="panel">
          <h2>Message stats</h2>
          {stats.loading && <p className="loading">Loading...</p>}
          {stats.error && (
            <p className="err" role="alert">
              {stats.error}
            </p>
          )}
          {!stats.loading && !stats.error && stats.data && (
            <>
              <div className="stat-cards">
                <div className="stat-card">
                  <span className="stat-label">RECEIVED</span>
                  <span className="stat-val">{stats.data.received}</span>
                </div>
                <div className="stat-card">
                  <span className="stat-label">QUEUED</span>
                  <span className="stat-val">{stats.data.queued}</span>
                </div>
                <div className="stat-card">
                  <span className="stat-label">PROCESSED</span>
                  <span className="stat-val">{stats.data.processed}</span>
                </div>
                <div className="stat-card">
                  <span className="stat-label">FAILED</span>
                  <span className="stat-val warn">{stats.data.failed}</span>
                </div>
                <div className="stat-card">
                  <span className="stat-label">total</span>
                  <span className="stat-val">{stats.data.total}</span>
                </div>
              </div>
              <p className="meta">
                generatedAt: {fmtTime(stats.data.generatedAt)}
              </p>
            </>
          )}
        </section>

        <section className="panel">
          <h2>Throughput</h2>
          {throughput.loading && <p className="loading">Loading...</p>}
          {throughput.error && (
            <p className="err" role="alert">
              {throughput.error}
            </p>
          )}
          {!throughput.loading && !throughput.error && throughput.data && (
            <dl className="kv">
              <dt>processedCount</dt>
              <dd>{throughput.data.processedCount}</dd>
              <dt>messagesPerSecond</dt>
              <dd>{throughput.data.messagesPerSecond.toFixed(4)}</dd>
              <dt>windowSeconds</dt>
              <dd>{throughput.data.windowSeconds}</dd>
              <dt>since</dt>
              <dd>{fmtTime(throughput.data.since)}</dd>
              <dt>generatedAt</dt>
              <dd>{fmtTime(throughput.data.generatedAt)}</dd>
            </dl>
          )}
        </section>

        <section className="panel span-2">
          <h2>Recent failed messages</h2>
          {failed.loading && <p className="loading">Loading...</p>}
          {failed.error && (
            <p className="err" role="alert">
              {failed.error}
            </p>
          )}
          {!failed.loading && !failed.error && failed.data && (
            <>
              {failed.data.length === 0 ? (
                <p className="empty">No failed messages</p>
              ) : (
                <div className="table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>message id</th>
                        <th>source</th>
                        <th>destination</th>
                        <th>error</th>
                        <th>timestamps</th>
                      </tr>
                    </thead>
                    <tbody>
                      {failed.data.map((m) => (
                        <tr key={m.messageId}>
                          <td className="mono">{m.messageId}</td>
                          <td className="mono">{m.sourceAddr ?? "-"}</td>
                          <td className="mono">{m.destinationAddr ?? "-"}</td>
                          <td className="err-cell">
                            {m.lastError ?? "-"}
                          </td>
                          <td className="small">
                            <div>recv: {fmtTime(m.receivedAt)}</div>
                            <div>queue: {fmtTime(m.queuedAt)}</div>
                            <div>proc: {fmtTime(m.processedAt)}</div>
                            <div>upd: {fmtTime(m.updatedAt)}</div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          )}
        </section>

        <section className="panel span-2 prom-panel">
          <h2>Prometheus scrape (/actuator/prometheus)</h2>
          {prom.loading && <p className="loading">Loading...</p>}
          {prom.error && (
            <p className="err" role="alert">
              {prom.error}
            </p>
          )}
          {!prom.loading && !prom.error && (
            <div className="prom-grid">
              <div>
                <h3>Submit received (by system_id)</h3>
                <p className="meta">
                  smpp_messages_received_total - series sum:{" "}
                  {fmtNum(aggReceived)}
                </p>
                {receivedRows.length === 0 ? (
                  <p className="empty">No samples</p>
                ) : (
                  <table className="data-table compact">
                    <thead>
                      <tr>
                        <th>system_id</th>
                        <th>value</th>
                      </tr>
                    </thead>
                    <tbody>
                      {receivedRows.map((r) => (
                        <tr
                          key={`${r.labels.system_id ?? ""}-${r.value}`}
                        >
                          <td>{r.labels.system_id ?? "-"}</td>
                          <td className="mono">{r.value}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>

              <div>
                <h3>Processed (by status)</h3>
                <p className="meta">
                  smpp_messages_processed_total - series sum:{" "}
                  {fmtNum(aggProcessed)}
                </p>
                {processedRows.length === 0 ? (
                  <p className="empty">No samples</p>
                ) : (
                  <table className="data-table compact">
                    <thead>
                      <tr>
                        <th>status</th>
                        <th>value</th>
                      </tr>
                    </thead>
                    <tbody>
                      {processedRows.map((r) => (
                        <tr key={`${r.labels.status ?? ""}-${r.value}`}>
                          <td>{r.labels.status ?? "-"}</td>
                          <td className="mono">{r.value}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>

              <div>
                <h3>Throttled (by system_id)</h3>
                <p className="meta">
                  smpp_messages_throttled_total - series sum:{" "}
                  {fmtNum(aggThrottled)}
                </p>
                {throttledRows.length === 0 ? (
                  <p className="empty">No samples</p>
                ) : (
                  <table className="data-table compact">
                    <thead>
                      <tr>
                        <th>system_id</th>
                        <th>value</th>
                      </tr>
                    </thead>
                    <tbody>
                      {throttledRows.map((r) => (
                        <tr
                          key={`${r.labels.system_id ?? ""}-${r.value}`}
                        >
                          <td>{r.labels.system_id ?? "-"}</td>
                          <td className="mono">{r.value}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>

              <div>
                <h3>Sync latency (smpp_processing_latency_seconds)</h3>
                <table className="data-table compact">
                  <tbody>
                    <tr>
                      <th>count</th>
                      <td className="mono">{fmtNum(syncLat.count)}</td>
                    </tr>
                    <tr>
                      <th>sum (s)</th>
                      <td className="mono">{fmtNum(syncLat.sumSec)}</td>
                    </tr>
                    <tr>
                      <th>max (s)</th>
                      <td className="mono">{fmtNum(syncLat.maxSec)}</td>
                    </tr>
                  </tbody>
                </table>
              </div>

              <div>
                <h3>Async latency</h3>
                <table className="data-table compact">
                  <tbody>
                    <tr>
                      <th>count</th>
                      <td className="mono">{fmtNum(asyncLat.count)}</td>
                    </tr>
                    <tr>
                      <th>sum (s)</th>
                      <td className="mono">{fmtNum(asyncLat.sumSec)}</td>
                    </tr>
                    <tr>
                      <th>max (s)</th>
                      <td className="mono">{fmtNum(asyncLat.maxSec)}</td>
                    </tr>
                  </tbody>
                </table>
              </div>

              <div>
                <h3>Active sessions</h3>
                <p className="metric-big mono">smpp_sessions_active</p>
                <p className="metric-value">{fmtNum(sessions)}</p>
              </div>
            </div>
          )}
        </section>
      </main>
    </div>
  );
}
