import type { LiveFamily } from "@/lib/realtime/sse";

// Forma del tracciato di insight (docs/servizi/insight-service.md §3, `GET /v1/traces*`): nessun campo nuovo lato
// servizio, le etichette leggibili si ricavano nel frontend (issue #204).

export interface TraceNode {
  eventId: string;
  family: LiveFamily;
  shortType: string;
  service: string;
  time?: string | null;
  offsetMs: number;
  parentEventId?: string | null;
  summary: string;
}

export interface TraceOutcome {
  points: { currency: string; amount: number }[];
  tierChange?: { from?: string | null; to?: string | null } | null;
  messages: number;
  coupons: number;
  plays: number;
  dlq: number;
}

export interface Trace {
  correlationId: string;
  memberId?: string | null;
  startedAt?: string | null;
  durationMs: number;
  status: string;
  nodes: TraceNode[];
  outcome: TraceOutcome;
}

/** Riga dell'elenco (`GET /v1/traces`). */
export interface TraceSummary {
  correlationId: string;
  memberId: string | null;
  rootShortType: string;
  startedAt: string | null;
  durationMs: number;
  status: string;
  outcomeSummary: string;
}

/** Riga di spiegabilità per campagna (`campaign GET /v1/evaluations/{actionId}`, docs/03 §3.5). */
export interface CampaignResult {
  campaignCode: string;
  campaignName?: string | null;
  matched: boolean;
  reason?: string | null;
  failedConditions?: unknown[] | null;
  effects?: { type: string; currency?: string | null; amount?: number | null }[] | null;
}

/** Dettaglio di un evento (`insight GET /v1/events/{eventId}`). */
export interface EventDetail {
  eventId: string;
  topic?: string;
  family?: string;
  type?: string;
  shortType?: string;
  source?: string;
  payload?: unknown;
  [key: string]: unknown;
}
