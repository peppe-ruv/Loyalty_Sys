import type { Trace, TraceSummary } from "@/lib/observe/trace";

// Fixture di BO-25 (issue #204): il giro `purchase.completed` di MBR-000001 visto in vetrina, 16 nodi in 6,4 s.
// Il badge assegnato rientra in ingestion come nuova azione e innesca una seconda valutazione (parentEventId).

const n = (
  eventId: string,
  family: Trace["nodes"][number]["family"],
  shortType: string,
  service: string,
  offsetMs: number,
  parentEventId: string | null,
  summary: string,
) => ({ eventId, family, shortType, service, offsetMs, parentEventId, summary, time: null });

export const PURCHASE_TRACE: Trace = {
  correlationId: "c0ffee00-0000-4000-8000-000000000001",
  memberId: "MBR-000001",
  startedAt: "2026-10-02T19:38:00Z",
  durationMs: 6426,
  status: "COMPLETE",
  nodes: [
    n("e01", "ACTION", "purchase.completed", "ingestion", 0, null, "Acquisto · 24.9 €"),
    n("e02", "FACT", "campaign.evaluated", "campaign", 812, "e01", "Campagna valutata · 3 campagne"),
    n("e03", "EFFECT", "points.grant", "campaign", 820, "e01", "Effetto punti · +100 PTS"),
    n("e04", "EFFECT", "points.grant", "campaign", 821, "e01", "Effetto punti · +24 STS"),
    n("e05", "FACT", "wallet.points.earned", "wallet", 1604, "e03", "Punti accreditati · +100 PTS"),
    n("e06", "FACT", "wallet.points.earned", "wallet", 1702, "e04", "Punti accreditati · +24 STS"),
    n("e07", "FACT", "achievement.progressed", "hub", 2101, "e01", "achievement.progressed"),
    n("e08", "FACT", "achievement.progressed", "hub", 2130, "e01", "achievement.progressed"),
    n("e09", "FACT", "achievement.progressed", "hub", 2160, "e01", "achievement.progressed"),
    n("e10", "FACT", "achievement.completed", "hub", 2290, "e07", "achievement.completed"),
    n("e11", "FACT", "badge.awarded", "hub", 2402, "e10", "badge.awarded"),
    n("e12", "FACT", "member.segment.entered", "hub", 2510, "e01", "member.segment.entered"),
    n("e13", "ACTION", "badge.awarded", "ingestion", 3810, "e11", "badge.awarded"),
    n("e14", "FACT", "campaign.evaluated", "campaign", 3920, "e13", "Campagna valutata · 1 campagne"),
    n("e15", "EFFECT", "message.send", "hub", 4950, "e11", "message.send"),
    n("e16", "FACT", "message.delivered", "hub", 6426, "e15", "message.delivered"),
  ],
  outcome: { points: [{ currency: "PTS", amount: 100 }, { currency: "STS", amount: 24 }], tierChange: null, messages: 1, coupons: 0, plays: 0, dlq: 0 },
};

/** Un reso che il wallet non riesce a stornare: voce DLQ figlia dell'effetto punti. */
export const FAILED_TRACE: Trace = {
  correlationId: "c0ffee00-0000-4000-8000-000000000002",
  memberId: "MBR-000005",
  startedAt: "2026-10-02T19:15:00Z",
  durationMs: 1210,
  status: "FAILED",
  nodes: [
    n("f01", "ACTION", "purchase.returned", "ingestion", 0, null, "purchase.returned"),
    n("f02", "FACT", "campaign.evaluated", "campaign", 600, "f01", "Campagna valutata · 1 campagne"),
    n("f03", "EFFECT", "points.grant", "campaign", 610, "f01", "Effetto punti · -180 PTS"),
    n("dlq-77", "DLQ", "points.grant", "wallet", 1210, "f03", "DLQ · INSUFFICIENT_BALANCE · NEW"),
  ],
  outcome: { points: [], tierChange: null, messages: 0, coupons: 0, plays: 0, dlq: 0 },
};

/** Come FAILED_TRACE, ma la voce DLQ è stata riprocessata: il tracciato è completo. */
export const REPROCESSED_TRACE: Trace = {
  ...FAILED_TRACE,
  correlationId: "c0ffee00-0000-4000-8000-000000000005",
  status: "COMPLETE",
  nodes: FAILED_TRACE.nodes.map((x) => (x.family === "DLQ" ? { ...x, summary: "DLQ · INSUFFICIENT_BALANCE · REPROCESSED" } : x)),
};

/** Catena a due livelli di azioni derivate: ogni campaign.evaluated appartiene a un'azione diversa. */
export const CHAIN_TRACE: Trace = {
  correlationId: "c0ffee00-0000-4000-8000-000000000006",
  memberId: "MBR-000001",
  startedAt: "2026-10-02T19:00:00Z",
  durationMs: 5000,
  status: "COMPLETE",
  nodes: [
    n("r01", "ACTION", "purchase.completed", "ingestion", 0, null, "Acquisto · 10 €"),
    n("r02", "FACT", "badge.awarded", "hub", 500, "r01", "badge.awarded"),
    n("r03", "ACTION", "badge.awarded", "ingestion", 1000, "r02", "badge.awarded"),
    n("r04", "FACT", "campaign.evaluated", "campaign", 1500, "r03", "Campagna valutata · 1 campagne"),
    n("r05", "FACT", "badge.awarded", "hub", 2000, "r03", "badge.awarded"),
    n("r06", "ACTION", "badge.awarded", "ingestion", 3000, "r05", "badge.awarded"),
    n("r07", "FACT", "campaign.evaluated", "campaign", 4000, "r06", "Campagna valutata · 1 campagne"),
  ],
  outcome: { points: [], tierChange: null, messages: 0, coupons: 0, plays: 0, dlq: 0 },
};

export const TRACE_LIST: TraceSummary[] = [
  { correlationId: PURCHASE_TRACE.correlationId, memberId: "MBR-000001", rootShortType: "purchase.completed", startedAt: "2026-10-02T19:38:00Z", durationMs: 6426, status: "COMPLETE", outcomeSummary: "+100 PTS · +24 STS" },
  { correlationId: "c0ffee00-0000-4000-8000-000000000003", memberId: "MBR-000002", rootShortType: "member.segment.entered", startedAt: "2026-10-02T19:30:00Z", durationMs: 40, status: "COMPLETE", outcomeSummary: "" },
  { correlationId: "c0ffee00-0000-4000-8000-000000000004", memberId: "MBR-000004", rootShortType: "purchase.completed", startedAt: "2026-10-02T19:20:00Z", durationMs: 900, status: "IN_PROGRESS", outcomeSummary: "" },
  { correlationId: FAILED_TRACE.correlationId, memberId: "MBR-000005", rootShortType: "purchase.returned", startedAt: "2026-10-02T19:15:00Z", durationMs: 1210, status: "FAILED", outcomeSummary: "1 DLQ" },
];
