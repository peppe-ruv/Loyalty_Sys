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

export interface CampaignBudget {
  maxPoints: number | null;
  remainingPoints: number | null;
  maxMatches: number | null;
  remainingMatches: number | null;
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
  totals: { matches: number; uniqueMembers: number; pointsDecided: number; pointsGranted: number };
  budget: CampaignBudget | null;
}

// F-CMP-10 (docs/08 §BO-05/06): statistiche campagna con serie giornaliera 30 giorni.
export interface CampaignDailyStat {
  day: string;
  matches: number;
  points: number;
}

export interface CampaignStats {
  id: string;
  code: string;
  name: string;
  status: string;
  matches: number;
  uniqueMembers: number;
  pointsDecided: number;
  pointsGranted: number;
  budget: CampaignBudget | null;
  daily: CampaignDailyStat[];
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
  expiringSoon?: { amount: number; within30d: boolean; nextExpiryAt: string | null };
  tier: {
    code: string;
    name: string;
    since: string | null;
    periodSts: number;
    multiplier: number;
    progressPct: number;
    next: { code: string; threshold: number; missing: number } | null;
  };
}

// Livelli (BO-07, PT-08). `GET /v1/tiers` e `/v1/portal/tiers` restituiscono la scala.
export interface Tier {
  code: string;
  name: string;
  rank: number;
  thresholdSts: number;
  multiplier: number;
  benefits: string[];
  color: string | null;
  icon: string | null;
}

export interface TierCount {
  code: string;
  name: string;
  rank: number;
  threshold: number;
  multiplier: number;
  members: number;
}

export interface TierHistoryEntry {
  id: string;
  fromTier: string | null;
  toTier: string;
  kind: string;
  editionCode: string | null;
  at: string;
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

// Scenari demo (BO-29, docs/servizi/ingestion-service.md §3, F-DEMO-04).
export interface ScenarioStep {
  delayMs: number;
  memberId: string;
  type: string;
  data?: unknown;
  source: string;
  note?: string;
  expect?: "REJECTED" | "DUPLICATE" | "UNMATCHED";
}

export interface Scenario {
  code: string;
  name: string;
  description: string | null;
  protagonist: string | null;
  watch: string | null;
  steps: ScenarioStep[];
}

export interface ScenarioStepResult {
  index: number;
  note: string;
  memberId: string;
  type: string;
  status: "ACCEPTED" | "DUPLICATE" | "REJECTED" | "UNMATCHED";
  eventId: string | null;
  correlationId: string | null;
  rejectCode?: string;
  expected?: string;
  ok: boolean;
}

export interface ScenarioRun {
  id: string;
  scenarioCode: string;
  startedAt: string;
  finishedAt: string | null;
  status: "RUNNING" | "DONE" | "FAILED";
  stepsTotal: number;
  stepsDone: number;
  actor: string | null;
  results: ScenarioStepResult[];
}

// Valute ed edizioni (BO-08)
export interface ExpiryPolicy {
  type: "ROLLING_MONTHS" | "END_OF_EDITION_PLUS_GRACE" | "EDITION" | "NEVER";
  months?: number;
  graceDays?: number;
}

export interface Currency {
  code: string;
  name: string;
  spendable: boolean;
  expiryPolicy: ExpiryPolicy | null;
}

export interface Edition {
  code: string;
  name: string;
  startDate: string;
  endDate: string;
  redemptionGraceUntil: string | null;
  status: "PLANNED" | "ACTIVE" | "CLOSED";
}

export interface ClosePreviewMember {
  memberId: string;
  currentTier: string;
  periodSts: number;
  earnedTier: string;
  newTier: string;
  outcome: "RETAINED" | "DOWNGRADED";
}

export interface ClosePreviewSummary {
  retained: number;
  downgraded: number;
}

export interface EditionClosePreviewResult {
  summary: ClosePreviewSummary;
  members: ClosePreviewMember[];
}

// Passività (F-WAL-09, wallet GET /v1/liability)
export interface LiabilityMonth {
  month: string | null; // YYYY-MM (Europe/Rome); null = lotti senza scadenza
  amount: number;
}

export interface Liability {
  currency: string;
  outstanding: number;
  pending: number;
  byExpiryMonth: LiabilityMonth[];
  asOf: string;
}

// Premi (reward /v1/rewards*, /v1/reward-bands, /v1/reward-categories; docs/servizi/reward-service.md §3)
export type RewardType = "PHYSICAL" | "COUPON" | "DIGITAL" | "DONATION" | "EXPERIENCE";
export type RewardFulfilment = "AUTO_COUPON" | "MANUAL" | "INSTANT";

export interface Reward {
  id: string;
  code: string;
  name: string;
  description: string | null;
  terms: string | null;
  imageUrl: string | null;
  type: RewardType;
  categoryCode: string | null;
  bandCode: string;
  fulfilment: RewardFulfilment;
  couponPoolId: string | null;
  stockTotal: number | null; // null = illimitato
  stockRemaining: number | null;
  perMemberLimit: number | null;
  eligibleTiers: string[];
  eligibleSegments: string[];
  validFrom: string | null;
  validTo: string | null;
  status: string;
  version: number;
  createdBy: string | null;
  updatedAt: string;
}

export interface RewardBand {
  code: string;
  name: string;
  pointsThreshold: number;
  color: string | null;
  sortOrder: number;
}

export interface RewardCategory {
  code: string;
  name: string;
  icon: string | null;
  sortOrder: number;
}

export interface RewardStats {
  rewardsByStatus: Record<string, number>;
  redemptionsByStatus: Record<string, number>;
  topRewards: { rewardCode: string; rewardName: string; redemptions: number }[];
  lowStock: { id: string; code: string; name: string; stockRemaining: number; stockTotal: number }[];
}

// Coupon (reward /v1/coupon-pools*, /v1/coupons/*; docs/servizi/reward-service.md §3)
export type CouponStatus = "AVAILABLE" | "ISSUED" | "USED" | "EXPIRED" | "VOID";

export interface CouponPool {
  id: string;
  code: string;
  name: string;
  prefix: string;
  validityDays: number;
  counts: Record<CouponStatus, number>;
  total: number;
  rewards: { id: string; code: string; name: string; status: string }[];
}

export interface Coupon {
  code: string;
  poolId: string;
  poolCode: string | null;
  poolName: string | null;
  status: CouponStatus;
  memberId: string | null;
  rewardCode: string | null;
  origin: string | null;
  redemptionId: string | null;
  issuedAt: string | null;
  expiresAt: string | null;
  usedAt: string | null;
  voidedAt: string | null;
}

export interface CouponGenerateResult {
  generated: number;
  seed: number;
  available: number;
}

export interface CouponImportResult {
  imported: number;
  skipped: string[];
  available: number;
}
