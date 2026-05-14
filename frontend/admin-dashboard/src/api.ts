/** Typed JSON fetch against same-origin paths (Vite proxies /admin, /actuator). */

export async function fetchJson<T>(path: string): Promise<T> {
  const res = await fetch(path, {
    headers: { Accept: "application/json" },
  });
  if (!res.ok) {
    const body = await res.text().catch(() => "");
    throw new Error(
      `${res.status} ${res.statusText}${body ? `: ${body.slice(0, 200)}` : ""}`,
    );
  }
  return res.json() as Promise<T>;
}

export async function fetchText(path: string): Promise<string> {
  const res = await fetch(path, {
    headers: { Accept: "text/plain,*/*;q=0.8" },
  });
  if (!res.ok) {
    throw new Error(`${res.status} ${res.statusText}`);
  }
  return res.text();
}
