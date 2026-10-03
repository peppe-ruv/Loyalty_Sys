import type { Transport } from "@/lib/vetrina/programma-core.mjs";

// Hub finto in memoria per i test del programma di esempio (V10): GET degli elenchi, POST di creazione (409 se esiste),
// ricerca membri, import con avanzamento, audit. Nessuna rete. Solo test (importato da *.test.ts).

export interface FakeCall {
  service: string;
  method: string;
  path: string;
  query?: Record<string, string>;
  body?: unknown;
  multipart?: { file: { name: string; text: string }; fields?: Record<string, string> };
  headers?: Record<string, string>;
}

export interface FakeHubOptions {
  /** Utente → id del membro registrato (e ACTIVE). */
  members?: Record<string, string>;
  /** Nome utente dell'operatore nelle voci di audit. */
  actor?: string;
  /** Righe elaborate a ogni lettura del dettaglio dell'import (0 = tutte subito). */
  importStep?: number;
  /** Esito dell'import. */
  importStatus?: "DONE" | "FAILED";
  importCounts?: Record<string, number>;
  /** `none`: l'audit non arriva mai; `other`: voci di un altro attore. */
  audit?: "ok" | "none" | "other";
  /** Percorsi che rispondono 500 (per i test di errore). */
  fail?: string[];
  /** Percorsi la cui POST risponde 500 (la lettura funziona). */
  failPost?: string[];
  /** Fa rispondere 409 a una creazione (concorrenza). */
  conflictOn?: string[];
  /** Eseguita prima di ogni richiesta (per simulare la scadenza del token ecc.). */
  onCall?: (c: FakeCall) => void;
}

const COLLECTIONS: Record<string, [string, string]> = {
  "/v1/event-types": ["event_type", "code"],
  "/v1/sources": ["source", "code"],
  "/v1/message-templates": ["MESSAGE_TEMPLATE", "code"],
  "/v1/notification-rules": ["NOTIFICATION_RULE", "code"],
  "/v1/reward-categories": ["REWARD_CATEGORY", "code"],
  "/v1/reward-bands": ["REWARD_BAND", "code"],
  "/v1/coupon-pools": ["COUPON_POOL", "code"],
  "/v1/rewards": ["REWARD", "code"],
  "/v1/segments": ["SEGMENT", "code"],
  "/v1/badges": ["BADGE", "code"],
  "/v1/achievements": ["ACHIEVEMENT", "code"],
  "/v1/leaderboards": ["LEADERBOARD", "code"],
  "/v1/campaigns": ["CAMPAIGN", "code"],
};

