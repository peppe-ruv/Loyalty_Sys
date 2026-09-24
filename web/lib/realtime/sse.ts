// Client SSE per il flusso eventi live (docs/07 §3, ADR-020). L'EventSource va DIRETTO a insight
// (NEXT_PUBLIC_LH_INSIGHT_URL): le funzioni serverless del proxy non reggono connessioni lunghe.

export type LiveFamily = "ACTION" | "EFFECT" | "FACT" | "AUDIT" | "DLQ";

export interface LiveEvent {
  eventId: string;
  topic: string;
  family: LiveFamily;
  shortType: string;
  memberId?: string | null;
  correlationId?: string | null;
  time?: string | null;
  summary: string;
}

export type ConnectionState = "live" | "reduced" | "disconnected";

export interface LiveFilters {
  topics?: string[];
  types?: string[];
  memberId?: string;
  correlationId?: string;
}

/** Base URL di insight per l'SSE diretto; default locale per lo sviluppo con ./mvnw. */
export function insightBaseUrl(): string {
  const fromEnv = process.env.NEXT_PUBLIC_LH_INSIGHT_URL;
  return (fromEnv && fromEnv.replace(/\/$/, "")) || "http://localhost:8088";
}

/** URL dello stream con i filtri applicati. */
export function streamUrl(filters: LiveFilters): string {
  const params = new URLSearchParams();
  if (filters.topics?.length) params.set("topics", filters.topics.join(","));
  if (filters.types?.length) params.set("types", filters.types.join(","));
  if (filters.memberId) params.set("memberId", filters.memberId);
  if (filters.correlationId) params.set("correlationId", filters.correlationId);
  const qs = params.toString();
  return `${insightBaseUrl()}/v1/stream/events${qs ? `?${qs}` : ""}`;
}

/** Colore CSS del topic in base alla famiglia (token in globals.css). */
export function familyColorVar(family: LiveFamily): string {
  // Nomi scritti per intero: Tailwind v4 emette solo le variabili di @theme che trova nel sorgente, e un nome
  // composto a runtime (`--color-topic-${…}`) non lo vede (i pallini di fatti/effetti/audit restavano trasparenti).
  const color: Record<LiveFamily, string> = {
    ACTION: "var(--color-topic-actions)",
    EFFECT: "var(--color-topic-effects)",
    FACT: "var(--color-topic-facts)",
    AUDIT: "var(--color-topic-audit)",
    DLQ: "var(--color-topic-dlq)",
  };
  return color[family];
}
