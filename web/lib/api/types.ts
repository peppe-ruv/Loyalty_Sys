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
  referralCode?: string | null;
  referredBy?: string | null;
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

// Richieste premio (reward GET /v1/redemptions*, /v1/portal/redemptions*; BO-13, PT-04)
export type RedemptionStatus = "PENDING" | "CONFIRMED" | "FULFILLED" | "REJECTED" | "CANCELLED";

export interface RedemptionHistoryItem {
  status: RedemptionStatus;
  note: string | null;
  actor: string | null;
  at: string;
}

export interface Redemption {
  id: string;
  memberId: string;
  rewardCode: string;
  rewardName: string;
  pointsCost: number;
  status: RedemptionStatus;
  rejectReason: string | null;
  needsAttention: boolean;
  couponCode: string | null;
  fulfilmentNote: string | null;
  shipping: Record<string, string> | null;
  correlationId: string;
  requestedAt: string;
  confirmedAt: string | null;
  closedAt: string | null;
  history: RedemptionHistoryItem[] | null;
}

// Portale premi (reward GET /v1/portal/catalog, /v1/portal/rewards/{code}, /v1/portal/coupons; PT-03/04/13)
export type StockStateCode = "AVAILABLE" | "LOW" | "SOLD_OUT";

export interface PortalReward {
  code: string;
  name: string;
  type: string;
  imageUrl: string | null;
  category: string | null;
  pointsCost: number;
  stockState: StockStateCode;
  lockedByTier?: { requiredTiers: string[] } | null;
  perMemberLimitReached: boolean;
}

export interface PortalBand {
  code: string;
  name: string;
  pointsThreshold: number;
  color: string | null;
  rewards: PortalReward[];
}

export interface PortalCatalog {
  bands: PortalBand[];
}

export interface PortalRewardDetail extends PortalReward {
  description: string | null;
  terms: string | null;
  band: string;
  stockRemaining: number | null;
  perMemberLimit: number | null;
}

export interface PortalCoupon {
  code: string;
  rewardCode: string | null;
  rewardName: string | null;
  status: "AVAILABLE" | "ISSUED" | "USED" | "EXPIRED" | "VOID";
  issuedAt: string | null;
  expiresAt: string | null;
  origin: string | null;
}

export interface RedemptionAccepted {
  redemptionId: string;
  status: RedemptionStatus;
  correlationId: string;
}

// ---------- gamification (M5): concorsi instant win (docs/servizi/gamification-service.md §3; BO-14) ----------

export type ContestMechanic = "WHEEL" | "SCRATCH" | "BOX";
export type ContestDistribution = "UNIFORM" | "BUSINESS_HOURS";
export type PrizeType = "POINTS" | "COUPON" | "PHYSICAL";

export interface ContestPrize {
  id: string;
  code: string;
  name: string;
  type: PrizeType;
  points: number | null;
  rewardCode: string | null;
  quantityTotal: number;
  quantityRemaining: number;
  imageUrl: string | null;
  wheelColor: string | null;
  sortOrder: number;
}

export interface Contest {
  id: string;
  code: string;
  name: string;
  description: string | null;
  rulesText: string | null;
  mechanic: ContestMechanic;
  startAt: string;
  endAt: string;
  freePlayDaily: boolean;
  maxPlaysPerMemberPerDay: number | null;
  maxWinsPerMember: number | null;
  distribution: ContestDistribution;
  seed: number;
  instantsGeneratedAt: string | null;
  status: string;
  version: number;
  createdBy: string | null;
  updatedAt: string | null;
  prizes: ContestPrize[];
  instants: { total: number; open: number; claimed: number; voided: number };
  plays: number;
  wins: number;
  prizesTotal: number;
  prizesRemaining: number;
}

export interface InstantRow {
  id: string;
  prizeId: string;
  prizeCode: string;
  prizeName: string;
  instantAt: string;
  status: "OPEN" | "CLAIMED" | "VOID";
  claimedBy: string | null;
  claimedAt: string | null;
  playId: string | null;
  planted: boolean;
}

export interface InstantDay {
  day: string;
  total: number;
  open: number;
  claimed: number;
  voided: number;
}

export interface InstantHistogram {
  code: string;
  distribution: ContestDistribution;
  days: InstantDay[];
}

export interface InstantsGenerated {
  instants: number;
  seed: number;
  generatedAt: string;
}

export interface ContestWinner {
  playId: string;
  memberId: string;
  nickname: string | null;
  prizeCode: string;
  prizeName: string;
  prizeType: PrizeType;
  playedAt: string;
  deliveryStatus: "NA" | "PENDING" | "DELIVERED";
  deliveryNote: string | null;
}

export interface ContestStats {
  code: string;
  plays: number;
  wins: number;
  winRate: number;
  players: number;
  winners: number;
  prizesTotal: number;
  prizesRemaining: number;
  prizes: { code: string; name: string; type: PrizeType; total: number; remaining: number; won: number }[];
  daily: { day: string; plays: number; wins: number }[];
}

// ---------- gamification — portale (PT-05, PT-06) ----------

export interface PortalContestPrize {
  code: string;
  name: string;
  type: PrizeType;
  points: number | null;
  imageUrl: string | null;
  wheelColor: string | null;
}

export interface PortalContest {
  code: string;
  name: string;
  description: string | null;
  rulesText: string | null;
  mechanic: ContestMechanic;
  startAt: string;
  endAt: string;
  playsAvailable: number;
  freePlayDaily: boolean;
  freePlayAvailable: boolean;
  credits: number;
  playsToday: number;
  dailyLimit: number | null;
  prizes: PortalContestPrize[];
}

