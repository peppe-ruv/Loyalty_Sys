// Tipi delle API KPI di insight per BO-01 (docs/servizi/insight-service.md §3).

export interface KpiDelta {
  abs: number;
  pct: number | null;
}

export interface KpiDeltas {
  membersActive: KpiDelta;
  actions: KpiDelta;
  pointsEarned: KpiDelta;
  pointsSpent: KpiDelta;
  redemptions: KpiDelta;
  plays: KpiDelta;
}

export interface KpiOverview {
  from: string;
  to: string;
  membersTotal: number;
  membersActive: number;
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
