import { infraFromHealth } from "@/lib/api/status";

// HUB-02 — stato della vetrina enterprise (docs/18 §5, ADR-049, F2-DIST-09). SOLO LATO SERVER: la pagina lo calcola
// durante il rendering, senza un nuovo endpoint (docs/18 M8.14: nessun nuovo endpoint pubblico). Tessere per ruolo
// dell'immagine (`hub`, `web`, `idp`, `cms`) e per infrastruttura (Postgres, Kafka).
// - `hub`: `GET <hub>/actuator/health` (libero anche in enterprise, OidcActorFilter); Postgres e Kafka dai suoi
//   componenti `db` e `kafka`, come per HUB-01 (docs/07 §8). Hub irraggiungibile ⇒ infrastruttura non verificabile.
// - `web`: è il processo che sta rispondendo, quindi attivo.
// - `idp`: documento di discovery OIDC dell'emittente configurato (`LH_OIDC_ISSUER`), lo stesso che usa il login.
// - `cms`: il ruolo `cms` (Directus, M10.2) non esiste ancora e la vetrina non lo installa: sempre «non installato».
// Nessun valore di configurazione (URL, emittente) finisce nella pagina o nei log.

export type TileState = "UP" | "DOWN" | "UNKNOWN" | "NOT_INSTALLED";
export type TileKey = "hub" | "web" | "idp" | "cms" | "db" | "kafka";

export interface StatusTile {
  key: TileKey;
  group: "role" | "infra";
  state: TileState;
  latencyMs: number | null;
}

export interface EnterpriseStatus {
  tiles: StatusTile[];
  checkedAt: string;
}

/** `ok`: tutto ciò che è installato è attivo; `degraded`: qualcosa non risponde; `error`: hub irraggiungibile. */
export type StatusView = "ok" | "degraded" | "error";

export interface ProbeOptions {
  /** URL base dell'hub (lo stesso di `LH_SVC_INGESTION_URL`: nel compose di riferimento tutti puntano all'hub). */
  hubUrl: string;
  /** Emittente OIDC; `null` se la configurazione è assente o rifiutata (allora `idp` non risponde). */
  issuer: string | null;
  fetchImpl?: typeof fetch;
  timeoutMs?: number;
  now?: () => number;
}

export const PROBE_TIMEOUT_MS = 2500;

async function timed(
  fetchImpl: typeof fetch,
  url: string,
  timeoutMs: number,
  now: () => number,
): Promise<{ res: Response; latencyMs: number } | null> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const start = now();
  try {
    const res = await fetchImpl(url, { signal: controller.signal, cache: "no-store", redirect: "error" });
    return { res, latencyMs: Math.max(0, now() - start) };
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

async function probeHub(opts: Required<Omit<ProbeOptions, "issuer">>): Promise<StatusTile[]> {
  const out = await timed(opts.fetchImpl, `${opts.hubUrl.replace(/\/+$/, "")}/actuator/health`, opts.timeoutMs, opts.now);
  if (out === null) {
    return [
      { key: "hub", group: "role", state: "DOWN", latencyMs: null },
      { key: "db", group: "infra", state: "UNKNOWN", latencyMs: null },
      { key: "kafka", group: "infra", state: "UNKNOWN", latencyMs: null },
    ];
  }
  // Anche con 503 il corpo porta i componenti `db` e `kafka` (stessa regola di HUB-01, `infraFromHealth`).
  const body: unknown = await out.res.json().catch(() => null);
  const infra = infraFromHealth(body);
  return [
    { key: "hub", group: "role", state: out.res.ok ? "UP" : "DOWN", latencyMs: out.res.ok ? out.latencyMs : null },
    { key: "db", group: "infra", state: infra.db === "UP" ? "UP" : "DOWN", latencyMs: null },
    { key: "kafka", group: "infra", state: infra.kafka === "UP" ? "UP" : "DOWN", latencyMs: null },
  ];
}

async function probeIdp(
  issuer: string | null,
  opts: Required<Omit<ProbeOptions, "issuer" | "hubUrl">>,
): Promise<StatusTile> {
  const down: StatusTile = { key: "idp", group: "role", state: "DOWN", latencyMs: null };
  if (issuer === null) return down;
  const out = await timed(
    opts.fetchImpl,
    `${issuer.replace(/\/+$/, "")}/.well-known/openid-configuration`,
    opts.timeoutMs,
    opts.now,
  );
  if (out === null || !out.res.ok) return down;
  const doc: unknown = await out.res.json().catch(() => null);
  const valid = doc !== null && typeof doc === "object" && typeof (doc as { issuer?: unknown }).issuer === "string";
  return valid ? { key: "idp", group: "role", state: "UP", latencyMs: out.latencyMs } : down;
}

/** Legge lo stato con le sonde in parallelo. Non lancia mai: ogni guasto diventa uno stato di tessera. */
export async function readEnterpriseStatus(options: ProbeOptions): Promise<EnterpriseStatus> {
  const opts = {
    hubUrl: options.hubUrl,
    fetchImpl: options.fetchImpl ?? fetch,
    timeoutMs: options.timeoutMs ?? PROBE_TIMEOUT_MS,
    now: options.now ?? Date.now,
  };
  const [hubTiles, idp] = await Promise.all([probeHub(opts), probeIdp(options.issuer, opts)]);
  const byKey = new Map<TileKey, StatusTile>([...hubTiles, idp].map((t) => [t.key, t]));
  const tiles: StatusTile[] = [
    byKey.get("hub")!,
    { key: "web", group: "role", state: "UP", latencyMs: null },
    byKey.get("idp")!,
    { key: "cms", group: "role", state: "NOT_INSTALLED", latencyMs: null },
    byKey.get("db")!,
    byKey.get("kafka")!,
  ];
  return { tiles, checkedAt: new Date(opts.now()).toISOString() };
}

export function tileState(status: EnterpriseStatus, key: TileKey): TileState | undefined {
  return status.tiles.find((t) => t.key === key)?.state;
}

export function statusView(status: EnterpriseStatus): StatusView {
  if (tileState(status, "hub") === "DOWN" && tileState(status, "db") === "UNKNOWN") return "error";
  return status.tiles.some((t) => t.state !== "UP" && t.state !== "NOT_INSTALLED") ? "degraded" : "ok";
}

/** Tessere installate che non sono attive (per il riquadro degraded). */
export function notUp(status: EnterpriseStatus): TileKey[] {
  return status.tiles.filter((t) => t.state !== "UP" && t.state !== "NOT_INSTALLED").map((t) => t.key);
}

const CACHE_MS = 3000;
let cache: { at: number; key: string; value: EnterpriseStatus } | null = null;

/**
 * Come `readEnterpriseStatus`, con una cache di 3 s per processo: la pagina è pubblica e ogni visita non deve
 * diventare una raffica di sonde verso hub e IdP (stesso criterio dei 2 s di `/api/demo/status`).
 */
export async function cachedEnterpriseStatus(options: ProbeOptions): Promise<EnterpriseStatus> {
  const now = (options.now ?? Date.now)();
  const key = `${options.hubUrl}\n${options.issuer ?? ""}`;
  if (cache && cache.key === key && now - cache.at < CACHE_MS) return cache.value;
  const value = await readEnterpriseStatus(options);
  cache = { at: now, key, value };
  return value;
}
