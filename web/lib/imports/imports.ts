// Import file (BO-32, docs/08 §BO-32; F2-ING-02, M8.7; ingestion /v1/imports*, docs/servizi/ingestion-service.md §3).
// Logica pura della pagina: tipi delle risposte, controlli sul file prima dell'invio (il servizio li ripete), stato del
// lavoro e avanzamento, filtri del rapporto e messaggi degli errori del servizio.

import type { LhError } from "@/lib/api/client";

export type ImportStatus = "QUEUED" | "RUNNING" | "DONE" | "FAILED";
export type ImportOutcome = "ACCEPTED" | "DUPLICATE" | "REJECTED" | "UNMATCHED" | "INVALID";

export interface OutcomeCounts {
  accepted: number;
  duplicate: number;
  rejected: number;
  unmatched: number;
  invalid: number;
}

/** Lavoro di import (`GET /v1/imports`, risposta `202` di `POST /v1/imports`). */
export interface ImportJob {
  id: string;
  kind: string;
  format: "CSV" | "NDJSON" | "JSON" | string;
  fileName: string;
  sizeBytes: number;
  sha256: string;
  defaultSource?: string | null;
  status: ImportStatus | string;
  rowsTotal: number;
  rowsDone: number;
  counts: OutcomeCounts;
  attempts: number;
  errorDetail?: string | null;
  createdBy: string;
  createdAt: string;
  startedAt?: string | null;
  finishedAt?: string | null;
}

/** Dettaglio (`GET /v1/imports/{id}`): il lavoro più i non abbinati ancora aperti nel monitor ingressi (BO-26). */
export interface ImportDetail {
  job: ImportJob;
  openUnmatched: number;
}

/** Riga del rapporto (`GET /v1/imports/{id}/rows`): solo le righe non accettate. */
export interface ImportRow {
  rowNumber: number;
  eventId?: string | null;
  outcome: ImportOutcome | string;
  rejectCode?: string | null;
  detail?: string | null;
  inboundEventId?: string | null;
  currentStatus?: string | null;
}

/** Esito di «Riprova non abbinati» (`POST /v1/imports/{id}/retry-unmatched?afterRow=`); a blocchi di righe. */
export interface RetryUnmatchedResult {
  retried: number;
  accepted: number;
  stillUnmatched: number;
  rejected: number;
  /** Riga da cui continuare; assente a fine elenco. */
  nextAfterRow?: number | null;
}

/**
 * Riprova tutti i non abbinati di un import, blocco dopo blocco (il servizio ne tratta al più qualche centinaio per
 * chiamata), sommando gli esiti. `call(afterRow)` è la chiamata al servizio.
 */
export async function retryAllUnmatched(
  call: (afterRow: number) => Promise<RetryUnmatchedResult>,
  maxCalls = 100,
): Promise<RetryUnmatchedResult> {
  const total: RetryUnmatchedResult = { retried: 0, accepted: 0, stillUnmatched: 0, rejected: 0, nextAfterRow: null };
  let after = 0;
  for (let i = 0; i < maxCalls; i++) {
    const r = await call(after);
    total.retried += r.retried;
    total.accepted += r.accepted;
    total.stillUnmatched += r.stillUnmatched;
    total.rejected += r.rejected;
    if (r.nextAfterRow == null) return total;
    after = r.nextAfterRow;
  }
  return { ...total, nextAfterRow: after };
}

/** Limiti di default del servizio (Q-371): il servizio decide comunque, qui solo per avvisare prima dell'invio. */
export const MAX_FILE_BYTES = 1_048_576;
export const MAX_ROWS = 10_000;
export const ACCEPTED_EXTENSIONS = [".csv", ".ndjson", ".jsonl", ".json"] as const;
export const ACCEPT_ATTRIBUTE = ACCEPTED_EXTENSIONS.join(",");

/** Colonne del CSV (docs/servizi/ingestion-service.md §3), per l'aiuto della pagina. */
export const CSV_COLUMNS: { name: string; note: string }[] = [
  { name: "id", note: "obbligatoria: id dell'evento presso la fonte (deduplica)" },
  { name: "type", note: "obbligatoria: tipo azione, es. purchase.completed" },
  { name: "subject", note: "obbligatoria: member:<id>, external:<codice> o email:<indirizzo>" },
  { name: "time", note: "obbligatoria: istante RFC 3339, es. 2026-09-25T10:00:00Z" },
  { name: "source", note: "URN urn:loyaltyhub:source:<codice>; facoltativa se scegli la fonte predefinita" },
  { name: "data.<campo>", note: "un campo di data, convertito col tipo dello schema (es. data.amount)" },
  { name: "data", note: "facoltativa: l'intero data come oggetto JSON" },
];

export const ROW_FILTERS: { key: ImportOutcome | ""; label: string }[] = [
  { key: "", label: "Tutte" },
  { key: "DUPLICATE", label: "Duplicate" },
  { key: "REJECTED", label: "Respinte" },
  { key: "UNMATCHED", label: "Non abbinate" },
  { key: "INVALID", label: "Non valide" },
];

