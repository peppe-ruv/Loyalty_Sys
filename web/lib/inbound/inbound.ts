// Monitor ingressi (BO-26, docs/08 §BO-26; ingestion /v1/inbound-events*, docs/servizi/ingestion-service.md §3, §5).
// Logica pura della pagina: tipi, regole di Riprova / Abbina (F-ING-04, F-ING-09, M7.4), errori di schema campo per
// campo, esito di un'azione e suggerimento di ricerca del membro dal subject.

export type InboundStatus = "ACCEPTED" | "DUPLICATE" | "REJECTED" | "UNMATCHED";
export type InboundResolution = "RETRY" | "MANUAL_MATCH" | "AUTO_MATCH";

export interface InboundRow {
  id: string;
  eventId: string;
  sourceCode: string;
  typeCode: string;
  subject: string;
  memberId: string | null;
  receivedAt: string | null;
  status: InboundStatus | string;
  rejectCode: string | null;
  rejectDetail: string | null;
  correlationId: string;
  origin?: string | null;
  resolution?: InboundResolution | string | null;
  resolvedBy?: string | null;
  resolvedAt?: string | null;
}

/** Dettaglio (`GET /v1/inbound-events/{id}`, risposta di `/retry` e `/match`): la riga + il CloudEvent salvato. */
export interface InboundDetail extends InboundRow {
  eventTime: string | null;
  payload: unknown;
}

export const OUTCOMES: { key: InboundStatus | ""; label: string }[] = [
  { key: "", label: "Tutti" },
  { key: "ACCEPTED", label: "Accettati" },
  { key: "DUPLICATE", label: "Duplicati" },
  { key: "REJECTED", label: "Respinti" },
  { key: "UNMATCHED", label: "Non abbinati" },
];

export function outcomeOf(value: string | null): InboundStatus | "" {
  return OUTCOMES.some((o) => o.key === value) ? (value as InboundStatus | "") : "";
}

export type InboundActionBlock = "READ_ONLY_ROLE" | "NOT_RETRYABLE" | "NOT_UNMATCHED" | null;

/** Riprova: capacità `inbound.handle` (ADMIN, CARE) e solo esiti REJECTED / UNMATCHED (gli altri → 409). */
export function retryBlock(row: Pick<InboundRow, "status">, canHandle: boolean): InboundActionBlock {
  if (row.status !== "REJECTED" && row.status !== "UNMATCHED") return "NOT_RETRYABLE";
  if (!canHandle) return "READ_ONLY_ROLE";
  return null;
}

/** Abbina a un membro: capacità `inbound.handle` e solo esito UNMATCHED. */
export function matchBlock(row: Pick<InboundRow, "status">, canHandle: boolean): InboundActionBlock {
  if (row.status !== "UNMATCHED") return "NOT_UNMATCHED";
  if (!canHandle) return "READ_ONLY_ROLE";
  return null;
}

export const RESOLUTION_LABEL: Record<InboundResolution, string> = {
  RETRY: "Riprovato",
  MANUAL_MATCH: "Abbinato a mano",
  AUTO_MATCH: "Abbinato automaticamente alla registrazione del membro",
};

export function resolutionLabel(resolution: string | null | undefined): string | null {
  if (!resolution) return null;
  return RESOLUTION_LABEL[resolution as InboundResolution] ?? resolution;
}

export interface SchemaError {
  /** Campo in forma `data.<path>`, o `data` se l'errore è sull'oggetto intero. */
  field: string;
  message: string;
}

/**
 * Errori di schema campo per campo (docs/08 §BO-26) dal dettaglio di `REJECTED/INVALID_DATA` (docs §5 punto 4:
 * "dettaglio = errori dello schema", uniti da `; `). Forma dei messaggi: `$.amount: … ` oppure
 * `$: required property 'amount' not found`. Una riga non riconosciuta resta intera, sul campo `data`.
 */
export function schemaErrors(rejectCode: string | null | undefined, detail: string | null | undefined): SchemaError[] {
  if (rejectCode !== "INVALID_DATA" || !detail) return [];
  return detail
    .split("; ")
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const m = /^\$((?:\.|\[)[^:]*)?:\s*(.*)$/.exec(line);
      if (!m) return { field: "data", message: line };
      const path = (m[1] ?? "").replace(/^\./, "");
      const message = m[2];
      const required = /required property '([^']+)'/.exec(message);
      if (required) {
        return { field: path ? `data.${path}.${required[1]}` : `data.${required[1]}`, message };
      }
      return { field: path ? `data.${path}` : "data", message };
    });
}

export type OutcomeTone = "success" | "warning" | "error";

/** Esito leggibile di Riprova / Abbina, dalla riga restituita dal servizio. */
export function resolutionOutcome(row: Pick<InboundRow, "status" | "rejectCode" | "memberId">): { tone: OutcomeTone; text: string } {
  switch (row.status) {
    case "ACCEPTED":
      return {
        tone: "success",
        text: `Accettato: l'azione è stata pubblicata${row.memberId ? ` per ${row.memberId}` : ""}. Segui il tracciato.`,
      };
    case "UNMATCHED":
      return { tone: "warning", text: "Ancora non abbinato: nessun membro corrisponde al soggetto. Abbinalo a mano." };
    case "DUPLICATE":
      return { tone: "warning", text: "Duplicato: un evento con la stessa fonte e lo stesso id era già stato accettato. Nulla è stato ripubblicato." };
    default:
      return { tone: "error", text: `Ancora respinto${row.rejectCode ? ` (${row.rejectCode})` : ""}: correggi la causa e riprova.` };
  }
}

/** Testo da cercare tra i membri partendo dal subject (`email:x` → `x`, `external:X` → `X`, `member:MBR-…` → id). */
export function memberSearchHint(subject: string | null | undefined): string {
  if (!subject) return "";
  const i = subject.indexOf(":");
  const value = i >= 0 ? subject.slice(i + 1) : subject;
  return value.trim();
}

export const BLOCK_HINT: Record<Exclude<InboundActionBlock, null>, string> = {
  READ_ONLY_ROLE: "Riprova e Abbina richiedono il ruolo ADMIN o CARE.",
  NOT_RETRYABLE: "Si riprovano solo gli eventi respinti o non abbinati.",
  NOT_UNMATCHED: "Si abbinano solo gli eventi non abbinati.",
};

/** Messaggio per gli errori delle azioni (RFC 9457 `code`), in italiano. */
export function actionErrorMessage(err: { code: string; detail: string; asleep: boolean }): string {
  if (err.asleep) return "Il servizio non risponde: riprova quando la demo è accesa.";
  switch (err.code) {
    case "INBOUND_NOT_RETRYABLE":
    case "INBOUND_NOT_UNMATCHED":
      return `${err.detail || "L'evento è già stato risolto."} Ricarico il dettaglio.`;
    case "MEMBER_NOT_FOUND":
      return err.detail || "Membro non trovato.";
    case "MEMBER_NOT_ACTIVE":
      return err.detail || "Il membro non è attivo.";
    case "FORBIDDEN_ROLE":
      return BLOCK_HINT.READ_ONLY_ROLE;
    default:
      return err.detail || err.code;
  }
}

/** Il JSON del CloudEvent, indentato. */
export function prettyEvent(payload: unknown): string {
  if (payload == null) return "—";
  try {
    return JSON.stringify(payload, null, 2);
  } catch {
    return String(payload);
  }
}
