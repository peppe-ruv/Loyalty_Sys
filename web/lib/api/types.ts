// Tipi delle risposte dei servizi usati dal backoffice (docs/08). Solo i campi consumati dalle viste M1.

export interface MemberView {
  id: string;
  externalId: string | null;
  firstName: string | null;
  lastName: string | null;
  nickname: string | null;
  email: string | null;
  status: string;
  tier: string;
  balancePts: number;
  periodSts: number;
  version: number;
  registeredAt: string | null;
}

export interface CampaignSummary {
  id: string;
  code: string;
  name: string;
  status: string;
  priority: number;
  triggerActionTypes: string[];
  visibleInPortal: boolean;
  system: boolean;
  totals: { matches: number; pointsDecided: number; pointsGranted: number };
}

export interface Campaign {
  id: string;
  code: string;
  name: string;
  description: string | null;
  memberDescription: string | null;
  status: string;
  priority: number;
  triggerActionTypes: string[];
  audience: unknown;
  conditions: unknown;
  effects: unknown[];
  limits: unknown;
  schedule: unknown;
  visibleInPortal: boolean;
  system: boolean;
}

export interface EventType {
  code: string;
  name: string;
  origin: string;
  category: string | null;
  dataSchema: string | null;
  enabled: boolean;
  icon: string | null;
}

export interface InboundEventRow {
  eventId: string;
  status: string;
  memberId: string | null;
  correlationId: string;
  rejectCode?: string | null;
  detail?: string | null;
}

export interface EvaluationRow {
  actionId: string;
  memberId: string | null;
  actionType: string;
  actionTime: string;
  evaluatedAt: string;
  outcome: string;
  resultsJson: string;
}

export interface LedgerEntry {
  id: string;
  currency: string;
  type: string;
  amount: number;
  direction: string;
  balanceAfter: number;
  occurredAt: string;
  campaignCode: string | null;
  description: string | null;
}

export interface WalletView {
  memberId: string;
  balances: Record<string, { active: number; pending: number; lifetimeEarned: number; lifetimeSpent: number }>;
  tier: {
    code: string;
    name: string;
    periodSts: number;
    multiplier: number;
    progressPct: number;
    next: { code: string; threshold: number; missing: number } | null;
  };
}

export interface Evaluation {
  outcome: string;
  results: {
    campaignCode: string;
    campaignName: string;
    matched: boolean;
    reason: string | null;
    effects: { type: string; currency: string | null; amount: number | null }[];
  }[];
  effects: { effectId: string; campaignCode: string; currency: string; amount: number }[];
}
