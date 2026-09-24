import { FACTS, factLabel, type FactInfo } from "@/lib/messages/facts";
import type { DeliveryStatus, Webhook, WebhookDelivery, WebhookRequest, WebhookStats } from "./types";

// Logica pura di BO-23 (docs/08 §BO-23; docs/servizi/engagement-service.md §5): etichette degli stati di consegna,
// tasso di successo, tentativi e prossimo ritento, validazione del modulo (stesse regole del servizio, che resta
// l'arbitro: in `local` accetta anche http://localhost), catalogo dei fatti raggruppato per il selettore multiplo.

export const DELIVERY_STATUSES: DeliveryStatus[] = ["PENDING", "OK", "FAILED", "GAVE_UP"];

export const DELIVERY_STATUS_LABEL: Record<DeliveryStatus, string> = {
  PENDING: "In coda",
  OK: "Consegnata",
  FAILED: "Da ritentare",
  GAVE_UP: "Abbandonata",
};

export const DELIVERY_STATUS_TONE: Record<DeliveryStatus, string> = {
  PENDING: "bg-sky-100 text-sky-800",
  OK: "bg-emerald-100 text-emerald-800",
  FAILED: "bg-amber-100 text-amber-800",
  GAVE_UP: "bg-red-100 text-red-800",
};

export const ERROR_LABEL: Record<string, string> = {
  TIMEOUT: "nessuna risposta entro 5 s",
  CONNECTION_FAILED: "connessione non riuscita",
  BLOCKED_ADDRESS: "indirizzo non ammesso",
  HTTP_ERROR: "risposta non 2xx",
};

/** Ritardi dei ritenti automatici dopo il 1°, 2° e 3° tentativo (minuti). */
export const RETRY_DELAYS_MIN = [1, 5, 15];

/** Quota di consegne riuscite tra quelle già tentate con esito (OK, da ritentare, abbandonate); null se nessuna. */
export function successRate(stats: WebhookStats | null | undefined): number | null {
  if (!stats) return null;
  const done = stats.ok + stats.failed + stats.gaveUp;
  return done === 0 ? null : stats.ok / done;
}

export function formatSuccessRate(rate: number | null): string {
  return rate == null ? "—" : `${Math.round(rate * 100)}%`;
}

export function canRetry(status: DeliveryStatus): boolean {
  return status === "FAILED" || status === "GAVE_UP";
}

/** "2 di 4"; oltre i tentativi automatici (Riprova manuale) "5 (manuale)"; 0 → "non ancora tentata". */
export function attemptLabel(d: Pick<WebhookDelivery, "attempt" | "maxAttempts">): string {
  if (d.attempt <= 0) return "non ancora tentata";
  return d.attempt <= d.maxAttempts ? `${d.attempt} di ${d.maxAttempts}` : `${d.attempt} (manuale)`;
}

/** Prossimo tentativo per una consegna in coda o da ritentare: "adesso", "tra 4 min", "tra 2 h"; "—" altrimenti. */
export function nextAttemptLabel(d: Pick<WebhookDelivery, "status" | "nextAttemptAt">, now: Date = new Date()): string {
  if ((d.status !== "PENDING" && d.status !== "FAILED") || !d.nextAttemptAt) return "—";
  const diffMin = Math.ceil((new Date(d.nextAttemptAt).getTime() - now.getTime()) / 60_000);
  if (diffMin <= 0) return "adesso";
  if (diffMin < 60) return `tra ${diffMin} min`;
  return `tra ${Math.round(diffMin / 60)} h`;
}

/** Esito sintetico: "HTTP 500", "nessuna risposta entro 5 s", "—". */
export function outcomeLabel(d: Pick<WebhookDelivery, "httpStatus" | "error" | "attempt">): string {
  if (d.attempt <= 0) return "—";
  if (d.httpStatus != null) return `HTTP ${d.httpStatus}`;
  return d.error ? ERROR_LABEL[d.error] ?? d.error : "—";
}

/** JSON indentato per il dettaglio; il testo originale se non è JSON. */
export function prettyPayload(payload: string): string {
  try {
    return JSON.stringify(JSON.parse(payload), null, 2);
  } catch {
    return payload;
  }
}

/** Comando per verificare la firma col ricevitore d'esempio (deploy/webhook-receiver). */
export function verifyCommand(signature: string): string {
  return `node deploy/webhook-receiver/verify.mjs --secret <segreto> --signature ${signature} --file corpo.json`;
}