export interface PlayResult {
  playId: string;
  outcome: "WIN" | "LOSE";
  prize: { code: string; name: string; type: PrizeType; points: number | null; rewardCode: string | null; wheelColor: string | null } | null;
  playsAvailable: number;
  correlationId: string;
}

export interface MemberPlay {
  playId: string;
  playedAt: string;
  outcome: "WIN" | "LOSE";
  kind: "FREE_DAILY" | "CREDIT";
  prizeCode: string | null;
  prizeName: string | null;
  prizeType: PrizeType | null;
  deliveryStatus: string;
}

// ---------- gamification — obiettivi e badge (BO-15, PT-09) ----------

export type AchievementMetric = "COUNT" | "SUM" | "DISTINCT_TYPES" | "STREAK";
export type AchievementPeriod = "EVER" | "MONTH" | "EDITION";

export interface Achievement {
  id: string;
  code: string;
  name: string;
  description: string | null;
  icon: string | null;
  actionTypes: string[];
  filter: unknown;
  metric: AchievementMetric;
  sumField: string | null;
  streakUnit: "DAY" | "WEEK" | null;
  target: number;
  period: AchievementPeriod;
  repeatable: boolean;
  badgeCode: string | null;
  status: "ACTIVE" | "INACTIVE";
  completions: number;
  inProgress: number;
}

export interface BadgeView {
  code: string;
  name: string;
  description: string | null;
  icon: string | null;
  color: string | null;
  holders: number;
  unlockedBy: string[];
}

export interface PortalAchievement {
  code: string;
  name: string;
  description: string | null;
  icon: string | null;
  metric: AchievementMetric;
  streakUnit: "DAY" | "WEEK" | null;
  value: number;
  target: number;
  pct: number;
  period: AchievementPeriod;
  periodKey: string;
  repeatable: boolean;
  completedAt: string | null;
  lastUnitKey: string | null;
  badge: { code: string; name: string; icon: string | null; color: string | null } | null;
}

export interface PortalBadge {
  code: string;
  name: string;
  description: string | null;
  icon: string | null;
  color: string | null;
  awardedAt: string | null;
  origin: string | null;
  unlockHint: string;
}

// ---------- gamification — classifiche (BO-16, PT-10) ----------

export type LeaderboardMetric = "PTS_EARNED" | "STS_EARNED" | "ACTION_COUNT";
export type LeaderboardPeriod = "MONTH" | "EDITION" | "ALL_TIME";

export interface Leaderboard {
  id: string;
  code: string;
  name: string;
  metric: LeaderboardMetric;
  actionTypes: string[];
  period: LeaderboardPeriod;
  topN: number;
  status: "ACTIVE" | "INACTIVE";
}

export interface LeaderboardRanking {
  code: string;
  name: string;
  metric: LeaderboardMetric;
  period: LeaderboardPeriod;
  periodKey: string;
  currentPeriodKey: string;
  periods: string[];
  topN: number;
  items: { rank: number; memberId: string; nickname: string | null; score: number; reachedAt: string }[];
}

export interface PortalLeaderboard {
  code: string;
  name: string;
  metric: LeaderboardMetric;
  period: LeaderboardPeriod;
  periodKey: string;
  topN: number;
  top: { rank: number; nickname: string; score: number; isMe: boolean }[];
  me: { rank: number; score: number } | null;
  participants: number;
}

// BO-14 aiuto demo (docs/servizi/gamification-service.md §3 "Demo"; F-IW-08): istante piantato.
export interface PlantedInstant {
  instantId: string;
  prizeCode: string;
  prizeName: string;
  instantAt: string;
}

// Referral e profilo del portale (docs/servizi/member-service.md §3; F-REF-01/02, F-MBR-06/07; BO-17, PT-08, PT-11).
export type ReferralStatus = "PENDING" | "COMPLETED";

export interface ReferralLink {
  referrerId: string;
  referrerName: string;
  refereeId: string;
  refereeName: string;
  refereeNickname: string | null;
  refereeStatus: string;
  status: ReferralStatus;
  registeredAt: string | null;
  completedAt: string | null;
}

export interface ReferralOverview {
  invited: number;
  completed: number;
  pending: number;
  rate: number;
  qualifyingActionType: string;
  topReferrers: { memberId: string; name: string; invited: number; completed: number }[];
  links: ReferralLink[];
}

export interface PortalInvitee {
  nickname: string;
  status: ReferralStatus;
  registeredAt: string | null;
  completedAt: string | null;
}

export interface PortalReferral {
  code: string;
  shareUrl: string;
  qualifyingActionType: string;
  invited: PortalInvitee[];
  completedCount: number;
}

export interface Consents {
  marketing: boolean;
  profiling: boolean;
}

export type ProfileField = "firstName" | "lastName" | "email" | "phone" | "birthDate" | "city";

export interface PortalProfile {
  memberId: string;
  firstName: string | null;
  lastName: string | null;
  nickname: string | null;
  email: string | null;
  phone: string | null;
  birthDate: string | null;
  city: string | null;
  consents: Consents;
  referralCode: string | null;
  version: number;
  completeness: { completed: boolean; missingFields: ProfileField[] };
}

/** Voce di {@code GET /v1/portal/campaigns} (PT-02, PT-11 con {@code codes}). */
export interface PortalCampaign {
  code: string;
  name: string;
  memberDescription: string | null;
  icon: string | null;
  rewardSummary: string;
  endsAt: string | null;
  memberLimit?: { max: number; period: string } | null;
}
