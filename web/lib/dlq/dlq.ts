// DLQ (BO-27, docs/08 §BO-27; insight /v1/dlq*, docs/servizi/insight-service.md §3, §5). Logica pura della pagina:
// etichette, filtri, regole di riprocessa/scarta, spiegazioni per codice d'errore, stack abbreviato.

export type DlqStatus = "OPEN" | "REPROCESSED" | "DISCARDED";
export type DlqFamily = "ACTION" | "EFFECT" | "FACT" | "AUDIT" | "UNKNOWN";

export interface DlqEntry {
  id: string;
  eventId: string;
  originalTopic: string | null;
  originalType: string | null;
  shortType: string | null;
  family: DlqFamily | string;
  consumer: string;
  errorCode: string | null;
  errorClass: string | null;
  errorMessage: string | null;
  errorStack: string | null;
  retryable: boolean | null;
  attempts: number | null;
  memberId: string | null;
  correlationId: string | null;
  payload: unknown;
  firstSeenAt: string;
  status: DlqStatus | string;
  resolvedBy: string | null;
  resolvedAt: string | null;
  resolutionNote: string | null;
  reprocessable: boolean;
}

// SPEC-GAP: Q-B2 — docs/08 chiama "NEW" lo stato che la scheda di insight (fonte più autorevole) chiama "OPEN":
// l'API usa OPEN, la UI lo mostra come «Nuova».
export const DLQ_STATUS_LABEL: Record<DlqStatus, string> = {
  OPEN: "Nuova",
  REPROCESSED: "Riprocessata",
  DISCARDED: "Scartata",
};

export function statusLabel(status: string): string {
  return DLQ_STATUS_LABEL[status as DlqStatus] ?? status;
}

/** Schede di stato della pagina (default: le nuove, quelle da gestire). */
export const DLQ_STATUS_TABS: { key: DlqStatus | "ALL"; label: string }[] = [
  { key: "OPEN", label: "Nuove" },
  { key: "REPROCESSED", label: "Riprocessate" },
  { key: "DISCARDED", label: "Scartate" },
  { key: "ALL", label: "Tutte" },
];

export function statusTabOf(value: string | null): DlqStatus | "ALL" {
  return DLQ_STATUS_TABS.some((t) => t.key === value) ? (value as DlqStatus | "ALL") : "OPEN";
}

/** Parametri di GET /v1/dlq dai filtri della pagina (i vuoti non si inviano). */
export function dlqQuery(filters: { status: DlqStatus | "ALL"; consumer?: string; errorCode?: string }, page = 0, size = 25) {
  return {
    status: filters.status === "ALL" ? undefined : filters.status,
    consumer: filters.consumer?.trim() || undefined,
    errorCode: filters.errorCode?.trim().toUpperCase() || undefined,
    page,
    size,
  };
}

/** Consumer noti (gruppi `lh-<servizio>`, docs/05 §1) per il filtro. */
export const DLQ_CONSUMERS = [
  "lh-ingestion",
  "lh-member",
  "lh-campaign",
  "lh-wallet",
  "lh-reward",
  "lh-gamification",
  "lh-engagement",
  "lh-insight",
] as const;

/** Servizio dal gruppo consumer: `lh-campaign` → `campaign`. */
export function consumerService(consumer: string | null | undefined): string {
  if (!consumer) return "—";
  return consumer.startsWith("lh-") ? consumer.slice(3) : consumer;
}

export const FAMILY_LABEL: Record<string, string> = {
  ACTION: "Azione",
  EFFECT: "Effetto",
  FACT: "Fatto",
  AUDIT: "Audit",
  UNKNOWN: "Sconosciuto",
};

/** Codice breve della voce, da digitare per confermare lo scarto (docs/08 §3: azioni irreversibili). */
export function dlqCode(id: string): string {
  return `DLQ-${id.slice(-6).toUpperCase()}`;
}

