// HUB-01 — le nove risorse da aspettare prima di «Apri la vetrina» (Q-728, ADR-051 decisione 9, F2-DIST-09).
// Modulo SENZA dipendenze server: lo importano sia la route (lib/hub/codespace.ts, lib/hub/vetrinaProbe.ts) sia il
// riquadro client. Un controllo vale `ok` o `pending`; la risposta della route porta solo questo, mai un corpo grezzo.

/** Nell'ordine in cui compaiono nell'elenco del riquadro. */
export const CHECK_KEYS = [
  "codespace",
  "ports",
  "web",
  "hub",
  "db",
  "kafka",
  "idpOperators",
  "idpMembers",
  "testMembers",
] as const;

export type CheckKey = (typeof CHECK_KEYS)[number];
export type CheckState = "ok" | "pending";
export type Checks = Record<CheckKey, CheckState>;

/**
 * Livello di dipendenza: una risorsa «in corso» è una non pronta i cui livelli precedenti sono tutti pronti; le altre
 * sono «in attesa». Rispecchia il mockup approvato (codespace, porte, web/servizi/Postgres/Kafka, accesso, membri).
 */
export const CHECK_LEVEL: Record<CheckKey, number> = {
  codespace: 0,
  ports: 1,
  web: 2,
  hub: 2,
  db: 2,
  kafka: 2,
  idpOperators: 3,
  idpMembers: 3,
  testMembers: 4,
};

export const allPending = (): Checks => Object.fromEntries(CHECK_KEYS.map((k) => [k, "pending"])) as Checks;

/** Lettura rigorosa del campo `checks` della route: tutte e nove le chiavi con `ok` o `pending`, altrimenti `null`. */
export function parseChecks(raw: unknown): Checks | null {
  if (raw === null || typeof raw !== "object") return null;
  const o = raw as Record<string, unknown>;
  const out = {} as Checks;
  for (const k of CHECK_KEYS) {
    const v = o[k];
    if (v !== "ok" && v !== "pending") return null;
    out[k] = v;
  }
  return out;
}

export const readyCount = (c: Checks): number => CHECK_KEYS.filter((k) => c[k] === "ok").length;
export const allReady = (c: Checks): boolean => readyCount(c) === CHECK_KEYS.length;
export const pendingKeys = (c: Checks): CheckKey[] => CHECK_KEYS.filter((k) => c[k] !== "ok");

/** `wait` se tutti i livelli sotto sono pronti (la risorsa si sta controllando adesso), `todo` altrimenti. */
export function itemPhase(c: Checks, key: CheckKey): "ok" | "wait" | "todo" {
  if (c[key] === "ok") return "ok";
  const blocked = CHECK_KEYS.some((k) => CHECK_LEVEL[k] < CHECK_LEVEL[key] && c[k] !== "ok");
  return blocked ? "todo" : "wait";
}
