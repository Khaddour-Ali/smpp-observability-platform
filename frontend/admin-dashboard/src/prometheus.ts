/**
 * Minimal Prometheus text exposition parser for counter/gauge/histogram lines.
 * Tolerates missing metrics and malformed lines without throwing.
 */

export interface MetricSample {
  name: string;
  labels: Record<string, string>;
  value: number;
}

function parseLabels(raw: string): Record<string, string> {
  const out: Record<string, string> = {};
  const re = /([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*"((?:[^"\\]|\\.)*)"/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(raw)) !== null) {
    out[m[1]] = m[2].replace(/\\"/g, '"');
  }
  return out;
}

function parseValue(s: string): number | null {
  const v = s.trim();
  if (v === "NaN") return NaN;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

/**
 * Parse exposition text into samples. Ignores comments, blank lines, and bad lines.
 */
export function parsePrometheusText(text: string): MetricSample[] {
  const samples: MetricSample[] = [];
  const lines = text.split(/\r?\n/);
  for (const line of lines) {
    const t = line.trim();
    if (!t || t.startsWith("#")) continue;

    const brace = t.indexOf("{");
    let namePart: string;
    let rest: string;

    if (brace === -1) {
      const sp = t.split(/\s+/);
      if (sp.length < 2) continue;
      namePart = sp[0]!;
      rest = sp.slice(1).join(" ");
    } else {
      const close = t.indexOf("}", brace);
      if (close === -1) continue;
      namePart = t.slice(0, brace);
      const after = t.slice(close + 1).trim();
      const sp = after.split(/\s+/);
      if (sp.length < 1) continue;
      const labelsRaw = t.slice(brace + 1, close);
      const valueStr = sp[0]!;
      const val = parseValue(valueStr);
      if (val === null) continue;
      samples.push({
        name: namePart.trim(),
        labels: parseLabels(labelsRaw),
        value: val,
      });
      continue;
    }

    const sp = rest.split(/\s+/);
    const valueStr = sp[0]!;
    const val = parseValue(valueStr);
    if (val === null) continue;
    samples.push({ name: namePart.trim(), labels: {}, value: val });
  }
  return samples;
}

export function latestByLabels(
  samples: MetricSample[],
  metricName: string
): MetricSample[] {
  const key = (labels: Record<string, string>) =>
    JSON.stringify(
      Object.keys(labels)
        .sort()
        .map((k) => [k, labels[k]])
    );
  const best = new Map<string, MetricSample>();
  for (const s of samples) {
    if (s.name !== metricName) continue;
    const k = key(s.labels);
    const prev = best.get(k);
    if (!prev || s.value >= prev.value) best.set(k, s);
  }
  return [...best.values()].sort((a, b) =>
    JSON.stringify(a.labels).localeCompare(JSON.stringify(b.labels))
  );
}

export function findScalar(
  samples: MetricSample[],
  metricName: string,
  labelFilter?: Record<string, string>
): number | null {
  for (const s of samples) {
    if (s.name !== metricName) continue;
    if (!labelFilter) return s.value;
    let ok = true;
    for (const [lk, lv] of Object.entries(labelFilter)) {
      if (s.labels[lk] !== lv) {
        ok = false;
        break;
      }
    }
    if (ok) return s.value;
  }
  return null;
}
