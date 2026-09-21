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
  const key: Record<LiveFamily, string> = {
    ACTION: "actions",
    EFFECT: "effects",
    FACT: "facts",
    AUDIT: "audit",
    DLQ: "dlq",
  };
  return `var(--color-topic-${key[family]})`;
}