export type DlqActionBlock = "READ_ONLY_ROLE" | "CLOSED" | "NOT_ACTION" | null;

/**
 * Riprocessa: solo ADMIN (`dlq.handle`), solo voci aperte e solo azioni (effetti e fatti → 409 NOT_REPROCESSABLE).
 * Restituisce il motivo del blocco, o `null` se l'azione è possibile.
 */
export function reprocessBlock(entry: Pick<DlqEntry, "status" | "family">, canHandle: boolean): DlqActionBlock {
  if (!canHandle) return "READ_ONLY_ROLE";
  if (entry.status !== "OPEN") return "CLOSED";
  if (entry.family !== "ACTION") return "NOT_ACTION";
  return null;
}

/** Scarta: solo ADMIN e solo voci aperte, con una nota (qualunque famiglia). */
export function discardBlock(entry: Pick<DlqEntry, "status">, canHandle: boolean): DlqActionBlock {
  if (!canHandle) return "READ_ONLY_ROLE";
  if (entry.status !== "OPEN") return "CLOSED";
  return null;
}

export const BLOCK_HINT: Record<Exclude<DlqActionBlock, null>, string> = {
  READ_ONLY_ROLE: "Riprocessa e scarta richiedono il ruolo ADMIN.",
  CLOSED: "La voce è già chiusa.",
  NOT_ACTION:
    "Solo le azioni si riprocessano da qui (ingestion le ripubblica con lo stesso id). Effetti e fatti si possono solo scartare con una nota.",
};

/** Il discard si conferma solo con una nota e digitando il codice della voce. */
export function canConfirmDiscard(note: string, typed: string, id: string): boolean {
  return note.trim().length > 0 && typed.trim().toUpperCase() === dlqCode(id);
}

export interface ErrorExplanation {
  title: string;
  body: string;
}

/** Spiegazioni per codice (docs/08 §BO-27: `LOOP_GUARD` ha una spiegazione dedicata). */
export function explainError(code: string | null | undefined): ErrorExplanation | null {
  switch (code) {
    case "LOOP_GUARD":
      return {
        title: "Catena interna troppo lunga (LOOP_GUARD)",
        body:
          "Un fatto è rientrato come azione interna più di 3 volte di fila (lhhop > 3): il ponte di ingestion l'ha fermato per evitare un ciclo " +
          "(es. un bonus che genera un badge che genera un altro bonus…). Non si riprocessa: rivedi le campagne che reagiscono alle azioni " +
          "interne coinvolte o la mappatura del ponte in «Azioni e fonti», poi scarta la voce con una nota.",
      };
    case "DEMO_POISON":
      return {
        title: "Messaggio avvelenato di prova (SCN-POISON)",
        body:
          "L'azione porta il flag demo _poison: il motore campagne la rifiuta apposta per mostrare la DLQ. Riprocessarla la fa fallire di nuovo " +
          "(si apre una nuova voce); scartala con una nota per chiudere il giro.",
      };
    case "COUPON_POOL_EMPTY":
      return {
        title: "Pool coupon vuoto",
        body: "Il premio non aveva più codici disponibili quando la campagna ha chiesto di emetterne uno. Genera nuovi codici in «Coupon».",
      };
    default:
      return null;
  }
}

/** Le prime `lines` righe dello stack (il servizio lo manda già abbreviato). */
export function shortStack(stack: string | null | undefined, lines = 8): string {
  if (!stack) return "";
  const all = stack.split("\n");
  return all.length <= lines ? stack : [...all.slice(0, lines), `\t… altre ${all.length - lines} righe`].join("\n");
}

/** Payload leggibile (JSON indentato); un valore non JSON è conservato dal servizio come `{ raw }`. */
export function prettyPayload(payload: unknown): string {
  if (payload == null) return "—";
  try {
    return JSON.stringify(payload, null, 2);
  } catch {
    return String(payload);
  }
}