export const OUTCOME_LABELS: Record<ImportOutcome, string> = {
  ACCEPTED: "Accettate",
  DUPLICATE: "Duplicate",
  REJECTED: "Respinte",
  UNMATCHED: "Non abbinate",
  INVALID: "Non valide",
};

export const STATUS_LABELS: Record<ImportStatus, string> = {
  QUEUED: "In coda",
  RUNNING: "In elaborazione",
  DONE: "Completato",
  FAILED: "Fallito",
};

export function statusLabel(status: string): string {
  return STATUS_LABELS[status as ImportStatus] ?? status;
}

/** Il lavoro non è ancora concluso: la pagina si aggiorna da sola. */
export function isActive(status: string): boolean {
  return status === "QUEUED" || status === "RUNNING";
}

/** Percentuale di righe elaborate (0–100, intera). */
export function progressPercent(job: Pick<ImportJob, "rowsDone" | "rowsTotal">): number {
  if (!job.rowsTotal) return 0;
  return Math.min(100, Math.floor((job.rowsDone / job.rowsTotal) * 100));
}

/** Filtro delle righe dall'URL: solo i valori ammessi, altrimenti tutte. */
export function rowFilterOf(value: string | null): ImportOutcome | "" {
  return ROW_FILTERS.some((f) => f.key === value) ? (value as ImportOutcome) : "";
}

/** Conteggio per esito nell'ordine del rapporto (accettate prima). */
export function countEntries(counts: OutcomeCounts): { key: ImportOutcome; label: string; value: number }[] {
  return [
    { key: "ACCEPTED", label: OUTCOME_LABELS.ACCEPTED, value: counts.accepted },
    { key: "DUPLICATE", label: OUTCOME_LABELS.DUPLICATE, value: counts.duplicate },
    { key: "REJECTED", label: OUTCOME_LABELS.REJECTED, value: counts.rejected },
    { key: "UNMATCHED", label: OUTCOME_LABELS.UNMATCHED, value: counts.unmatched },
    { key: "INVALID", label: OUTCOME_LABELS.INVALID, value: counts.invalid },
  ];
}

export function formatBytes(bytes: number): string {
  if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toLocaleString("it-IT", { maximumFractionDigits: 1 })} MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024).toLocaleString("it-IT")} KB`;
  return `${bytes} B`;
}

/** Controllo prima dell'invio: estensione ammessa, non vuoto, sotto il limite. `null` = si può caricare. */
export function checkFile(file: { name: string; size: number } | null, maxBytes = MAX_FILE_BYTES): string | null {
  if (!file) return "Scegli un file da caricare.";
  const name = file.name.toLowerCase();
  if (!ACCEPTED_EXTENSIONS.some((ext) => name.endsWith(ext))) {
    return "Formato non ammesso: carica un file .csv, .ndjson, .jsonl o .json.";
  }
  if (file.size === 0) return "Il file è vuoto.";
  if (file.size > maxBytes) {
    return `Il file supera il limite di ${formatBytes(maxBytes)} (${formatBytes(file.size)}): dividilo in più import.`;
  }
  return null;
}

/** Corpo multipart di `POST /v1/imports`: parte `file`, `kind=EVENTS`, fonte predefinita facoltativa. */
export function importFormData(file: Blob & { name?: string }, source: string): FormData {
  const form = new FormData();
  form.append("file", file, file.name ?? "import.csv");
  form.append("kind", "EVENTS");
  if (source) form.append("source", source);
  return form;
}

/** Chiave di idempotenza per un invio (Q-353): lo stesso file ricaricato per un doppio clic non crea un secondo lavoro. */
export function newIdempotencyKey(random: () => string = () => crypto.randomUUID()): string {
  return `bo32-${random()}`;
}

/** Messaggio di un errore del caricamento: il `detail` del servizio, con un'etichetta per i codici noti. */
export function uploadErrorMessage(error: LhError): string {
  if (error.asleep) return "Il servizio ingestion si sta svegliando: riprova tra qualche secondo.";
  if (error.status === 413) return `Il file supera il limite del server (${formatBytes(MAX_FILE_BYTES)}).`;
  if (error.status === 403) return "Il tuo ruolo non può caricare import (servono ADMIN o CARE).";
  return error.detail || error.code;
}

/** Collegamento alla riga del monitor ingressi (BO-26) di una riga del rapporto, se esiste. */
export function inboundHref(row: Pick<ImportRow, "inboundEventId">): string | null {
  return row.inboundEventId ? `/backoffice/observe/inbound?e=${encodeURIComponent(row.inboundEventId)}` : null;
}

/** Percorso (via proxy) del rapporto CSV scaricabile. */
export function reportHref(id: string): string {
  return `/api/lh/ingestion/v1/imports/${encodeURIComponent(id)}/report.csv`;
}

/** Sintesi di «Riprova non abbinati». */
export function retrySummary(r: RetryUnmatchedResult): string {
  if (r.retried === 0) return "Nessuna riga da riprovare: i non abbinati sono già stati risolti.";
  const parts = [`${r.accepted} accettate`];
  if (r.stillUnmatched) parts.push(`${r.stillUnmatched} ancora non abbinate`);
  if (r.rejected) parts.push(`${r.rejected} respinte`);
  return `Riprovate ${r.retried} righe: ${parts.join(", ")}.`;
}
