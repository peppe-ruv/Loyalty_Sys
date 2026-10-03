// Tipi scritti a mano del nucleo puro (programma-core.mjs): il web è TypeScript strict e non deduce i tipi da un .mjs.
// Tenerlo allineato al nucleo: la verifica è `pnpm typecheck` e i test di web/lib/vetrina/.

export class UsageError extends Error {}
export class ApiError extends Error {
  constructor(status: number, code: string | null, detail?: string);
  status: number;
  code: string | null;
}

export const SERVICES: readonly string[];
export const PROGRAM_SERVICES: readonly string[];
export const STORY_SOURCE_CODE: string;
export const STORY_SOURCE_URN: string;
export const NOT_INCLUDED: readonly (readonly [string, string])[];
export const NOT_INCLUDED_PROGRAM: readonly (readonly [string, string])[];

export interface Scope {
  readonly campaigns: boolean;
  readonly stories: boolean;
}
export const CLI_SCOPE: Scope;
export const PROGRAM_SCOPE: Scope;

export interface Multipart {
  file: { name: string; type?: string; text: string };
  fields?: Record<string, string>;
}
export interface RequestOptions {
  query?: Record<string, string>;
  body?: unknown;
  multipart?: Multipart;
  headers?: Record<string, string>;
}
export interface TransportResponse {
  status: number;
  body: unknown;
}
export type Transport = (service: string, method: string, path: string, options?: RequestOptions) => Promise<TransportResponse>;

export function assertAllowedRequest(method: string, pathname: string, body?: unknown, scope?: Scope): void;
export function assertStoryImport(multipart: Multipart | undefined, subjects: ReadonlySet<string>): void;
export function fetchTransport(options: {
  targets: Record<string, string>;
  getToken: () => string | Promise<string>;
  fetch: typeof fetch;
  timeoutMs?: number;
}): Transport;
export function safeJson(res: { text(): Promise<string> }): Promise<unknown>;
export function asItems(body: unknown): any[];
export function describeError(e: unknown): string;
export function resolveSeedDate(value: unknown, now: number): string | null;
export function criteriaAttributeKeys(criteria: unknown, attributeDefs: unknown): string[];
export function isRealActor(record: { actorName?: unknown; actorRole?: unknown }): boolean;

export class Api {
  constructor(options: { transport: Transport; scope?: Scope });
  writes: number;
  storySubjects: Set<string>;
  request(service: string, method: string, pathname: string, options?: RequestOptions): Promise<{ status: number; body: any }>;
}

export interface StoryRow {
  type: string;
  at: string;
  data?: Record<string, unknown>;
}
export interface Story {
  username: string;
  email: string;
  name: string;
  expectedTier: string;
  expectedSts: number;
  rows: StoryRow[];
}
export interface VetrinaTestSeed {
  source: Record<string, unknown> & { code: string };
  stories: Story[];
}
export interface ProgramSeed extends Record<string, any> {
  campaigns: any[];
  vetrinaTest: VetrinaTestSeed;
}

export type PlanAction = "create" | "present" | "skip" | "error";
export interface PlanItem {
  entity: string;
  service: string;
  key: string;
  action: PlanAction;
  method?: string;
  path?: string;
  body?: unknown;
  needs?: string[];
  reason?: string;
  note?: string;
  label?: string;
  result?: "created" | "already" | "failed";
  error?: string;
  audit?: { type: string; ids: string[]; actions: string[] };
  [k: string]: unknown;
}
export interface PlanContext {
  [k: string]: unknown;
}

export function buildPlan(args: {
  seed: Record<string, any>;
  api: Api | null;
  offline?: boolean;
  now: number;
  options?: { campaigns?: boolean; vetrinaSource?: boolean };
}): Promise<{ plan: PlanItem[]; ctx: PlanContext }>;

export function applyPlan(args: {
  plan: PlanItem[];
  ctx: PlanContext;
  api: Api;
  log?: { err(s: string): void };
  onProgress?: (p: { done: number; total: number; item: PlanItem }) => void;
}): Promise<PlanItem[]>;

export interface AuditProblem {
  id: string;
  reason: string | undefined;
}
export function verifyAudit(args: {
  api: Api;
  items: PlanItem[];
  since: Date;
  expectedActor?: string | null;
  timeoutSec: number;
  intervalSec: number;
  sleep: (ms: number) => Promise<void>;
}): Promise<{ verified: number; problems: AuditProblem[] }>;
export function fetchAudit(api: Api, args: { since: Date; entityType: string }): Promise<any[]>;

export const PLAN_GROUPS: readonly { id: string; label: string; entities: readonly string[] }[];
export function resolveStoryTime(at: string, now: number): string;
export function expectedSts(rows: StoryRow[], now: number): number;
export function campaignsNeededByStories(seed: Record<string, any>): string[];
export function buildStoryFile(
  vetrinaTest: VetrinaTestSeed,
  members: ReadonlyMap<string, string>,
  now: number,
): Promise<{ name: string; type: string; text: string; rows: number; sha256: string }>;

export type StoriesState = "ready" | "waiting" | "running" | "present" | "offline" | "error";
export interface StoriesPlan {
  state: StoriesState;
  reasons: string[];
  needs: string[];
  waitingFor: string[];
  members: { username: string; name: string; rows: number; state: "unknown" | "ready" | "missing" }[];
  rows: number;
  totalRows: number;
  jobId: string | null;
  /** Utente → id del membro risolto: SOLO in memoria, mai nelle risposte. */
  resolved: Map<string, string>;
}
export function planStories(args: { seed: Record<string, any>; api: Api; plan?: PlanItem[]; offline?: boolean }): Promise<StoriesPlan>;
export function applyStories(args: {
  seed: Record<string, any>;
  api: Api;
  stories: StoriesPlan;
  now: number;
  onProgress?: (p: { done: number; total: number; status: string }) => void;
  sleep?: (ms: number) => Promise<void>;
  pollMs?: number;
  timeoutMs?: number;
}): Promise<{
  jobId: string;
  status: string;
  rows: number;
  counts: Record<string, number>;
  problems: string[];
  reused: boolean;
}>;

export interface ProgramSummary {
  actor: string;
  groups: { id: string; label: string; create: number; present: number; skipped: number; errors: number }[];
  create: number;
  present: number;
  errors: number;
  rewardsCreate: number;
  campaignsCreate: number;
  excluded: { what: string; why: string }[];
  stories: {
    state: StoriesState;
    reasons: string[];
    waitingFor: string[];
    rows: number;
    totalRows: number;
    jobId: string | null;
    members: StoriesPlan["members"];
  };
}
export function summarizeProgram(args: { plan: PlanItem[]; stories: StoriesPlan; actor: string }): ProgramSummary;
export function planProgram(args: {
  seed: Record<string, any>;
  api: Api;
  now: number;
  offline?: boolean;
}): Promise<{ plan: PlanItem[]; ctx: PlanContext; stories: StoriesPlan }>;
