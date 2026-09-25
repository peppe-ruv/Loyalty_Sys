// Tipi delle API KPI di insight per BO-01 (docs/servizi/insight-service.md §3).

import type { Page } from "@/lib/api/client";

export interface KpiDelta {
  abs: number;
  pct: number | null;
}

export interface KpiDeltas {
  membersActive30d: KpiDelta;
  actions: KpiDelta;
  pointsEarned: KpiDelta;
  pointsSpent: KpiDelta;
  redemptions: KpiDelta;
  plays: KpiDelta;
}

export interface KpiOverview {
  from: string;
  to: string;
  /** Membri totali: storico sintetico + i 12 reali + i registrati (docs/10 §9). */
  membersTotal: number;
  membersActive30d: number;
  actions: number;
  pointsEarned: number;
  pointsSpent: number;
  pointsExpired: number;
  membersNew: number;
  tierChanges: number;
  redemptions: number;
  plays: number;
  wins: number;
  deltas: KpiDeltas;
}

export interface MetricPoint {
  day: string;
  value: number;
  synthetic: boolean;
}

export interface KpiTimeSeries {
  metric: string;
  granularity: string;
  points: MetricPoint[];
}

export interface MetricSlice {
  dimValue: string;
  value: number;
}

export interface KpiBreakdown {
  metric: string;
  dimension: string;
  total: number;
  slices: MetricSlice[];
}

/** Periodi ammessi per BO-01 (docs/08 §BO-01): 7 / 30 / 90 giorni. */
export const PERIODS = [7, 30, 90] as const;
export type Period = (typeof PERIODS)[number];

// Audit (BO-22, docs/servizi/insight-service.md §3). `before`/`after` sono oggetti coi soli campi cambiati.
export type AuditAction = "CREATE" | "UPDATE" | "DELETE" | "TRANSITION" | "ADJUST" | "JOB" | "RESET";

export interface AuditRecord {
  id: string;
  eventId: string;
  at: string;
  actorRole: string | null;
  actorName: string | null;
  service: string;
  entityType: string;
  entityId: string;
  action: AuditAction | string;
  summary: string;
  before: Record<string, unknown> | null;
  after: Record<string, unknown> | null;
  correlationId: string | null;
}

/** Elenco paginato dell'audit (docs/06 §2): `{ items, page }`. */
export type AuditPage = Page<AuditRecord>;