/** "Punti guadagnati, Salita di livello e altri 2" ("e un altro" se ne resta uno). */
export function describeFactTypes(types: string[], max = 2): string {
  if (types.length === 0) return "nessuno";
  const labels = types.map(factLabel);
  const head = labels.slice(0, max).join(", ");
  const rest = types.length - max;
  return rest <= 0 ? head : rest === 1 ? `${head} e un altro` : `${head} e altri ${rest}`;
}

/** Aggiunge o toglie un tipo, mantenendo l'ordine alfabetico (quello del servizio). */
export function toggleFactType(types: string[], type: string): string[] {
  const set = new Set(types);
  if (set.has(type)) set.delete(type);
  else set.add(type);
  return [...set].sort();
}

export interface FactGroup {
  label: string;
  facts: FactInfo[];
}

const GROUPS: { label: string; prefixes: string[] }[] = [
  { label: "Membri", prefixes: ["member.", "referral."] },
  { label: "Punti", prefixes: ["wallet."] },
  { label: "Livelli", prefixes: ["tier.", "edition."] },
  { label: "Premi e coupon", prefixes: ["reward.", "coupon."] },
  { label: "Gioco", prefixes: ["contest.", "achievement.", "badge."] },
  { label: "Programma", prefixes: ["campaign.", "content."] },
];

/** Catalogo dei fatti (lo stesso di BO-19, senza message.delivered) raggruppato per area; gli altri in "Altro". */
export function groupedFacts(facts: FactInfo[] = FACTS): FactGroup[] {
  const out: FactGroup[] = GROUPS.map((g) => ({ label: g.label, facts: [] }));
  const other: FactInfo[] = [];
  for (const f of facts) {
    if (f.type === "message.delivered") continue;
    const i = GROUPS.findIndex((g) => g.prefixes.some((p) => f.type.startsWith(p)));
    if (i >= 0) out[i].facts.push(f);
    else other.push(f);
  }
  if (other.length > 0) out.push({ label: "Altro", facts: other });
  return out.filter((g) => g.facts.length > 0);
}

export interface WebhookForm {
  code: string;
  name: string;
  url: string;
  factTypes: string[];
  enabled: boolean;
}

export function emptyForm(): WebhookForm {
  return { code: "", name: "", url: "https://", factTypes: [], enabled: true };
}

export function formFrom(w: Webhook): WebhookForm {
  return { code: w.code, name: w.name, url: w.url, factTypes: [...w.factTypes], enabled: w.enabled };
}

const LOCAL_HOST = /^http:\/\/(localhost|127\.0\.0\.1|\[::1\])(:\d+)?(\/|$)/i;

/**
 * Errori di forma, campo per campo (stessi controlli minimi del servizio, che decide in base al profilo: fuori da
 * `local` rifiuta anche http://localhost e gli indirizzi privati).
 */
export function formProblems(f: WebhookForm): Record<string, string[]> {
  const out: Record<string, string[]> = {};
  const add = (field: string, msg: string) => (out[field] ??= []).push(msg);
  const code = f.code.trim();
  if (code && !/^[A-Z][A-Z0-9-]{2,39}$/.test(code)) add("code", "formato WH-… (maiuscole, cifre, trattini)");
  const name = f.name.trim();
  if (!name) add("name", "obbligatorio");
  else if (name.length > 80) add("name", "al massimo 80 caratteri");
  const url = f.url.trim();
  if (!url || url === "https://") add("url", "obbligatorio");
  else if (!(/^https:\/\/[^\s/?#]+/i.test(url) || LOCAL_HOST.test(url))) add("url", "solo https:// (http:// solo verso localhost, in locale)");
  else if (url.length > 500) add("url", "al massimo 500 caratteri");
  if (f.factTypes.length === 0) add("factTypes", "scegli almeno un tipo di fatto");
  if (f.factTypes.includes("message.delivered")) add("factTypes", "message.delivered non si consegna (eviterebbe cicli)");
  return out;
}

/** Modulo → corpo della richiesta. In creazione il codice vuoto lo genera il servizio. */
export function toRequest(f: WebhookForm, existing?: Pick<Webhook, "version"> | null): WebhookRequest {
  return {
    code: existing ? undefined : f.code.trim() ? f.code.trim().toUpperCase() : undefined,
    name: f.name.trim(),
    url: f.url.trim(),
    factTypes: [...f.factTypes].sort(),
    enabled: f.enabled,
    version: existing?.version,
  };
}

/** "1 consegna", "5 consegne". */
export function countLabel(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

/** Stati per cui il registro va aggiornato da solo (lo scheduler lavora ogni 30 s). */
export function hasOpenDeliveries(items: Pick<WebhookDelivery, "status">[]): boolean {
  return items.some((d) => d.status === "PENDING" || d.status === "FAILED");
}