export function makeHub(opts: FakeHubOptions = {}) {
  const actor = opts.actor ?? "marta.admin";
  const calls: FakeCall[] = [];
  const lists: Record<string, Record<string, unknown>[]> = Object.fromEntries(Object.keys(COLLECTIONS).map((p) => [p, []]));
  const audit: { id: string; entityType: string; entityId: string; action: string; actorName: string; actorRole: string }[] = [];
  const imports = new Map<string, { id: string; status: string; rowsTotal: number; rowsDone: number; counts: Record<string, number>; defaultSource: string; fileName: string; createdAt: string; reads: number }>();
  const idempotent = new Map<string, string>();
  /** Portafogli per i test di «Invia un'azione» (V11): id membro → punti e livello (modificabili dal test). */
  const wallets = new Map<string, { points: number; tier: string; pending?: number }>();
  /** Libro mastro per membro (V11): movimenti con `actionId`, `currency`, `amount` e `direction`. */
  const ledgers = new Map<string, { actionId: string; currency: string; amount: number; direction: "+" | "-" }[]>();
  let seq = 0;
  let attrs: unknown[] = [];
  let theme: object = { programName: "Club Aurora", updatedAt: null, version: 0 };
  const record = (entityType: string, entityId: string) => {
    if (opts.audit === "none") return;
    audit.push({ id: `A${audit.length}`, entityType, entityId, action: "CREATE", actorName: opts.audit === "other" ? "altro.operatore" : actor, actorRole: "ADMIN" });
  };
  const state = { campaignsLive: [] as string[] };

  const transport: Transport = async (service, method, path, o = {}) => {
    const call: FakeCall = { service, method, path, ...o } as FakeCall;
    calls.push(call);
    opts.onCall?.(call);
    if (method === "POST" && opts.failPost?.includes(path)) return { status: 500, body: { code: "BOOM", detail: "errore" } };
    if (opts.fail?.includes(path)) return { status: 500, body: { code: "BOOM", detail: "errore" } };
    if (method === "GET") {
      if (path === "/v1/campaigns") {
        return { status: 200, body: lists[path].map((c) => ({ code: c.code, status: state.campaignsLive.includes(String(c.code)) ? "LIVE" : "DRAFT" })) };
      }
      const l = /^\/v1\/wallets\/([^/]+)\/ledger$/.exec(path);
      if (l) return { status: 200, body: ledgers.get(l[1]) ?? [] };
      const w = /^\/v1\/wallets\/([^/]+)$/.exec(path);
      if (w) {
        const wallet = wallets.get(w[1]);
        if (!wallet) return { status: 404, body: { code: "NOT_FOUND" } };
        return { status: 200, body: { memberId: w[1], balances: { PTS: { active: wallet.points, pending: wallet.pending ?? 0 } }, tier: { code: wallet.tier.toUpperCase(), name: wallet.tier } } };
      }
      if (path === "/v1/attribute-definitions") return { status: 200, body: attrs };
      if (path === "/v1/theme") return { status: 200, body: theme };
      if (path in lists) return { status: 200, body: lists[path] };
      if (path === "/v1/members") {
        const q = String(o.query?.q ?? "").toLowerCase();
        const found = Object.entries(opts.members ?? {}).filter(([u]) => `${u}@example.org` === q);
        return { status: 200, body: { items: found.map(([u, id]) => ({ id, email: `${u}@example.org`, status: "ACTIVE" })), page: {} } };
      }
      if (path === "/v1/imports") return { status: 200, body: { items: [...imports.values()].map((j) => ({ ...j })), page: {} } };
      const m = /^\/v1\/imports\/(.+)$/.exec(path);
      if (m) {
        const job = imports.get(m[1]);
        if (!job) return { status: 404, body: { code: "NOT_FOUND" } };
        job.reads++;
        const step = opts.importStep ?? 0;
        job.rowsDone = step === 0 ? job.rowsTotal : Math.min(job.rowsTotal, job.reads * step);
        job.status = job.rowsDone >= job.rowsTotal ? (opts.importStatus ?? "DONE") : "RUNNING";
        if (job.status === "DONE" && Object.keys(job.counts).length === 0) job.counts = opts.importCounts ?? { accepted: job.rowsTotal, duplicate: 0, rejected: 0, unmatched: 0, invalid: 0 };
        return { status: 200, body: { job: { ...job } } };
      }
      if (path === "/v1/audit") {
        const t = String(o.query?.entityType ?? "");
        return { status: 200, body: { items: audit.filter((a) => a.entityType.toLowerCase() === t.toLowerCase()), page: { totalPages: 1 } } };
      }
      return { status: 200, body: [] };
    }
    if (method === "POST" && path === "/v1/imports") {
      const key = o.headers?.["Idempotency-Key"] ?? `k${seq}`;
      const existing = idempotent.get(key);
      if (existing) return { status: 202, body: { ...imports.get(existing) } };
      const id = `01J${String(++seq).padStart(23, "0")}`;
      const rows = (o.multipart?.file.text ?? "").split("\n").filter(Boolean).length;
      imports.set(id, { id, status: "QUEUED", rowsTotal: rows, rowsDone: 0, counts: {}, defaultSource: o.multipart?.fields?.source ?? "", fileName: o.multipart?.file.name ?? "", createdAt: new Date().toISOString(), reads: 0 });
      idempotent.set(key, id);
      record("import", id);
      return { status: 202, body: { id, status: "QUEUED" } };
    }
    if (method === "POST" && path in COLLECTIONS) {
      const [entityType, keyField] = COLLECTIONS[path];
      const body = o.body as Record<string, unknown>;
      if (opts.conflictOn?.includes(String(body[keyField])) || lists[path].some((x) => x[keyField] === body[keyField])) {
        return { status: 409, body: { code: "CODE_TAKEN" } };
      }
      lists[path].push({ ...body, id: `ID-${seq++}` });
      record(entityType, String(body[keyField]));
      return { status: 201, body: { ...body, id: `ID-${seq}` } };
    }
    if (method === "PUT") {
      if (path === "/v1/attribute-definitions") attrs = o.body as unknown[];
      if (path === "/v1/theme") theme = { ...(o.body as object), updatedAt: new Date().toISOString() };
      record(path === "/v1/theme" ? "THEME" : "attribute_definition", path === "/v1/theme" ? "default" : "all");
      return { status: 200, body: {} };
    }
    return { status: 404, body: { code: "NOT_FOUND" } };
  };
  return { transport, calls, lists, audit, imports, state, wallets, ledgers, writes: () => calls.filter((c) => c.method !== "GET") };
}

/**
 * Adattatore `fetch` → hub finto: l'URL `http://<servizio>.test/...` diventa una richiesta del trasporto. Registra le
 * intestazioni `Authorization` viste (per verificare il token fresco a ogni chiamata) e può fermare la risposta a un cancello.
 */
export function fetchFromHub(hub: ReturnType<typeof makeHub>, hooks: { gate?: () => Promise<void> } = {}) {
  const authorizations: string[] = [];
  const fetchImpl = async (url: URL | string, init: RequestInit = {}): Promise<Response> => {
    const u = new URL(String(url));
    const service = u.hostname.replace(/\.test$/, "");
    const headers = new Headers(init.headers as HeadersInit);
    authorizations.push(headers.get("authorization") ?? "");
    await hooks.gate?.();
    const o: Record<string, unknown> = { query: Object.fromEntries(u.searchParams) };
    if (init.body instanceof FormData) {
      const file = init.body.get("file") as File;
      o.multipart = {
        file: { name: file.name, type: file.type, text: await file.text() },
        fields: Object.fromEntries([...init.body.entries()].filter(([k]) => k !== "file")),
      };
    } else if (typeof init.body === "string") {
      o.body = JSON.parse(init.body);
    }
    const idem = headers.get("idempotency-key");
    if (idem) o.headers = { "Idempotency-Key": idem };
    const res = await hub.transport(service, String(init.method ?? "GET"), u.pathname, o);
    return new Response(JSON.stringify(res.body), { status: res.status, headers: { "content-type": "application/json" } });
  };
  return { fetchImpl, authorizations };
}
